package com.something.sthkey.capture

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * 悬浮窗的**触摸接管容器**。
 *
 * ============================================================
 * ⚠️⚠️ 为什么需要它
 * ============================================================
 * 悬浮窗的拖动是挂在"根视图"上的 `setOnTouchListener`
 * （见 [OverlayService.setupDragListener]）。而 `OnTouchListener` 只有在
 * **没有子 View 消费这个手势**时才会被调用 —— 这是 Android 的分发顺序决定的。
 *
 * 于是历史上反复出现同一个症状:**某一块区域能看见内容、但拖不动窗口**:
 *
 * | 当时那块内容是什么 | 谁吃掉了 DOWN |
 * |---|---|
 * | Live2D 的 WebView | WebView（后来靠它自己的 `onInterceptTouchEvent` 抢回来） |
 * | 图片/动图的 `AndroidView`（内部 ImageView） | 那个原生子 View |
 *
 * ⚠️ 我试过 `pointerInteropFilter { false }`、也试过把 GIF 改成纯 Compose 绘制，
 * 都没能稳定解决 —— 因为**任何**会接手势的子 View（包括以后的视频 `TextureView`）
 * 都会让这个方案失效。指望"事件能漏到根视图"本身就是脆的。
 *
 * ============================================================
 * 做法:容器层**无条件接管**
 * ============================================================
 * `onInterceptTouchEvent` 直接返回 true:子 View 只会收到一个 CANCEL，
 * 之后所有事件都归这个容器，拖动在任何区域都生效。
 *
 * ⚠️ 前提是"悬浮窗里没有需要点击的东西" —— 这在本项目成立:
 * 可触摸悬浮窗的唯一交互就是**拖动**；触点交互（触屏样式）走的是采集层，
 * 不经过这些视图。
 */
internal class DragTouchHost(context: Context) : FrameLayout(context) {

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true
}