package com.something.sthkey.data.shortcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.custom.CustomComponent
import com.something.sthkey.domain.custom.CustomLayout
import com.something.sthkey.domain.custom.KeyComponent
import com.something.sthkey.domain.custom.TextComponent
import java.io.File
import java.io.FileOutputStream

/**
 * 生成快捷方式图标。
 *
 * ============================================================
 * 两条完全不同的路
 * ============================================================
 * | 样式 | 图标从哪来 |
 * |---|---|
 * | key / 自定义 key | **一个固定预设**（[generateKeyboardIcon]），用 Canvas 画 |
 * | Live2D | **模型自带的预览图**（[loadLive2DCover]） |
 * | 用户自选 | 传上来的图片（[saveCustomIcon]） |
 *
 * ============================================================
 * ⚠️ 为什么 key 样式不再"每份配置一张"
 * ============================================================
 * 第一版给每份配置画"用它的配色 + 布局"的图标，失败了两次：
 *
 * 1. **全黑**：`colors.keyUp` 的默认值就是黑色（`0x000000`），
 *    而图标以它作背景 —— 默认配色的配置画出来就是一张黑图；
 * 2. 就算配色解决了，**收益也很低**：key 样式的布局由 `KeyLayout` 固定，
 *    用户只能改颜色与间距，图标之间的差异小到看不出来。
 *
 * 所以现在 key 与自定义 key **共用一个固定预设**。
 * 想要"就长配置那样"的走「传图片」。
 *
 * ============================================================
 * ⚠️ 为什么不用配置预览截图
 * ============================================================
 * 配置预览（`ui/preview/ConfigPreview`）是**纯 Compose** 的，要拿它的位图
 * 得用 `GraphicsLayer.toImageBitmap` 或套一层 `AndroidView` 手动
 * measure/layout/draw。而且 Live2D 样式的预览是**异步**的（缩略图要先解码），
 * 钉图标那一刻多半还没画出来，抓到的是一张空框。
 */
object ShortcutIcons {

    private const val TAG = "ShortcutIcon"

    /** 图标边长（px） */
    private const val ICON_SIZE = 192

    /**
     * 生成**固定**的"键位"预设图标（浅色键帽 + 深色底）。
     *
     * ============================================================
     * ⚠️ 刻意**不跟配置的配色**
     * ============================================================
     * 第一版用配置的 `colors.keyUp` 做背景，结果是**全黑** ——
     * 那个字段的默认值就是黑色（`0x000000`），而图标以它作底，
     * 画出来就是一张黑图，连里面的键盘描边都看不清
     * （描边只是把黑微微调亮）。
     *
     * 现在用一个固定的配色，任何配置下都清楚，也一眼能认出
     * "这是 sth key 的键位图标"。
     *
     * @return 失败返回 null（调用方回落应用图标）
     */
    fun generateKeyboardIcon(): Bitmap? = try {
        drawKeyboardPreset()
    } catch (e: Exception) {
        AppLog.e(TAG, "生成键位图标失败", e)
        null
    } catch (e: OutOfMemoryError) {
        AppLog.e(TAG, "生成键位图标内存不足", e)
        null
    }

    /**
     * 同上，但**落盘**并返回文件。
     *
     * ⚠️ 分成两个函数、而不是一个 `asFile` 开关返回 `Any?`：
     * 那种"返回值类型取决于参数"的签名会让每个调用点都要做一次
     * 类型判断或强转，而强转写错在编译期是能过的
     * （`Any?` → 强转成 `File` 只要加了 `as` 就合法）。
     */
    fun generateKeyboardIconFile(context: Context): File? {
        val bitmap = generateKeyboardIcon() ?: return null
        return try {
            val file = iconFile(context, "preset_keyboard")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            file
        } catch (e: Exception) {
            AppLog.e(TAG, "保存键位图标失败", e)
            bitmap.recycle()
            null
        }
    }

