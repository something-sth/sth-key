package com.something.sthkey.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.custom.MediaComponent
import com.something.sthkey.domain.custom.MediaFit
import com.something.sthkey.domain.custom.MediaKind
import com.something.sthkey.domain.media.CustomMediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext









/**
 * **媒体组件**（图片 / 动图 / 视频）的渲染。
 *
 * ============================================================
 * ⚠️ 为什么它必须是独立分支
 * ============================================================
 * [CustomComponentView] 的形参是 `TextualComponent`，媒体组件**不是**它。
 * 不分派的话，那里会抛 `ClassCastException` —— 加一个图片组件就能让悬浮窗崩。
 *
 * ============================================================
 * ⚠️ 解码/加载都是异步的
 * ============================================================
 * 一张 4K 图解码要几十毫秒、视频初始化更久。放在组合里同步做会**卡住第一帧**，
 * 表现是"加了个图片，整个悬浮窗卡一下"。所以统一走 [produceState] + IO。
 */
@Composable
internal fun MediaComponentView(
    component: MediaComponent,
    scale: Float,
    /**
     * 编辑器画布里**不播视频**（也尽量不动图）。
     *
     * ⚠️ 编辑器里可能有几十个组件，全都播起来会发热+耗电，
     * 而编辑时用户要看的是"位置和大小对不对"，不是动画。
     */
    staticPreview: Boolean = false,
) {
    val widthDp = (component.width * scale).dp
    val heightDp = (component.height * scale).dp
    val opacity = component.opacityPercent.coerceIn(0, 100) / 100f
    val corner = RoundedCornerShape(
        (minOf(widthDp.value, heightDp.value) * component.cornerRadiusPercent / 100f).dp,
    )
    val fit = when (component.fit) {
        MediaFit.FIT -> ContentScale.Fit
        MediaFit.COVER -> ContentScale.Crop
        /* ⚠️ 拉伸:宽高各自拉满，不保比例（用户点名要的） */
        MediaFit.STRETCH -> ContentScale.FillBounds
    }

    Box(
        modifier = Modifier
            .offset(x = (component.x * scale).dp, y = (component.y * scale).dp)
            .size(widthDp, heightDp)
            .alpha(opacity)
            .clip(corner),
    ) {
        val file = CustomMediaStore.fileOfId(component.assetId)
        /*
         * ⚠️ 目标尺寸要按**像素**算:窗口里的 dp 与真实像素差一个 density
         * （这台机器 2.975）。早先直接把 dp 当像素传，解码出来的图只有
         * 需要的 1/3，细笔画的透明文字就糊成一团。
         *
         * ⚠️ 还要把尺寸**分档**当 produceState 的 key:悬浮窗首次组合时
         * `scale` 往往还没定下来（先 1.0，随后按屏幕自适应放大），
         * 只按文件路径做 key 的话会一直用第一帧那张小图 ——
         * 表现就是"偶尔糊，重开窗口又正常"（用户实测）。
         */
        val density = LocalDensity.current.density
        val targetPx = (widthDp.value * density).toInt().coerceAtLeast(1)
        val sizeBucket = (targetPx / 64).coerceAtLeast(1)

        when {
            file == null -> MediaPlaceholder(component)

            component.kind == MediaKind.IMAGE -> {
                val bitmap by produceState<Bitmap?>(initialValue = null, file.absolutePath, sizeBucket) {
                    value = withContext(Dispatchers.IO) { decodeSampled(file, targetPx) }
                }
                val image = bitmap
                if (image != null) {
                    /* 染色 + 轮廓描边见 [MediaArtwork] */
                    MediaArtwork(
                        bitmap = image,
                        contentScale = fit,
                        tintColor = component.tintColor,
                        tintPercent = component.tintPercent,
                        outlineColor = component.outlineColor,
                        outlineWidthDp = minOf(widthDp.value, heightDp.value) *
                            component.outlineWidthPercent / 100f,
                    )
                } else {
                    MediaPlaceholder(component)
                }
            }

            component.kind == MediaKind.GIF && staticPreview -> {
                /*
                 * ⚠️ 编辑器画布与配置预览里**不播动图**（用户要求"预览静态就好"）。
                 *
                 * 做法:按首帧解码成静态位图 —— 既不占解码线程，也不会在
                 * 一屏几十个组件时把 CPU 拉满。
                 */
                val firstFrame by produceState<Bitmap?>(initialValue = null, file.absolutePath, sizeBucket) {
                    value = withContext(Dispatchers.IO) {
                        decodeSampled(file, targetPx)
                    }
                }
                val frame = firstFrame
                if (frame != null) {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = null,
                        contentScale = fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    MediaPlaceholder(component)
                }
            }

            component.kind == MediaKind.GIF -> {
                /*
                 * ⚠️⚠️ **用 Compose 自己画，不用 `AndroidView`**
                 *
                 * 用户报的现象:"拖 GIF 会拦截触摸，但拖不动悬浮窗；摸它旁边的空气能拖"。
                 *
                 * 根因:悬浮窗的拖动挂在**根视图**的 `setOnTouchListener` 上
                 * （`OverlayService.setupDragListener`），而 `AndroidView` 是一个
                 * **真实的原生 View 子树** —— 触摸落到它身上，根视图就收不到 DOWN。
                 *
                 * ⚠️ 我先试了 `pointerInteropFilter { false }`，**没用**:
                 * 那个只影响 Compose 自己的指针分发，真实 View 层级里的子 View
                 * 依然会被系统派发到。所以这次改成"根本不存在原生 View"。
                 *
                 * 做法:`AnimatedImageDrawable` 自己负责解码与循环，
                 * 我们只把每一帧 `draw` 到 Compose 的画布上，并用它的
                 * 帧回调触发重绘。
                 */
                val drawable = remember(file.absolutePath) { loadAnimated(file) }
                var frameTick by remember(file.absolutePath) { mutableIntStateOf(0) }

                /*
                 * ⚠️ 逐帧重绘。
                 *
                 * `AnimatedImageDrawable` 只会自己推进帧，**不会**告诉 Compose 重绘
                 * （它不是 View），所以必须由我们按屏幕帧节奏触发。
                 *
                 * ⚠️ 只在"有 drawable"时才挂这个循环:资源没选/解码失败时
                 * 不该白白每帧唤醒一次组合。
                 */
                if (drawable != null) {
                    /*
                     * ⚠️⚠️ **必须 `start()`**。
                     *
                     * `AnimatedImageDrawable` 在 `draw()` 时**只画当前帧**;
                     * 帧的推进靠它自己的动画驱动，而那个驱动只在 `start()` 之后才跑。
                     *
                     * ⚠️ 这个坑我踩了:把渲染从 `AndroidView` 改成 Compose 绘制时
                     * 顺手把原来的 `start()` 丢了 —— 动图在悬浮窗上变成静态图，
                     * 而编辑器/预览本来就是静态，所以只有悬浮窗能看出区别。
                     */
                    DisposableEffect(drawable) {
                        runCatching { drawable.start() }
                        onDispose { runCatching { drawable.stop() } }
                    }
                    LaunchedEffect(file.absolutePath) {
                        while (true) {
                            withFrameMillis { frameTick++ }
                        }
                    }
                }

                if (drawable == null) {
                    MediaPlaceholder(component)
                } else {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        /* 读一下 tick:每帧变化才会重绘 */
                        @Suppress("UNUSED_EXPRESSION")
                        frameTick

                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            val srcWidth = drawable.intrinsicWidth.coerceAtLeast(1)
                            val srcHeight = drawable.intrinsicHeight.coerceAtLeast(1)
                            /*
                             * 按 `fit` 把 drawable 摆进组件框:
                             * - FillBounds(拉伸):宽高各自拉满
                             * - Crop(铺满裁剪):等比放大到铺满，超出部分被组件框裁掉
                             * - Fit(完整显示):等比缩小到完整可见，居中
                             */
                            val uniform = when (fit) {
                                ContentScale.FillBounds -> null
                                ContentScale.Crop ->
                                    maxOf(size.width / srcWidth, size.height / srcHeight)
                                else ->
                                    minOf(size.width / srcWidth, size.height / srcHeight)
                            }
                            val drawWidth: Int
                            val drawHeight: Int
                            val left: Float
                            val top: Float
                            if (uniform == null) {
                                drawWidth = size.width.toInt()
                                drawHeight = size.height.toInt()
                                left = 0f
                                top = 0f
                            } else {
                                drawWidth = (srcWidth * uniform).toInt()
                                drawHeight = (srcHeight * uniform).toInt()
                                left = (size.width - drawWidth) / 2f
                                top = (size.height - drawHeight) / 2f
                            }
                            native.save()
                            native.translate(left, top)
                            drawable.setBounds(0, 0, drawWidth, drawHeight)
                            drawable.draw(native)
                            native.restore()
                        }
                    }
                }
            }
            /*
             * ============================================================
             * 视频
             * ============================================================
             * ⚠️ 静态预览（编辑器画布 / 配置预览）:取**首帧位图**。
             * 用户要求"预览静态就好，不要动态"，而播放器在编辑器里
             * 几十个组件时会明显发热。
             */
            component.kind == MediaKind.VIDEO && staticPreview -> {
                val firstFrame by produceState<Bitmap?>(initialValue = null, file.absolutePath, sizeBucket) {
                    value = withContext(Dispatchers.IO) { videoFirstFrame(file) }
                }
                val frame = firstFrame
                if (frame != null) {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = null,
                        contentScale = fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    MediaPlaceholder(component)
                }
            }

            component.kind == MediaKind.VIDEO -> {
                /*
                 * ⚠️ 播放器在**悬浮窗**里才真的播（`MediaPlayer` + `TextureView`）。
                 *
                 * ⚠️ 触摸不用担心:`OverlayService` 的 `DragTouchHost` 在容器层
                 * 无条件接管了手势，`TextureView` 收不到事件（见那的说明）。
                 */
                VideoSurface(
                    file = file,
                    fit = fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                MediaPlaceholder(component)
            }
        }
    }
}

