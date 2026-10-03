package com.something.sthkey.ui.overlay

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 给一层内容加**真·高斯模糊**（柔光阴影用）。
 *
 * ============================================================
 * 为什么需要它，而不是继续用"多层偏移副本"
 * ============================================================
 * 图片字体的柔光最早是用"画 8 个偏移副本、每层降透明度"近似的。
 * 但**这个近似在这个场景下上限不高**：
 *
 * 柔光那一层画完之后，还要用 `DstOut` 把**字形本身**擦掉，
 * 剩下的只有"字形外面那一圈薄薄的晕"。而图片字体是**像素块**——
 * 块状字形外面一圈薄晕，看起来和"实心副本挪一点"（硬阴影）几乎一样。
 *
 * TTF 那边之所以好看，是因为 `TextStyle.Shadow` 走的是系统的**真模糊**。
 * 这里就补上同一件事。
 *
 * ============================================================
 * ⚠️ API 30 上会退化成"没有模糊"
 * ============================================================
 * `Modifier.blur` 底层是 `RenderEffect`，**API 31 才有**。
 * 在 30 上它会被静默忽略 —— 于是柔光看起来"完全没效果"。
 *
 * 所以调用方（见 `KeyGrid`）在 API 30 上仍然走多层副本那条路，
 * 这里只负责"能用真模糊时用真模糊"。
 * 判断放在调用方而不是这里，是因为调用方还要相应地**不要**再叠多层副本
 * （两套同时上会糊成一团）。
 *
 * @param radius 模糊半径（**Dp** —— `Modifier.blur` 的入参就是 Dp）
 */
fun Modifier.realBlur(radius: Dp): Modifier =
    if (radius.value <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        this
    } else {
        /*
         * ⚠️ `Unbounded` 是必须的：默认的 `BlurredEdgeTreatment.Rectangle`
         * 会把模糊**裁在组件边界内** —— 光晕只出现在内容里面，
         * 外面一圈全被切掉，看起来就是"模糊没生效"。
         *
         * 用 `Unbounded` 之后模糊可以溢出到组件外，而调用方已经在
         * 外层留了 `contentPadding`（= 阴影大小 × 2）的出血，
         * 所以不会被父容器裁掉。
         */
        blur(radius, edgeTreatment = BlurredEdgeTreatment.Unbounded)
    }

/** 这台设备能不能用真模糊（见上面的说明） */
val canUseRealBlur: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
