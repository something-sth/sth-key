package com.something.sthkey.domain.style

import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.custom.CustomLayout

/**
 * 悬浮窗样式标识。
 *
 * 约束：**一旦发布就不能改名字符串**，因为它会被写进用户的配置数据里。
 * 需要改显示名称时改 [OverlayStyleDescriptor.label]，不要动 [id]。
 */
object StyleId {
    /** 内置按键显示样式 */
    const val KEYSTROKES = "keystrokes"

    /**
     * 键盘猫 / Live2D 模型。
     *
     * 用 WebView 渲染 assets/bongocat 里的模型（设计文档见仓库根目录的 docs/live2d.md）。
     * 本版**只内置两套模型**，不支持用户导入。
     */
    const val KEYBOARD_CAT = "keyboard_cat"

    /**
     * 自定义 Key：画布上的按键与文本组件全部由用户摆放。
     *
     * 命名沿用 snake_case（与上面两个一致）。**不要**写成 `custom key` ——
     * 带空格的值在 JSON / 命令里都更容易出问题，而它是会写进用户配置的持久标识。
     */
    const val CUSTOM_KEY = "custom_key"
}

/**
 * 样式内容在基础坐标系里的尺寸（单位像素）。
 *
 * 与旧项目一致：所有坐标按"固定坐标系 + 整体缩放"理解，
 * 缩放由 [KeyLayout.uiScale] 统一处理，不改这里的常量。
 */
data class OverlayBaseSize(
    val width: Float,
    val height: Float,
)

/**
 * 样式描述。
 *
 * @param id          持久化标识，不可变
 * @param label       显示名称
 * @param description 一句话说明（配置列表的副标题、新建配置弹窗里都会用到）
 * @param enabled     是否已实现；false 时在 UI 上置灰不可选
 * @param iconKey     UI 图标标识。样式层不认识 Compose 的图标类型，
 *   用一个字符串让 UI 层自己映射，避免 domain 依赖 ui。
 * @param baseSize    该样式的**内容尺寸**，由样式自己提供。
 *
 * 为什么尺寸要放在这里，而不是让窗口去猜：按键显示的高度**会随内容变化**
 * （开启 Shift、CPS 模式 2/3 都会变高），Live2D 则是固定设计分辨率。
 * 窗口尺寸、Compose 根布局、配置预览三处都取这里，口径才可能一致 ——
 * 之前只有按键一种样式时，这三处各自去问 [KeyLayout]，加了第二种样式立刻就会分叉。
 */
data class OverlayStyleDescriptor(
    val id: String,
    val label: String,
    val description: String,
    val enabled: Boolean,
    val iconKey: String,
    val baseSize: (KeyStrokesConfig, Map<String, Int>) -> OverlayBaseSize,
)

/**
 * Live2D 的设计分辨率（像素）。
 *
 * ⚠️ **必须与 assets/bongocat 里 runtime.js 的 `DESIGN_WIDTH` / `DESIGN_HEIGHT` 一致**。
 * 不一致不会崩：JS 会把舞台等比缩放并居中（letterbox），
 * 表现为模型周围多出一圈空白 —— 很难看出是配置错了，所以特意写在这里备查。
 */
private const val LIVE2D_DESIGN_WIDTH = 612f
private const val LIVE2D_DESIGN_HEIGHT = 354f

/**
 * 样式注册表。
 *
 * 用途：配置页的样式选择、悬浮窗创建时的分发，都只依赖这里，
 * 不再出现 `if (style == "x") ... else ...` 的散落分支。
 *
 * 新增一个样式的完整动作：
 * 1. 在 [StyleId] 加一个不可变的 id；
 * 2. 在这里 [register] 一个描述（含 [OverlayStyleDescriptor.baseSize]）；
 * 3. 在 `ui/overlay/OverlayContent.kt` 的渲染分发里加一个分支
 *    —— UI 层必须知道这个样式怎么画，这一步无法避免，全项目**只有那一处**；
 * 4. 如果它有自己的设置项，在配置编辑页里补上。
 */
object OverlayStyleRegistry {

    private val descriptors = linkedMapOf<String, OverlayStyleDescriptor>()

