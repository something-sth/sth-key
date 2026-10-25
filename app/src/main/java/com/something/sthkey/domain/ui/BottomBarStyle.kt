package com.something.sthkey.domain.ui

/**
 * 底栏样式。
 *
 * ============================================================
 * ⚠️ 两种样式**都是 Material3 实现的**，没有第三方依赖
 * ============================================================
 * | 样式 | 实现 | 观感 |
 * |---|---|---|
 * | [STANDARD] | Material3 `NavigationBar` | 贴底整条，图标在上文字在下 |
 * | [FLOATING] | 自己拼的 `Surface(CircleShape)` + `Row` | 浮起来的胶囊，**选中项展开成文字** |
 *
 * ⚠️ [FLOATING] 是照 SukiSU 的 `BottomBarMaterial` 做的（它的 Material 底栏
 * 本来就是悬浮胶囊）。组件在 `ui/component/MaterialFloatingBottomBar.kt`。
 *
 * ⚠️ 刻意**没有"液态玻璃"那一项**:
 * 那需要 `miuix-blur` 的 `Backdrop` + `highlight`（只有 0.9.1+ 才有），
 * 而 0.9.1 声明 `minSdk 32`、0.9.4 要 Kotlin 2.4（本项目升不动）。
 * 为了它把 minSdk 从 28 抬上去不划算，所以这一项**不提供** ——
 * 留一个选了不生效的选项比不提供更糟。
 */
enum class BottomBarStyle(
    val id: String,
    val label: String,
    val description: String,
) {
    /** 贴底的整条底栏 */
    STANDARD(
        id = "standard",
        label = "标准",
        description = "贴着屏幕底边的整条底栏，图标在上、文字在下",
    ),

    /** 悬浮胶囊底栏 */
    FLOATING(
        id = "floating",
        label = "悬浮",
        description = "浮在内容上的胶囊底栏，选中项展开成文字",
    ),
    ;

    companion object {
        val DEFAULT: BottomBarStyle = STANDARD

        /**
         * 从偏好里的字符串 id 还原。
         *
         * ⚠️ 认不出的 id（降级安装、或者存的是旧版本的 id）**安静地回落到默认值**，
         * 不能抛异常 —— 那会让应用一开就崩，用户连设置页都进不去。
         */
        fun fromId(id: String?): BottomBarStyle =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