/**
 * 带**染色**与**轮廓描边**的位图绘制（图片 / 动图的静态首帧都走这里）。
 *
 * ⚠️ 染色用 `BlendMode.Modulate`（与位图字体同一套）:按亮度相乘，
 * 保留明暗与纹理;`SrcIn` 会把图变成纯色剪影，细节全丢。
 * `tintPercent` 控制混多少进去。
 *
 * ⚠️ 轮廓描边 = 同一张图按圆周偏移复制 8 份，每份 `tint(描边色, SrcIn)`
 * （保留 alpha → 透明处不描），再盖上原图 → "贴纸描边"。
 *
 * ⚠️ 颜色必须补 `0xFF000000` 的 alpha:`Color(0xFFFFFF)` 会被当成
 * `0xAARRGGBB`，高 8 位是 0 → 全透明（踩过这个坑，画了但看不见）。
 */
@Composable
private fun MediaArtwork(
    bitmap: Bitmap,
    contentScale: ContentScale,
    tintColor: Long,
    tintPercent: Int,
    outlineColor: Long,
    outlineWidthDp: Float,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val tint = Color(0xFF000000L or (tintColor and 0xFFFFFF))
    val outline = Color(0xFF000000L or (outlineColor and 0xFFFFFF))
    val strength = tintPercent.coerceIn(0, 100) / 100f
    val outlineWidth = outlineWidthDp.coerceAtLeast(0f)

    Box(modifier = Modifier.fillMaxSize()) {
        if (outlineWidth > 0f) {
            repeat(OUTLINE_STEPS) { index ->
                val angle = 2.0 * Math.PI * index / OUTLINE_STEPS
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = contentScale,
                    colorFilter = ColorFilter.tint(outline, BlendMode.SrcIn),
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(
                            x = (Math.cos(angle) * outlineWidth).toFloat().dp,
                            y = (Math.sin(angle) * outlineWidth).toFloat().dp,
                        ),
                )
            }
        }

        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )

        if (strength > 0f) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = contentScale,
                alpha = strength,
                colorFilter = ColorFilter.tint(tint, BlendMode.Modulate),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 轮廓描边复制的份数:8 份在屏幕上已看不出断口，再多只是白花性能 */
