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

/**
 * 当前是不是「悬浮底栏压在最上面」的模式。
 *
 * ============================================================
 * 为什么需要它：页面要**画到系统导航栏下面**
 * ============================================================
 * 悬浮胶囊要看起来"浮在内容上"，它下面那块（系统导航栏区域）就**不能是一条纯色带**。
 * 但默认的 `Scaffold` 会给内容留出导航栏内边距 —— 于是列表在导航栏上方就截止了，
 * 底下留一大块页面底色，看起来就是"一条背景色"。
 *
 * ⚠️ 所以悬浮模式下：外层和页面里的 `Scaffold` 都要**去掉底部内边距**，
 * 让内容真的铺到屏幕最底；避让由 [LocalBottomBarInset]（含导航栏高度）负责，
 * 列表最后一项仍然不会被胶囊或手势条挡住。
 *
 * ⚠️ 只在"悬浮 + 一级页面"时为 true（二级页没有底栏，不需要）。
 */
val LocalBottomBarOverlay = compositionLocalOf { false }