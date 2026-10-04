package com.something.sthkey.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.something.sthkey.capture.StickState
import com.something.sthkey.ui.overlay.gamepad.Gamepad2Content
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.custom.CustomLayout
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.domain.style.StyleId
import com.something.sthkey.ui.component.Live2DModelThumbnail
import com.something.sthkey.ui.overlay.CustomKeyCanvas
import com.something.sthkey.ui.overlay.KeyGrid

/** 预览框内边距（dp）：让预览图与外框之间留一点呼吸空间 */
private const val PREVIEW_PADDING_DP = 6f

/**
 * 配置预览。
 *
 * ============================================================
 * 与真实悬浮窗的关系
 * ============================================================
 * 预览**不自己另写一套按键**，直接复用悬浮窗的渲染组件 [KeyGrid] 与
 * 同一套布局常量 [KeyLayout]。所以两者不可能不一致 —— 它们就是同一段代码。
 *
 * ============================================================
 * 缩放怎么算
 * ============================================================
 * 布局坐标是**物理像素**，Compose 用 dp，所以任何绘制都必须乘
 * `1/density`（见 [KeyLayout.pxToDpFactor]）。预览在此基础上再乘一个
 * "刚好塞进固定框"的系数（且只缩不放）：
 *
 *   scale = (1/density) × min(框宽/基础宽, 框高/基础高, 1)
 *
 * 少乘 density 这一步就会出现"300dp 塞进 124dp 的框"——直接被裁。
 */
@Composable
fun ConfigPreview(
    config: KeyStrokesConfig,
    widthDp: Float,
    heightDp: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val pxToDp = KeyLayout.pxToDpFactor(density)

    /*
     * 预览用的 CPS 占位值。
     *
     * 必须给：CPS 会影响布局（模式 2 多一行、模式 3 键变高），
     * 不给的话预览画不出那些结构，用户就看不到自己选的模式长什么样。
     */
    val previewCps = remember(config.id) {
        mapOf(
            KeyLayout.Id.LMB to 0,
            KeyLayout.Id.RMB to 0,
        )
    }

    Box(
        modifier = modifier
            .width(widthDp.dp)
            .height(heightDp.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        /*
         * Live2D 样式：显示模型的预览图（`cover.png`，没有就用 `icon.png`）。
         *
         * 这里**不起第二个 WebView**：它真正的画面是 WebGL，为了一个一百多 dp
         * 的小框再养一个 WebView 实例（内存 + 完整的模型解析）完全不值。
         * 预览图已经足够回答"我选的是哪个模型"，而且和模型列表里看到的是同一张。
         */
        if (config.styleId == StyleId.KEYBOARD_CAT) {
            Live2DModelThumbnail(
                modelId = config.live2d.modelId,
                widthDp = widthDp - PREVIEW_PADDING_DP * 2,
                heightDp = heightDp - PREVIEW_PADDING_DP * 2,
                modifier = Modifier.padding(PREVIEW_PADDING_DP.dp),
                // 外框已经有底色与边框了，再套一层会变成"框中框"
                drawBackground = false,
            )
            return@Box
        }

        // 换算成 dp 后的大小，用来算"要塞进框里需要缩多少"。
        // 高度按内容算（与悬浮窗同一套逻辑），否则开启 Shift + CPS 行时
        // 预览会比实际窗口矮，比例就失真了
        val baseWidthDp: Float
        val baseHeightDp: Float
        val customBounds = if (config.styleId == StyleId.CUSTOM_KEY) {
            CustomLayout.bounds(config.custom.components)
        } else {
            null
        }

        if (customBounds != null) {
            /*
             * 自定义 Key：尺寸 = 组件包围盒（与样式注册表、悬浮窗**同一套算法**）。
             *
             * ⚠️ 这里曾经用的是 `canvasWidth`（固定 600×600 的定位区）。
             * 那会让缩略图按 600×600 去算缩放，而内容只占其中一角 ——
             * 于是预览里的小图缩得极小、还偏在一角。
             *
             * 预览要回答的是"这个配置长什么样"，那就该只框住内容。
             */
            baseWidthDp = customBounds.width * pxToDp
            baseHeightDp = customBounds.height * pxToDp
        } else {
            baseWidthDp = KeyLayout.baseWidth(config) * pxToDp
            baseHeightDp = KeyLayout.baseHeight(config, previewCps) * pxToDp
        }

        val fit = minOf(
            if (baseWidthDp > 0f) widthDp / baseWidthDp else 1f,
            if (baseHeightDp > 0f) heightDp / baseHeightDp else 1f,
            1f,
        )

        if (customBounds != null) {
            /*
             * ⚠️ 这里**不要**去掉 fit：自定义布局可以摆得很大，
             * 不留缩放系数就会溢出预览框被裁掉一角，用户看到的是"我的布局缺了一块"。
             *
             * 内容用 toWindow 搬进窗口坐标（与悬浮窗同一套，含方向）——
             * 手写偏移极容易写反，见 CustomLayout.toWindow 的说明。
             */
            val windowed = CustomLayout.toWindow(config.custom.components, customBounds)

            CustomKeyCanvas(
                settings = config.custom.copy(components = windowed.components),
                pressedCodes = emptySet(),
                scale = pxToDp * fit,
                cpsBySlot = previewCps,
                slotIdOf = { code -> KeyLayout.codeToSlotMap(config)[code] },
                baseWidth = windowed.bounds.width,
                baseHeight = windowed.bounds.height,
                /* 预览要跟着显示整体透明度 —— 否则预览与实际不一致 */
                overallAlpha = config.customOpacityPercent.coerceIn(0, 100) / 100f,
                /*
                 * 与悬浮窗一样自适应：按预览框实际给的空间反推缩放，
                 * 不会因为多层尺寸推算的误差而溢出。
                 *
                 * ⚠️ 必须传 `maxFitScale = fit`：预览有一条
                 * **"只缩小、不放大"** 的规则（上面 `fit` 里的 `1f`）——
                 * 不留上限时内容会被撑满预览框，看起来就是"预览变大了"。
                 */
                fitToContainer = true,
                maxFitScale = fit,
            )
        } else if (KeyLayout.usesJoystickLayout(config)) {
            /*
             * 「手柄（标准）」：布局与键盘样式同源，只是 WASD 那块是摇杆。
             *
             * ⚠️ **必须在这里分发**，不能直接调 `KeyGrid` ——
             * 它只认"键帽"，会把摇杆槽位当成一个普通键去画
             * （一个空白 label 的键帽 = **一个黑色方块**，
             * 用户描述就是"摇杆的组件变成了一个黑色直角正方形"）。
             *
             * 预览里摇杆画在**中位**（`StickState()` 默认全 0）——
             * 那是它静止时的样子，也是用户最该看到的样子。
             */
            Gamepad2Content(
                config = config,
                sticks = StickState(),
                pressedCodes = emptySet(),
                scale = pxToDp * fit,
                cpsBySlot = previewCps,
            )
        } else {
            KeyGrid(
                config = config,
                pressedCodes = emptySet(),
                scale = pxToDp * fit,
                cpsBySlot = previewCps,
            )
        }
    }
}