private const val OUTLINE_STEPS = 8
/** 还没选资源 / 文件丢了 / 该类型尚未实现时的占位框 */
@Composable
private fun MediaPlaceholder(component: MediaComponent) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x33000000)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when {
                component.assetId.isBlank() -> "${component.kind.label}（未选择资源）"
                else -> "${component.kind.label}（资源已删除）"
            },
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

/**
 * 按**显示尺寸**算 `inSampleSize` 再解码。
 *
 * ⚠️ 直接 `decodeFile` 一张 4K 图 = 几十 MB，悬浮窗里放几张就 OOM。
 */
/**
 * 把图片解码成**够用**的位图。
 *
 * @param targetWidthPx 组件在屏幕上占的**像素**宽度（调用方已乘过 density）
 *
 * ⚠️ 两条硬要求:
 * 1. **按像素算**，不是 dp:dp→px 差一个 density（这台机器 2.975），
 *    把 dp 当像素直接损失约 2/3 的细节 —— 细笔画的透明文字就是那时糊掉的。
 * 2. `inSampleSize` 只能取 2 的幂:取值时**宁可偏大**（分辨率高一点），
 *    缩小显示比放大清晰得多。
 */
private fun decodeSampled(file: File, targetWidthPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2

    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}

/** GIF / 动态 webp → 会自己循环播放的 Drawable */
private fun loadAnimated(file: File): AnimatedImageDrawable? = runCatching {
    val drawable: Drawable = Drawable.createFromPath(file.absolutePath) ?: return null
    drawable as? AnimatedImageDrawable
}.getOrNull()