    /**
     * 画固定预设：深色渐变底 + 三排浅色键帽。
     *
     * 键帽数量与排布**写死**（不读配置）—— 它是"键位样式"这个概念的图标，
     * 不是某一份配置的缩略图。三排递减的形状足够表意，
     * 而且在 48dp 下也看得清（这也是不画真实布局的原因：
     * 真实布局缩到那么小只剩一排糊掉的小方块）。
     */
    private fun drawKeyboardPreset(): Bitmap {
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        /* 底：深蓝灰渐变。与应用的深色主题一致，又不至于全黑 */
        canvas.drawRect(
            0f, 0f, ICON_SIZE.toFloat(), ICON_SIZE.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, ICON_SIZE.toFloat(), ICON_SIZE.toFloat(),
                    Color.rgb(0x37, 0x41, 0x51),
                    Color.rgb(0x1F, 0x29, 0x37),
                    Shader.TileMode.CLAMP,
                )
            },
        )

        val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(0xE8, 0xEE, 0xF6)
        }

        /* 全部比例都以 ICON_SIZE 为基准：改边长不用改这里 */
        val rowHeight = ICON_SIZE * 0.105f
        val gap = ICON_SIZE * 0.055f
        val radius = rowHeight * 0.28f
        val rows = 3
        val totalHeight = rows * rowHeight + (rows - 1) * gap
        val startY = (ICON_SIZE - totalHeight) / 2f

        for (row in 0 until rows) {
            /*
             * 每排少一个键、整排居中 —— 整体呈一个下窄上宽的梯形，
             * 比"三排等长的方块"更像一块键盘。
             */
            val keysInRow = 3 - row
            val rowWidth = keysInRow * rowHeight + (keysInRow - 1) * gap
            val startX = (ICON_SIZE - rowWidth) / 2f
            val y = startY + row * (rowHeight + gap)

            for (col in 0 until keysInRow) {
                val x = startX + col * (rowHeight + gap)
                canvas.drawRoundRect(
                    RectF(x, y, x + rowHeight, y + rowHeight),
                    radius, radius, keyPaint,
                )
            }
        }

        return bitmap
    }

    fun loadLive2DCover(context: Context, modelId: String): Bitmap? = runCatching {
        val entry = com.something.sthkey.domain.live2d.Live2DModels.find(modelId)

        val bitmap = if (entry?.dir == null) {
            /* 内置模型：从 assets 读 */
            builtinCoverPath(entry?.id ?: modelId)?.let { path ->
                context.assets.open(path).use { BitmapFactory.decodeStream(it) }
            }
        } else {
            /* 导入模型：先找皮肤包的 cover，再退回 icon */
            val dir = entry.dir
            val file = findFirstFile(dir, "cover.png") ?: findFirstFile(dir, "icon.png")
            file?.let { BitmapFactory.decodeFile(it.absolutePath) }
        }

        bitmap
    }.getOrNull()

    /**
     * 把一张位图裁成正方形、缩放、存成图标。
     *
     * @return 落盘的文件；失败返回 null
     */
    fun saveImageAsIcon(context: Context, source: Bitmap, name: String): File? {
        return try {
            val side = minOf(source.width, source.height)
            val square = Bitmap.createBitmap(
                source,
                (source.width - side) / 2,
                (source.height - side) / 2,
                side,
                side,
            )
            val scaled = Bitmap.createScaledBitmap(square, ICON_SIZE, ICON_SIZE, true)

            val file = iconFile(context, name)
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            /*
             * 回收中间位图。
             *
             * ⚠️ `createBitmap` / `createScaledBitmap` 在尺寸已经合适时
             * **会返回原对象**，所以要逐个判同一性 —— 不判的话会 recycle
             * 掉还要用的那张（`source` 可能是调用方持有的模型封面）。
             */
            if (square !== source && square !== scaled) square.recycle()
            if (scaled !== source) scaled.recycle()

            file
        } catch (e: Exception) {
            AppLog.e(TAG, "保存图标失败", e)
            null
        } catch (e: OutOfMemoryError) {
            AppLog.e(TAG, "保存图标内存不足", e)
            null
        }
    }

    /**
     * 把用户选的图片（SAF Uri）存成图标。
     *
     * ⚠️ 必须**复制**进私有目录，不能只记下那个 Uri：SAF 给的读权限是
     * 临时的，重启后就失效了，而快捷方式图标是**钉下去那一刻**由系统
     * 读走的 —— 那时可能已经读不到了。
     */
    fun saveCustomIcon(context: Context, source: android.net.Uri): File? {
        return try {
            val input = context.contentResolver.openInputStream(source) ?: return null
            val decoded = BitmapFactory.decodeStream(input)
            input.close()

            if (decoded == null) {
                AppLog.w(TAG, "选中的图片无法解码")
                return null
            }

            val file = saveImageAsIcon(context, decoded, "custom")
            decoded.recycle()
            file
        } catch (e: Exception) {
            AppLog.e(TAG, "保存自定义图标失败", e)
            null
        } catch (e: OutOfMemoryError) {
            AppLog.e(TAG, "保存自定义图标内存不足", e)
            null
        }
    }

    /* ============================================================
     * 工具
     * ============================================================ */

    /** 在目录（含子目录）里找第一个同名文件 */
    private fun findFirstFile(dir: File, name: String, depth: Int = 0): File? {
        if (depth > MAX_SEARCH_DEPTH) return null
        dir.listFiles()?.forEach { child ->
            if (child.isFile && child.name.equals(name, ignoreCase = true)) return child
            if (child.isDirectory) findFirstFile(child, name, depth + 1)?.let { return it }
        }
        return null
    }

    /** 内置模型的封面在 assets 里 */
    private fun builtinCoverPath(modelId: String): String? = when (modelId) {
        com.something.sthkey.domain.live2d.Live2DModels.KEYBOARD ->
            "bongocat/keyboard/resources/cover.png"

        com.something.sthkey.domain.live2d.Live2DModels.STANDARD ->
            "bongocat/standard/resources/cover.png"

        else -> null
    }

    /**
     * 图标文件的落点。
     *
     * ⚠️ 放在 `filesDir` 而不是 `cacheDir`：缓存会被系统在存储紧张时清掉，
     * 而快捷方式图标是钉下去那一刻由**系统**读走的（可能在很久以后，
     * 比如用户重启手机后桌面重新加载图标）。放缓存里就会变成白图标。
     */
    private fun iconFile(context: Context, name: String): File {
        val dir = File(context.filesDir, ICON_DIR).apply { mkdirs() }
        return File(dir, "$name.png")
    }

    private const val ICON_DIR = "shortcut_icons"
    private const val MAX_SEARCH_DEPTH = 4
}
