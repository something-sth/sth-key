package com.something.sthkey.ui.component

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 悬浮底栏压在屏幕底部的高度。
 *
 * ============================================================
 * ⚠️⚠️ 为什么要这个 —— 两个互相矛盾的需求
 * ============================================================
 * | 需求 | 冲突点 |
 * |---|---|
 * | 悬浮底栏要**真的浮在内容上** | 放进 `Scaffold.bottomBar` 槽位会给它**预留一条空白**，看起来像"底栏被安在一块背景上" |
 * | 内容不能钻到胶囊下面 | 不预留空间的话，`FloatingActionButton`（配置页的「新建」）会被胶囊挡住 |
 *
 * ⚠️ 解法:底栏**不进槽位**（真的浮着），但把它的高度通过这个
 * CompositionLocal 传给页面 —— **需要避让的东西自己加内边距**:
 *
 * | 谁 | 怎么用 |
 * |---|---|
 * | 可滚动列表（`ScrollableScreen` / `HomeScreen`） | 加到 `contentPadding.bottom`，最后一项才滚得到胶囊上面 |
 * | 悬浮按钮（`ScreenScaffold` 的 `floatingActionButton`） | 加到按钮自己的 `padding(bottom = ...)`，按钮才不被压住 |
 *
 * ⚠️ 只给「需要避让的元素」加，**不要**给整个 `NavHost` 加 ——
 * 那样内容又会在胶囊上方结束，回到"一条空白"的老问题。
 */
val LocalBottomBarInset = compositionLocalOf<Dp> { 0.dp }