/**
 * 视频组件的真实播放。
 *
 * ============================================================
 * ⚠️ 为什么用 `MediaPlayer` + `TextureView`（而不是 media3 / VideoView）
 * ============================================================
 * | 方案 | 取舍 |
 * |---|---|
 * | **`MediaPlayer` + `TextureView`**（这里） | 零新依赖;循环用 `isLooping`,静音用 `setVolume(0,0)` |
 * | media3 ExoPlayer | 循环更平滑，但多 1~2 MB 依赖 |
 * | `VideoView` | 内部也是 MediaPlayer，但不好精确控制 Surface 生命周期 |
 *
 * ⚠️ `TextureView` 必须硬件加速才出画面 —— 悬浮窗已经显式加了
 * `FLAG_HARDWARE_ACCELERATED`（见 `OverlayService.windowFlags`）。
 *
 * ⚠️ **音频焦点**:`MediaPlayer` 默认不申请焦点，所以静音时不会压掉游戏声音;
 * 不静音时也**不要**去 `requestAudioFocus` —— 那会把游戏声音停掉。
 */
@Composable
private fun VideoSurface(
    file: File,
    fit: ContentScale,
    modifier: Modifier,
) {
    val context = LocalContext.current
    /*
     * ⚠️ 用 holder 而不是 `mutableStateOf<MediaPlayer?>`:
     * 播放器是"命令式对象"，把它塞进 State 会让每次重组都读写一次，
     * 而且 `Surface` 到达与播放器创建的顺序在 Compose 里不固定。
     */
    val holder = remember(file.absolutePath) { VideoHolder() }

    DisposableEffect(file.absolutePath) {
        onDispose { holder.release() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        holder.start(
                            context = ctx,
                            file = file,
                            surface = Surface(surfaceTexture),
        
                        )
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                        holder.stop()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                }
            }
        },
    )
}

/** 播放器的持有者（把命令式生命周期从 Compose 的重组里隔开） */
private class VideoHolder {

    private var player: MediaPlayer? = null
    private var surface: Surface? = null

    /** ⚠️ 记住静音状态:Surface 可能比 `LaunchedEffect(muted)` 晚到 */
    var muted: Boolean = true
        private set

    fun setMuted(value: Boolean) {
        muted = value
        runCatching {
            val volume = if (value) 0f else 1f
            player?.setVolume(volume, volume)
        }
    }

    fun start(context: Context, file: File, surface: Surface) {
        stop()
        this.surface = surface
        this.muted = muted
        val mp = MediaPlayer()
        player = mp
        runCatching {
            mp.setDataSource(file.absolutePath)
            /* ⚠️ 循环播放是需求里点名的行为 */
            mp.isLooping = true
            mp.setSurface(surface)
            mp.setOnPreparedListener { prepared ->
                val volume = if (muted) 0f else 1f
                prepared.setVolume(volume, volume)
                prepared.start()
            }
            mp.setOnErrorListener { _, _, _ ->
                /*
                 * ⚠️ 编码不支持时**静默失败**:悬浮窗上留一块占位比弹一堆报错好。
                 * 具体原因写日志，用户在调试页能看到。
                 */
                false
            }
            mp.prepareAsync()
        }.onFailure { AppLog.w("Media", "视频初始化失败:${it.javaClass.simpleName}") }
    }

    fun stop() {
        runCatching { player?.let { if (it.isPlaying) it.stop() } }
        runCatching { player?.release() }
        player = null
        runCatching { surface?.release() }
        surface = null
    }

    fun release() = stop()
}

/** 取视频**首帧**（编辑器/预览的静态图） */
private fun videoFirstFrame(file: File): Bitmap? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } finally {
        runCatching { retriever.release() }
    }
}.getOrNull()
