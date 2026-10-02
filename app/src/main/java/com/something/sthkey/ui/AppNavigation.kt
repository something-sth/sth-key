package com.something.sthkey.ui

/**
 * 应用的两个阶段。
 *
 * 引导阶段与主界面分开管理，而不是"主页上盖一层遮罩"：
 * 引导页需要整屏展示权限说明，且完成后不应留在返回栈里（否则返回键会回到引导页）。
 */
enum class AppStage {
    SETUP,
    MAIN,
}

/** 主界面底部导航的三个一级页面 */
enum class TopDestination(
    val route: String,
    val label: String,
) {
    HOME("home", "主页"),
    CONFIG("config", "配置"),
    SETTINGS("settings", "设置"),
    ;

    companion object {
        val ROUTES: Set<String> = entries.map { it.route }.toSet()

        fun of(route: String?): TopDestination? = entries.firstOrNull { it.route == route }
    }
}

/** 二级页面路由（不显示底栏） */
object SubRoutes {
    const val DEBUG = "debug"
    const val ABOUT = "about"

    /** 配置编辑页；路径形如 config_editor/{configId} */
    const val CONFIG_EDITOR = "config_editor"

    /**
     * 自定义 Key 的图形化编辑器；路径形如 custom_editor/{configId}。
     *
     * 做成**独立的二级页面**而不是塞进配置编辑页：
     * 它有工具栏、画布、属性面板三块，塞进那个滚动页里既挤，
     * 画布的拖动手势也会和页面滚动打架。
     */
    const val CUSTOM_EDITOR = "custom_editor"

    const val ARG_CONFIG_ID = "configId"

    fun configEditor(configId: String): String = "$CONFIG_EDITOR/$configId"

    fun customEditor(configId: String): String = "$CUSTOM_EDITOR/$configId"
}

/**
 * 配置编辑的生效方式。
 *
 * - [REALTIME] 改动即时写入，适合边看预览边微调；
 * - [ON_SAVE]  改动只留在编辑页的草稿里，点"保存"才写入，
 *   适合一次改很多项、不满意就整体放弃的场景（旧版就是这种逻辑）。
 */
enum class EditMode(val id: String, val label: String, val description: String) {
    REALTIME(
        id = "realtime",
        label = "实时生效",
        description = "修改立即写入配置，改完可以直接退出",
    ),
    ON_SAVE(
        id = "on_save",
        label = "保存生效",
        description = "编辑页底部会出现保存按钮；不保存直接退出则放弃本次修改",
    ),
    ;

