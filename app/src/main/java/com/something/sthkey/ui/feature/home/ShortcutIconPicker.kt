package com.something.sthkey.ui.feature.home

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.IconCompat
import com.something.sthkey.data.shortcut.ShortcutIcons
import com.something.sthkey.data.shortcut.ShortcutPublisher
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.style.StyleId

/**
 * 图标选项的标识：固定预设用这几个保留字，Live2D 用配置自己的 id。
 */
private const val DEFAULT = "__default__"
private const val KEYBOARD = "__keyboard__"
private const val CUSTOM = "__custom__"

/** 一个可选的图标：标识 + 显示名 + 已经算好的位图 */
data class ShortcutIconOption(
    val id: String,
    val label: String,
    val bitmap: android.graphics.Bitmap?,
)

/**
 * 为快捷方式准备图标候选。
 *
 * ============================================================
 * ⚠️ key / 自定义 key 样式只有**一个固定预设**，不是每份配置一张
 * ============================================================
 * 第一版给每份配置都画了一张"用它的配色 + 布局"的图标，结果是
 * **全黑**：`colors.keyUp` 的默认值就是黑色（`0x000000`），
 * 而图标以它作背景 —— 默认配色的配置画出来就是一张黑图，
 * 连里面的键盘描边都看不清（描边只是把黑微微调亮）。
 *
 * 就算把配色问题解决，"每份配置一张"的收益也很低：
 * key 样式的布局由 `KeyLayout` 固定，用户只能改颜色与间距 ——
 * 图标之间的差异小到看不出来。
 *
 * 所以现在：**key 与自定义 key 共用一个固定预设**（浅色底 + 键盘轮廓）。
 * 想要"就长配置那样"的走「传图片」。
 *
 * ============================================================
 * Live2D 保留"每个模型一张"
 * ============================================================
 * 它的内容是 WebGL 画面，没法用 Canvas 画，只能取模型自带的预览图
 * （`cover.png` → `icon.png`，顺序与 `Live2DModelThumbnail` 一致）。
 * 而不同模型的封面**真的不一样**，所以每个都列出来是有意义的。
 */
fun buildIconOptions(
    context: Context,
    configs: List<KeyStrokesConfig>,
): List<ShortcutIconOption> = buildList {
    add(ShortcutIconOption(id = DEFAULT, label = "默认", bitmap = null))

    add(
        ShortcutIconOption(
            id = KEYBOARD,
            label = "键位",
            bitmap = ShortcutIcons.generateKeyboardIcon(),
        ),
    )

    /* 只为 Live2D 样式的配置各出一张 */
    configs.filter { it.styleId == StyleId.KEYBOARD_CAT }.forEach { config ->
        add(
            ShortcutIconOption(
                id = config.id,
                label = config.name,
                bitmap = live2dIconBitmap(context, config),
            ),
        )
    }
}

/** Live2D 配置的图标：取模型封面并裁方 */
private fun live2dIconBitmap(context: Context, config: KeyStrokesConfig): android.graphics.Bitmap? {
    val cover = ShortcutIcons.loadLive2DCover(context, config.live2d.modelId) ?: return null
    val file = ShortcutIcons.saveImageAsIcon(context, cover, "live2d_${config.id}")
    cover.recycle()
    return file?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }
}

/**
 * 图标选择器：一行可**横向滚动**的方形缩略图。
 *
 * ⚠️ 必须能横向滚动。第一版是个固定宽度的 `Row`，项一多就把
 * **「传图片」那个按钮顶出屏幕**了 —— 用户看不到，也就用不了。
 * 横向滚动之后无论多少项都能划到。
 */
@Composable
fun ShortcutIconPicker(
    options: List<ShortcutIconOption>,
    selectedId: String,
    customBitmap: android.graphics.Bitmap?,
    onSelect: (String) -> Unit,
    onPickCustom: () -> Unit,
) {
    val all = remember(options, customBitmap) {
        if (customBitmap == null) {
            options
        } else {
            options + ShortcutIconOption(id = CUSTOM, label = "自己选的图", bitmap = customBitmap)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "图标",
            style = MaterialTheme.typography.bodyLarge,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 6.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            all.forEach { option ->
                IconChoice(
                    option = option,
                    selected = option.id == selectedId,
                    onClick = { onSelect(option.id) },
                )
            }

            /* "传图片"永远排在最后，用加号表示"这里可以传图" */
            CustomIconButton(onClick = onPickCustom)
        }
    }
}

@Composable
private fun IconChoice(
    option: ShortcutIconOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .size(width = 64.dp, height = 84.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = option.bitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = option.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                )
            } else {
                /* "默认"没有位图，用文字占位 */
                Text(
                    text = "icon",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = option.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun CustomIconButton(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .size(width = 64.dp, height = 84.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "＋",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "传图片",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 把界面选出的图标标识换成一个可以交给系统的 `IconCompat`。
 *
 * @param selectedId [DEFAULT] / [KEYBOARD] / [CUSTOM] / 某个 Live2D 配置的 id
 * @param customIconFile 用户传的图片已经落盘的位置（[CUSTOM] 时用）
 */
fun resolveIcon(
    context: Context,
    selectedId: String,
    configs: List<KeyStrokesConfig>,
    customIconFile: java.io.File?,
): IconCompat? = when (selectedId) {
    /* 默认：交给 ShortcutPublisher 回落应用图标 */
    DEFAULT -> null

    KEYBOARD -> ShortcutIcons.generateKeyboardIconFile(context)
        ?.let { ShortcutPublisher.iconFromFile(it) }

    CUSTOM -> customIconFile?.let { ShortcutPublisher.iconFromFile(it) }

    else -> {
        /*
         * 某个 Live2D 配置的图标 —— 重新取一次模型封面。
         *
         * 不缓存路径是因为这里要的是 `IconCompat`（内含位图），
         * 而取一张 192×192 的图代价很小。换成"记住文件路径、读取时
         * 再解码"反而多一层可能失败的地方（文件被清掉、配置被删）。
         */
        configs.firstOrNull { it.id == selectedId }
            ?.let { live2dIconBitmap(context, it) }
            ?.let { ShortcutPublisher.iconFromBitmap(it) }
    }
}
