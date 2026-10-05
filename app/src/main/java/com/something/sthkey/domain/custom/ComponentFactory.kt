package com.something.sthkey.domain.custom

import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.keys.KeyCodes

/**
 * 组件的**创建**：新建一个、以及复制一个。
 *
 * ============================================================
 * 为什么这部分在 domain 而不是编辑器里
 * ============================================================
 * 它不碰 Compose、不碰 Android，全是数据规则：id 怎么保证不重、
 * 副本往哪偏、新建的默认值是什么。放在 UI 文件里就没法单元测试，
 * 而"复制出来的东西和原件联动""复制了却看不见"这类问题
 * 恰恰是**必须**有测试钉住的 —— 它们在真机上看起来都像灵异事件。
 */

/**
 * 生成一个**不会与现有组件冲突**的 id。
 *
 * ============================================================
 * ⚠️ 时间戳不够，必须查一遍重名
 * ============================================================
 * 时间戳精确到毫秒，正常点击不可能撞。但"复制"是个**点一下就有结果**的操作，
 * 连点两下、或者以后加了"批量复制"，同一毫秒内生成两次是完全可能的 ——
 * 撞了 id 的后果很严重：[CustomLayoutDraft.replace] 是按 id 找组件的，
 * 两个组件同 id 会变成"改一个另一个也跟着变"，而且极难联想到原因。
 *
 * 所以这里拿现有 id 集合去重，撞了就往后顺延。多花一次遍历，换掉一整类怪问题。
 *
 * @param type 决定前缀（`key_` / `text_`），便于在日志与调试面板里认出来
 */
fun newComponentId(type: ComponentType, existing: Collection<String>): String {
    val used = existing.toHashSet()
    val stamp = System.currentTimeMillis().toString(36)
    var candidate = "${type.id}_$stamp"
    var suffix = 1
    while (candidate in used) {
        candidate = "${type.id}_${stamp}_$suffix"
        suffix++
    }
    return candidate
}

/**
 * 新建一个组件。
 *
 * ============================================================
 * 默认值都要"能立刻用"
 * ============================================================
 * - 按键组件：绑 [SUGGESTED_KEY_CODES] 里**还没被用过**的第一个键，
 *   避免新建两个组件都绑同一个键、一按下去一起亮（用户会以为坏了）；
 * - 文本组件：内容给"水印"，**不带 `(cps)` 占位符** ——
 *   不带占位符就不显示 CPS，而"新建一个文本"通常是想写点别的字。
 *   想显示 CPS 自己把占位符写进去，这也是这个功能的用法本身。
 * - 位置：画布中央偏上，再按已有数量错开一点，避免完全重叠看不出加了东西。
 * - 外观：沿用这份配置已有的配色（[CustomLayout.styleFromConfigColors]），
 *   不必从"纯黑键帽"开始一项项调。
 */
fun createComponent(
    type: ComponentType,
    settings: CustomLayoutSettings,
    config: KeyStrokesConfig,
): CustomComponent {
    val id = newComponentId(type, settings.components.map { it.id })

    /*
     * 落点：画布中央偏上，按已有数量错开，再夹进范围。
     *
     * ⚠️ 夹的是 **[0, BASE_CANVAS]**，**不是** [CustomLayout.maxCoordinate]。
     *
     * 两者这一版分开了:坐标上界是 1000（用户可以把组件摆到定位区之外），
     * 而"新组件落在哪"应当始终在**看得见的定位区里** —— 用坐标上界的话，
     * 组件一多（每多一个右移 20），新组件会跑到 600~1000 那段，
     * 也就是加出来的组件**一开始就在定位区外面**，用户会以为没加上。
     */
    val offset = (settings.components.size * 20).toFloat()
    val baseX = (CustomLayout.BASE_CANVAS / 2f - 60f + offset)
        .coerceIn(0f, CustomLayout.BASE_CANVAS)
    val baseY = (140f + offset)
        .coerceIn(0f, CustomLayout.BASE_CANVAS)

    return when (type) {
        ComponentType.KEY -> {
            val used = settings.components
                .filterIsInstance<KeyComponent>()
                .flatMap { it.inputKeyCodes }
                .toSet()
            val code = SUGGESTED_KEY_CODES.firstOrNull { it !in used }
                ?: SUGGESTED_KEY_CODES.first()

            KeyComponent(
                id = id,
                x = baseX,
                y = baseY,
                width = CustomLayout.NEW_KEY_SIZE,
                height = CustomLayout.NEW_KEY_SIZE,
                style = CustomLayout.styleFromConfigColors(config.colors, config.fontId),
                label = KeyCodes.displayName(code),
                inputKeyCodes = listOf(code),
            )
        }

        ComponentType.TEXT -> TextComponent(
            id = id,
            x = baseX,
            y = baseY,
            width = CustomLayout.NEW_TEXT_WIDTH,
            height = CustomLayout.NEW_TEXT_HEIGHT,
            style = CustomLayout.styleFromConfigColors(config.colors, config.fontId),
            text = "水印",
        )

        ComponentType.JOYSTICK -> JoystickComponent(
            id = id,
            x = baseX,
            y = baseY,
            /*
             * ⚠️ 摇杆默认做成**正方形**，而且边长与按键组件一致（[CustomLayout.NEW_KEY_SIZE]）。
             *
             * 摇杆的三层几何（底盘圆角、内圆半径、帽能走多远）都是按
             * "边长"推的，宽高不等会让内圆变成椭圆 —— 那是渲染层没打算支持的形状。
             */
            width = CustomLayout.NEW_KEY_SIZE,
            height = CustomLayout.NEW_KEY_SIZE,
            side = StickSide.LEFT,
            /*
             * 外观用「标准」样式那份配置里的摇杆设置 ——
             * 用户的原话是"配置项要与'标准'样式相同"，那么**默认值也该一致**，
             * 否则同一个摇杆在两个样式下开箱长得不一样。
             */
            joystick = config.joystick,
        )
    }
}