    init {
        register(
            OverlayStyleDescriptor(
                id = StyleId.KEYSTROKES,
                label = "Key",
                description = "按键显示：可自定义键位、颜色、透明度、圆角",
                enabled = true,
                iconKey = ICON_KEYBOARD,
                baseSize = { config, cpsBySlot ->
                    OverlayBaseSize(
                        width = KeyLayout.BASE_WIDTH,
                        // 按内容算：模式 2 多一行、模式 3 键更高，用固定值会裁掉底部
                        height = KeyLayout.baseHeight(config, cpsBySlot),
                    )
                },
            ),
        )

        register(
            OverlayStyleDescriptor(
                id = StyleId.KEYBOARD_CAT,
                label = "LIVE 2D",
                description = "键盘猫 / Live2D 模型，跟随按键与鼠标动作",
                enabled = true,
                iconKey = ICON_LIVE2D,
                // Live2D 的尺寸是固定的设计分辨率，与配置内容无关
                baseSize = { _, _ ->
                    OverlayBaseSize(LIVE2D_DESIGN_WIDTH, LIVE2D_DESIGN_HEIGHT)
                },
            ),
        )

        register(
            OverlayStyleDescriptor(
                id = StyleId.CUSTOM_KEY,
                label = "自定义 Key",
                description = "自己摆放按键与文本组件：位置、大小、颜色、对齐全部可调",
                enabled = true,
                iconKey = ICON_CUSTOM,
                /*
                 * 尺寸 = 所有组件边框的**最小外接矩形**（见 CustomLayout.bounds）。
                 *
                 * 所以"摆多少内容就占多大地方"，不再是固定 600×600。
                 * 窗口内容由 CustomKeyCanvas 按同一个包围盒的左上角平移对齐 ——
                 * 两处必须用**同一个** bounds，否则内容会偏。
                 *
                 * cpsBySlot **不参与计算**：自定义布局里没有"按 CPS 行数长高"这种事，
                 * 文本组件的位置是用户定死的，不会因为有没有点击而变。
                 */
                baseSize = { config, _ ->
                    val bounds = CustomLayout.bounds(config.custom.components)
                    OverlayBaseSize(width = bounds.width, height = bounds.height)
                },
            ),
        )
    }

    /** UI 图标标识 */
    const val ICON_KEYBOARD = "keyboard"
    const val ICON_LIVE2D = "live2d"
    const val ICON_CUSTOM = "custom"

    fun register(descriptor: OverlayStyleDescriptor) {
        descriptors[descriptor.id] = descriptor
    }

    fun all(): List<OverlayStyleDescriptor> = descriptors.values.toList()

    /** 已实现的样式，只从这些里面创建配置 */
    fun available(): List<OverlayStyleDescriptor> = descriptors.values.filter { it.enabled }

    /** 未实现的样式，UI 上置灰展示（让用户知道后面会有什么） */
    fun unavailable(): List<OverlayStyleDescriptor> = descriptors.values.filter { !it.enabled }

    fun find(id: String): OverlayStyleDescriptor? = descriptors[id]

    /** 未知或未实现的样式一律回落到默认样式，避免配置损坏导致界面空白 */
    fun resolveOrDefault(id: String): OverlayStyleDescriptor =
        descriptors[id]?.takeIf { it.enabled }
            ?: descriptors[StyleId.KEYSTROKES]
            ?: error("默认样式未注册")

    /**
     * 解析某个配置的内容尺寸。
     *
     * 这是"窗口该多大"的**唯一入口**：悬浮窗尺寸、Compose 根布局、配置预览都走它，
     * 不允许任何一处再去直接问 [KeyLayout] —— 否则加了样式之后口径必然分叉。
     */
    fun baseSizeOf(
        config: KeyStrokesConfig,
        cpsBySlot: Map<String, Int> = emptyMap(),
    ): OverlayBaseSize {
        val descriptor = descriptors[config.styleId] ?: descriptors[StyleId.KEYSTROKES]
        return descriptor?.baseSize?.invoke(config, cpsBySlot)
            // 兜底：注册表被清空这种不可能的情况，也要给出一个能画的尺寸
            ?: OverlayBaseSize(KeyLayout.BASE_WIDTH, KeyLayout.baseHeight(config, cpsBySlot))
    }

    /** 当前默认样式 id */
    val defaultStyleId: String = StyleId.KEYSTROKES
}
