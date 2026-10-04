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
    /**
     * 内置按键显示样式。
     *
     * ⚠️ 它的**显示名**现在叫「键盘」,但这个 id **永远不要改** ——
     * 见本对象的注释。
     */
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

    /**
     * 手柄样式一：两个摇杆 + 方向键 + YXAB 圆形按键。
     *
     * ============================================================
     * ⚠️ 命名里的序号是**预设编号**，不是"第几代"
     * ============================================================
     * `gamepad1` / `gamepad2` 是两套**并列的预设**（布局不同，没有优劣）。
     * 以后再加预设就顺着排 `gamepad3`、`gamepad4` —— 这样一眼能看出
     * 它们是同一族，而不用去读注册表。
     *
     * ⚠️ 同样的**一旦发布就不能改**（会被写进用户配置里）。
     */
    const val GAMEPAD1 = "gamepad1"

    /**
     * 手柄样式二：两个**方形**摇杆 + 一条长条键 + LMB/RMB。
     *
     * 布局按手柄的"标准"思路摆（左摇杆当 WASD、右摇杆当视角，
     * 下面是空格与鼠标键）。
     */
    const val GAMEPAD2 = "gamepad2"
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
    /**
     * 新建配置页的分组（"键盘" / "手柄" / "通用"）。
     *
     * ⚠️ 用它分组而不是在 UI 里按 id 写 `when`：那样每加一个样式
     * 就要改一次 UI，而分组本来就是样式自己的属性。
     */
    val category: StyleCategory = StyleCategory.KEYBOARD,
)

/**
 * 新建配置页里样式的分组。
 *
 * 顺序即显示顺序 —— 用户最常用的（键盘）排在最前。
 */
enum class StyleCategory(val title: String) {
    KEYBOARD("键盘"),
    GAMEPAD("手柄"),
    GENERAL("通用"),
}

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
                /*
                 * ⚠️ 显示名从 "Key" 改成了 "键盘"，**id 一个字都没动**。
                 *
                 * 这正是本文件开头那条约束的用法:用户配置里存的是
                 * `StyleId.KEYSTROKES`（"keystrokes"），改 label 对
                 * 已有配置**零影响**。反过来改了 id 就会让所有老配置
                 * 落到"未知样式"的兜底分支上。
                 */
                label = "键盘",
                description = "按键显示：可自定义键位、颜色、透明度、圆角",
                enabled = true,
                iconKey = ICON_KEYBOARD,
                category = StyleCategory.KEYBOARD,
                baseSize = { config, cpsBySlot ->
                    OverlayBaseSize(
                        /*
                         * ⚠️ 按内容算，不是 `BASE_WIDTH` 常量 ——
                         * "按键间距"滑块**只改位置、不改键宽**，所以间距一变
                         * 内容宽度就跟着变（见 `KeyLayout.baseWidth`）。
                         *
                         * 用常量的话:间距调大 → 内容超出窗口**右边被裁**；
                         * 调小 → 右边**一块空气**。那两种都真的发生过。
                         */
                        width = KeyLayout.baseWidth(config),
                        // 按内容算：模式 2 多一行、模式 3 键更高，用固定值会裁掉底部
                        height = KeyLayout.baseHeight(config, cpsBySlot),
                    )
                },
            ),
        )

        /*
         * ============================================================
         * ⚠️ gamepad1 暂时**下线**
         * ============================================================
         * 用户的原话:"gamepad1 配置暂时先不考虑了，先注释掉吧回头有时间再做，
         * 我们现在主要做 gamepad2"。
         *
         * 它现在**只剩渲染代码**（`Gamepad1Content`），而 gamepad2 已经
         * 走"键盘布局 + 摇杆"那条路并且有了完整的专属设置 ——
         * 两个样式并存会让每次改摇杆都要改两处。
         *
         * ⚠️ 所以 `Gamepad1Content` / `GamepadLayout` 暂时**没有调用方**，
         * 但**不要删** —— 回头重新上线时直接取消注释即可。
         * （它引用的 `Joystick` 仍然在编译，不会因为没人用而失效。）
         *
         * ⚠️ 重新上线时记得:把 `Gamepad1Content` 里那两处
         * `JoystickStyle()` 改成读 `config.joystick`，
         * 否则 gamepad1 的摇杆会忽略用户的摇杆设置。
         *///         register(
//             OverlayStyleDescriptor(
//                 id = StyleId.GAMEPAD1,
//                 label = "手柄",
//                 description = "两个摇杆 + 方向键 + YXAB 圆形按键",
//                 enabled = true,
//                 iconKey = ICON_GAMEPAD,
//                 category = StyleCategory.GAMEPAD,
//                 /*
//                  * ⚠️ 尺寸**固定**，不随配置内容变。
//                  *
//                  * 与键盘样式不同:手柄样式的布局是定死的（两个摇杆 + 方向键
//                  * + 四个圆键），没有"开 CPS 多一行"这种事。
//                  * 所以不需要 `cpsBySlot` 参与计算。
//                  */
//                 baseSize = { _, _ ->
//                     OverlayBaseSize(GamepadLayout.BASE_WIDTH, GamepadHeights.ONE)
//                 },
//             ),
//         )

        register(
            OverlayStyleDescriptor(
                id = StyleId.GAMEPAD2,
                label = "标准",
                description = "键盘布局，WASD 那块换成左摇杆",
                enabled = true,
                iconKey = ICON_GAMEPAD,
                category = StyleCategory.GAMEPAD,
                /*
                 * ⚠️ 尺寸**与键盘样式同源**，因为布局本来就是同一个。
                 *
                 * `KeyLayout.keys()` 在 gamepad2 下只把 WASD 换成摇杆槽位，
                 * 所以宽度就是 `BASE_WIDTH`、高度就是 `baseHeight()`
                 * —— 与键盘样式调的是**同一个函数**。
                 *
                 * ⚠️ 这里踩过一个坑:改成键盘布局之后**忘了改这里的注册值**，
                 * 于是窗口还是旧的 `600 × 266`。后果是两处:
                 *
                 * - 宽度 600 而内容只占 252 → **右边一大块空白**
                 *   （用户描述:"右边有一大块空气，约占屏幕宽的三分之一"）；
                 * - 高度 266 而内容需要 320 → **底部被裁掉**
                 *   （用户描述:"A 键被裁剪了"）。
                 *
                 * 教训:样式的布局换了，**注册值必须跟着换**。
                 * 两者的唯一真源是 `KeyLayout`，不要各写一份。
                 */
                baseSize = { config, cpsBySlot ->
                    OverlayBaseSize(
                        width = KeyLayout.baseWidth(config),
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
                category = StyleCategory.GENERAL,
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
                category = StyleCategory.GENERAL,
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
    const val ICON_GAMEPAD = "gamepad"

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
            ?: OverlayBaseSize(KeyLayout.baseWidth(config), KeyLayout.baseHeight(config, cpsBySlot))
    }

    /** 当前默认样式 id */
    val defaultStyleId: String = StyleId.KEYSTROKES
}