/** 复制时副本相对原件的偏移（基础单位）：看得出"旁边多了一个"就行，不要偏太多 */
const val DUPLICATE_OFFSET = 24f

/**
 * 复制一个组件。
 *
 * ============================================================
 * 为什么不能直接把原件放进列表
 * ============================================================
 * 两个原因，第二个更致命：
 *
 * 1. **画面上会完全重叠** —— 副本与原件逐像素重合，用户点了"复制"却
 *    什么都没看见，会以为按钮坏了。所以要偏一点。
 *
 * 2. ⚠️ **id 必须换掉**。[CustomLayoutDraft.replace] 是按 **id** 找组件替换的，
 *    两个组件同 id 会让"改一个另一个也跟着变"—— 这是最难看懂的一类 bug
 *    （用户会以为"复制出来的东西是联动的"，其实是数据坏了）。
 *    见 [newComponentId]。
 *
 * ============================================================
 * 副本与原件**完全一样**（文字、绑定的键、全部外观）
 * ============================================================
 * 不做"自动加个 2"之类的改名：那是编辑器的自作聪明 ——
 * 用户想在副本上写什么，他自己会改；而自动加的后缀他首先要删掉。
 *
 * @return 可以直接 `draft.add()` 的新组件；**一定在画布范围内**
 */
fun duplicateComponent(
    source: CustomComponent,
    settings: CustomLayoutSettings,
    /** 偏移步长（基础单位） */
    step: Float = DUPLICATE_OFFSET,
): CustomComponent {
    val id = newComponentId(typeOf(source), settings.components.map { it.id })

    val offset = source
        .withNewId(id)
        .movedTo(x = source.x + step, y = source.y + step)
        .clampedToCanvas()

    /*
     * ⚠️ 贴住右下角时 `+step` 会被夹回原位，副本就正好压在原件上、还是看不见。
     * 所以"位置一点没变"就意味着被夹住了，这时改往左上偏。
     *
     * 用"位置没变"而不是"原件太靠边"来判断：后者要自己重算一遍边界，
     * 而边界规则（下界依赖尺寸）已经在 clampedToCanvas 里了 ——
     * 在这里再算一遍就是第二个真源。
     */
    if (offset.x == source.x && offset.y == source.y) {
        return source
            .withNewId(id)
            .movedTo(x = source.x - step, y = source.y - step)
            .clampedToCanvas()
    }
    return offset
}

/** 组件的类型（用于生成同前缀的新 id） */
fun typeOf(component: CustomComponent): ComponentType = when (component) {
    is KeyComponent -> ComponentType.KEY
    is TextComponent -> ComponentType.TEXT
    is JoystickComponent -> ComponentType.JOYSTICK
}

/**
 * 换一个 id（其它字段全不动）。
 *
 * ⚠️ 它**必须** `when` 到每一种组件类型：漏一种就是"某种组件复制出来还是同 id"，
 * 而编译器会直接报错（这里是穷尽 `when`，没有 `else`）——
 * 这正是当初刻意不写 `else` 的原因。
 */
fun CustomComponent.withNewId(id: String): CustomComponent = when (this) {
    is KeyComponent -> copy(id = id)
    is TextComponent -> copy(id = id)
    is JoystickComponent -> copy(id = id)
}
