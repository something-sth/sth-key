package com.something.sthkey.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.live2d.Live2DModels
import java.io.File

/**
 * Live2D 模型预览图。
 *
 * ============================================================
 * 图从哪来：优先 cover，其次 icon
 * ============================================================
 * - **自带皮肤包的模型**（`full`）有 `resources/cover.png` —— 作者给的预览图；
 * - **纯人物模型**（`cat`）**没有** cover，但有 `icon.png`
 *   （VTube Studio 的模型缩略图，作者一般都会放）。
 *
 * 只认 cover 的话，纯人物模型会全部没有预览，所以做两级回退；
 * 再没有就给一个图标占位，而不是留个空框。
 *
 * ============================================================
 * 两个调用方，一份实现
 * ============================================================
 * 模型选择列表（[Live2DModelPickerDialog]）与配置编辑页的样式预览
 * （[com.something.sthkey.ui.preview.ConfigPreview]）都要显示它。
 * 哪里需要就复制一份的话，两处的"找不到图怎么办"迟早会长得不一样，
 * 所以加载逻辑只此一份，调用方只决定尺寸。
 *
 * @param widthDp/heightDp 固定显示尺寸：不同模型的图尺寸不同，
 *   固定框 + `ContentScale.Fit` 才不会让列表高矮不一
 * @param drawBackground 是否画自己的底色与圆角。选择器里需要（列表背景是白的），
 *   编辑页的预览块**已经**有底色和边框了，再套一层会变成"框中框"
 */
@Composable
fun Live2DModelThumbnail(
    modelId: String,
    widthDp: Float,
    heightDp: Float,
    modifier: Modifier = Modifier,
    drawBackground: Boolean = true,
) {
    val context = LocalContext.current
    val bitmap = remember(modelId, widthDp, heightDp) {
        loadThumbnail(context, modelId, widthDp, heightDp)
    }

    Box(
        modifier = modifier
            .size(widthDp.dp, heightDp.dp)
            .then(
                if (drawBackground) {
                    Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            RoundedCornerShape(6.dp),
                        )
                        .clip(RoundedCornerShape(6.dp))
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 读取并降采样预览图；读不到返回 null（调用方给占位图标）。
 *
 * 解码在主线程上做，但**已经降到目标尺寸附近**，代价可以忽略；
 * 关键是不能把原图整个解出来 —— 原图可能是 612×354 甚至更大，
 * 一屏模型列表全量解码会吃掉几十 MB 位图，表现为"打开列表卡一下"。
 */
private fun loadThumbnail(
    context: Context,
    modelId: String,
    widthDp: Float,
    heightDp: Float,
): Bitmap? {
    val entry = Live2DModels.find(modelId)
    val source = when {
        // 内置模型：直接从 assets 读
        entry?.dir == null -> builtinCover(entry?.id ?: modelId)
        // 导入模型：先找皮肤包的 cover，再退回 icon
        else -> findFirstFile(entry.dir, "cover.png") ?: findFirstFile(entry.dir, "icon.png")
    } ?: return null

    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, source)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, widthDp, heightDp)
        }
        openStream(context, source)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()
}

/** 预览图来源：内置模型只能走 assets，导入模型只能走文件 */
private sealed interface ThumbSource {
    data class Asset(val path: String) : ThumbSource
    data class Local(val file: File) : ThumbSource
}

private fun builtinCover(modelId: String): ThumbSource? = when (modelId) {
    Live2DModels.KEYBOARD -> ThumbSource.Asset("bongocat/keyboard/resources/cover.png")
    Live2DModels.STANDARD -> ThumbSource.Asset("bongocat/standard/resources/cover.png")
    else -> null
}

/** 在目录里按文件名找第一个匹配（皮肤包可能嵌在子目录里） */
private fun findFirstFile(dir: File, fileName: String): ThumbSource? =
    runCatching {
        dir.walkTopDown()
            .firstOrNull { it.isFile && it.name.equals(fileName, ignoreCase = true) }
            ?.let { ThumbSource.Local(it) }
    }.getOrNull()

private fun openStream(context: Context, source: ThumbSource): java.io.InputStream? =
    when (source) {
        is ThumbSource.Asset -> runCatching { context.assets.open(source.path) }.getOrNull()
        is ThumbSource.Local -> runCatching { source.file.inputStream() }.getOrNull()
    }

/**
 * 求 2 的幂次降采样倍率：解出来的图只要不小于目标尺寸的两倍就够清晰了。
 *
 * 用 2 的幂是因为 `inSampleSize` 只在取 2 的幂时才有保证（其余值会被向下取整）。
 */
private fun sampleSizeFor(width: Int, height: Int, targetWidthDp: Float, targetHeightDp: Float): Int {
    var sample = 1
    while (width / (sample * 2) >= targetWidthDp * 2 && height / (sample * 2) >= targetHeightDp * 2) {
        sample *= 2
    }
    return sample
}