    companion object {
        val DEFAULT: EditMode = REALTIME

        fun fromId(id: String?): EditMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * 自定义 Key 编辑器里**属性面板停在哪一边**。
 *
 * ============================================================
 * 为什么需要这个
 * ============================================================
 * 属性面板是按"手机竖屏、面板在下方"设计的：一列滑块，宽度吃满、
 * 高度占大半屏。换到平板上就完全不对了 —— 横屏时上下空间很矮，
 * 面板被压成一两条缝，而左右两侧大片空白没人用。
 *
 * 所以位置交给用户选。手机竖屏用 [BOTTOM] 就行，
 * 平板横屏用 [LEFT] 能一次看到更多设置项。
 *
 * ============================================================
 * ⚠️ 刻意**没有"上"**
 * ============================================================
 * 面板在上方时，画布就跑到下面去了。而软键盘是从**底部**弹起来的：
 * 改文字时画布会被顶掉一半，而被编辑的组件恰好在画布里 ——
 * 边打字边看不到效果。下/左/右三种都与键盘错开。
 *
 * ============================================================
 * ⚠️ 也**没有"自动"这个可选项**（这里换过一次做法）
 * ============================================================
 * 早先 [AUTO] 是第四个按钮，与三个位置并列。但"自动"不是一个**位置**，
 * 而是"位置从哪来" —— 混在一起之后，选了自动的用户还是不知道
 * 面板现在在哪一边，而这两者在屏幕上差别很大。
 *
 * 现在改成：[AUTO] 只作为**初始值**存在（首次打开编辑器时解析一次），
 * 界面上的三个按钮由 [selectable] 给出，自动选出来的那个带 `-自动` 后缀
 * 显示（例如 `左-自动`）。于是"在哪一边"和"是不是自动定的"一眼都看得到。
 *
 * 用户一点按钮就变成明确的位置，并被记住 ——
 * 否则他手动切到下方、下次打开又被自动改回左边，看起来就像设置没记住。
 */
enum class EditorPanelSide(val id: String, val label: String) {
    /**
     * 初始值：按设备形态自动解析（平板→左、手机→下）。
     *
     * **只在用户还没选过时出现**，解析完就换成具体的位置写回偏好。
     */
    AUTO("auto", "自动"),

    /** 下方（手机竖屏的默认） */
    BOTTOM("bottom", "下"),

    /** 左侧（平板横屏推荐） */
    LEFT("left", "左"),

    /** 右侧 */
    RIGHT("right", "右"),
    ;

    /**
     * 把 [AUTO] 解析成一个确定的位置。
     *
     * @param isTablet 屏幕最短边 ≥ 600dp。用最短边而不是当前宽高：
     *   横竖屏切换时它不变，所以"这是不是平板"不会因为转屏而变。
     */
    fun resolved(isTablet: Boolean): EditorPanelSide = when (this) {
        AUTO -> if (isTablet) LEFT else BOTTOM
        else -> this
    }

    companion object {
        /** 首次打开时用的值 */
        val DEFAULT: EditorPanelSide = AUTO

        /**
         * 界面上可以点的三个位置。
         *
         * [AUTO] 不在里面：它是"从哪来"而不是"在哪"，
         * 作为按钮与其它三个不是一个维度（见类说明）。
         */
        val selectable: List<EditorPanelSide> = entries.filter { it != AUTO }

        fun fromId(id: String?): EditorPanelSide = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** 外部链接与常量 */
object AppLinks {
    /*
     * ⚠️ 这是**本项目的**仓库地址，不要写回旧项目（Sth-Android-KeyStrokes）。
     *
     * 从旧项目搬运"关于"页时这两个常量被一起带了过来，于是应用里
     * 「GitHub 开源仓库」与「MIT 许可证」都指向旧项目 —— 用户点进去
     * 看到的是一份**不相关的代码**，而这类链接错了很难被自己发现
     * （不报错、不崩溃，只是悄悄指错地方）。
     */
    const val GITHUB_REPO = "https://github.com/something-sth/sth-key"

    /*
     * ⚠️ 用 `/blob/HEAD/` 而不是写死分支名。
     *
     * 仓库的默认分支是 `master`，而新建仓库的惯例是 `main` ——
     * 写死任何一个，将来改了默认分支（或别人 fork 后用了别的名字）
     * 这个链接就指错地方，而它**不会报错、只是打开一个 404 页面**，
     * 很难被发现。
     *
     * `HEAD` 是 GitHub 支持的写法，永远指向**当前默认分支**。
     */
    const val LICENSE = "https://github.com/something-sth/sth-key/blob/HEAD/LICENSE"

    /** QQ 群分享链接（沿用旧项目的群） */
    const val QQ_GROUP =
        "https://qun.qq.com/universal-share/share?ac=1&authKey=TAcvzxnpxvtKzwgk%2Ba%2Br7WtZ5Mj63H3jNtzCLY9oy352oBj2mu5EFu2UYrGG2MbR&busi_data=eyJncm91cENvZGUiOiI5MDg4ODc0NzQiLCJ0b2tlbiI6IlVXOWloN3l2eGpQVksrTSsyMnZiWi84MXFsN2xhMXVxVUZ4K0xLd3hnRU5yanRpd29rMzB6MmtIeER2L1lwZk4iLCJ1aW4iOiIyNzUxODA5MjM3In0%3D&data=Xt1S3wTDGgqTCNJq8LaH9gg5UE1zg87Uw3a0VawgciuMnwuReiG1Hx-z_UX7X9i2MFP4w7OyNlwf2rVKURr7Zw&svctype=4&tempid=h5_group_info"

    const val QQ_GROUP_NUMBER = "908887474"

    const val QQ_GROUP_PASSWORD = "sthkey"

    const val DEVELOPER = "something-sth"
}
