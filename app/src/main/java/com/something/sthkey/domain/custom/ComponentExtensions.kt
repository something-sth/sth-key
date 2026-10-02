package com.something.sthkey.domain.custom

import kotlin.math.roundToInt


/**
 * 组件上的**便捷扩展**（文件级函数，不是 [CustomLayout] 的成员）。
 *
 * ============================================================
 * 为什么放在 object 外面
 * ============================================================
 * 它们**必须**集中在一处：`CustomComponent` 是密封接口、实体是两个 data class，
 * 所以任何"改某个字段"的操作都要写一遍 `when (this)`。
 * 分散在编辑器、属性面板、诊断代码里的话，漏掉一种组件类型就是
 * "某种组件改不动" —— **而且编译不会报错**（`when` 带上 else 就过去了）。
 *
 * 曾经把它们写在 `object CustomLayout` 内部，结果既不能当成员调用
 * （`CustomLayout.movedTo(...)` 报错），也不能当扩展导入（`import ...movedTo` 报错）——
 * 因为 Kotlin 的成员扩展函数要求**两层接收者**，用起来处处别扭。
 * 移到文件级之后就能正常 `import com.something.sthkey.domain.custom.movedTo` 了。
 */

/**
 * 把组件放到指定位置（不改尺寸）。
 *
 * 编辑器的拖动、吸附、属性面板都用它。
 */
fun CustomComponent.movedTo(x: Float, y: Float): CustomComponent = when (this) {
    is KeyComponent -> copy(x = x, y = y)
    is TextComponent -> copy(x = x, y = y)
}

/** 平移一个组件（不改尺寸） */
fun CustomComponent.movedBy(dx: Float, dy: Float): CustomComponent =
    movedTo(x = x + dx, y = y + dy)

/** 坐标取整，消掉拖动攒下的小数 */
fun CustomComponent.rounded(): CustomComponent = movedTo(
    x = x.roundToInt().toFloat(),
    y = y.roundToInt().toFloat(),
)

/*
 * ============================================================
 * 几何：位置与尺寸
 * ============================================================
 * 这几个函数**必须**放在文件级，不能放进 `object CustomLayout`：
 * 那样它们就是"成员扩展函数"，只能写成 `CustomLayout.resizedTo(c, w, h)`，
 * 既不能 `import` 也不能 `component.resizedTo(...)` 调用 ——
 * 直接后果是一整排 `Unresolved reference`（这个坑踩过）。
 * 纯计算的规则（范围常量、颜色换算）留在 object 里，改字段的留在这里。
 */

/**
 * 改尺寸（顺便把坐标夹回合法范围）。
 *
 * 编辑器改尺寸**只能走这个函数**：每多一个能改尺寸的入口，
 * 就多一处忘了夹范围的地方。
 *
 * ============================================================
 * ⚠️ 为什么改尺寸要连位置一起夹
 * ============================================================
 * 坐标下界是 `-尺寸`（见 [CustomLayout.minCoordinate]），**它跟着尺寸变**。
 * 一个贴住左边界的组件（x = -200、宽 200）被缩到宽 50 之后，
 * 合法下界变成 -50，而 x 还停在 -200。
 *
 * 这时 X 滑块读到的值已经超出自己的范围，会被夹到 -50 显示；
 * 用户一碰滑块，组件就会**凭空跳 150**。所以不变量必须是
 * "任何时刻坐标都在范围内"，而不是"只有直接改坐标时才检查"。
 */
fun CustomComponent.resizedTo(width: Float, height: Float): CustomComponent {
    val w = width.coerceIn(CustomLayout.COMPONENT_SIZE_MIN, CustomLayout.COMPONENT_SIZE_MAX)
    val h = height.coerceIn(CustomLayout.COMPONENT_SIZE_MIN, CustomLayout.COMPONENT_SIZE_MAX)
    return when (this) {
        is KeyComponent -> copy(width = w, height = h).clampedToCanvas()
        is TextComponent -> copy(width = w, height = h).clampedToCanvas()
    }
}

/**
 * 把坐标夹进合法范围。
 *
 * 滑块理论上不会越界，但坐标下界依赖尺寸、数据也可能来自手工改过的配置包，
 * 所以每次几何改动后都过一遍这个函数，比"相信调用方"便宜得多。
 */
fun CustomComponent.clampedToCanvas(): CustomComponent = movedTo(
    x = x.coerceIn(CustomLayout.minCoordinate(width), CustomLayout.maxCoordinate()),
    y = y.coerceIn(CustomLayout.minCoordinate(height), CustomLayout.maxCoordinate()),
)

/** 把组件摆到画布中央（给"居中"按钮用） */
fun CustomComponent.centeredOnCanvas(): CustomComponent = movedTo(
    x = ((CustomLayout.BASE_CANVAS - width) / 2f).roundToInt().toFloat(),
    y = ((CustomLayout.BASE_CANVAS - height) / 2f).roundToInt().toFloat(),
)

/**
 * 改外观主题，几何与内容不动。
 *
 * 属性面板改颜色/圆角/字体时都要"换掉 style"，而键面文字、键位映射这些
 * **类型特有的字段不能被顺手丢掉** —— 统一入口就不会出现"改个颜色把键位映射改没了"。
 */
fun CustomComponent.withStyle(style: ComponentStyle): CustomComponent = when (this) {
    is KeyComponent -> copy(style = style)
    is TextComponent -> copy(style = style)
}

/** 组件的类型名；UI 列表与调试信息共用一处 */
fun CustomComponent.typeLabel(): String = when (this) {
    is KeyComponent -> "按键"
    is TextComponent -> "文本"
}

/** 组件在列表里显示的一行摘要 */
fun CustomComponent.summary(): String = when (this) {
    is KeyComponent -> label.ifBlank { "（无键名）" }
    is TextComponent -> text.ifBlank { "（空文本）" }
}

/** 主体文字：按键组件是键面文字，文本组件是文本内容 */
fun CustomComponent.primaryText(): String = when (this) {
    is KeyComponent -> label
    is TextComponent -> text
}

/** 改主体文字 */
fun CustomComponent.withPrimaryText(value: String): CustomComponent = when (this) {
    is KeyComponent -> copy(label = value)
    is TextComponent -> copy(text = value)
}

/** 改文字缩放 */
fun CustomComponent.withTextScale(percent: Int): CustomComponent = when (this) {
    is KeyComponent -> copy(
        textScalePercent = percent.coerceIn(
            CustomLayout.TEXT_SCALE_MIN,
            CustomLayout.TEXT_SCALE_MAX,
        ),
    )

    is TextComponent -> copy(
        textScalePercent = percent.coerceIn(
            CustomLayout.TEXT_SCALE_MIN,
            CustomLayout.TEXT_SCALE_MAX,
        ),
    )
}

/**
 * 改文字偏移。
 *
 * 只挪文字、**不影响边框** —— 文字偏一点不该把元件的占位也跟着挪走。
 */
fun CustomComponent.withTextOffset(x: Float? = null, y: Float? = null): CustomComponent =
    when (this) {
        is KeyComponent -> copy(
            textOffsetX = x ?: textOffsetX,
            textOffsetY = y ?: textOffsetY,
        )

        is TextComponent -> copy(
            textOffsetX = x ?: textOffsetX,
            textOffsetY = y ?: textOffsetY,
        )
    }

/**
 * 改 CPS 统计的键。
 *
 * ⚠️ **只有文本组件有这个字段**，所以这里刻意**不是**"两种类型各 copy 一遍"：
 * 按键组件的 CPS 来源是它自己的 [KeyComponent.inputKeyCodes]（映射好的），
 * 硬给它一个字段就是又开了一个能与映射不一致的真源。
 *
 * 传进来的如果不是文本组件，就原样返回 —— 调用方（属性面板）本来也只在
 * 文本组件上显示这个选项，这里兜住是为了"以后加了第三种组件"时不会崩。
 */
fun CustomComponent.withCpsKeyCodes(keyCodes: List<Int>): CustomComponent = when (this) {
    is TextComponent -> copy(cpsKeyCodes = keyCodes)
    is KeyComponent -> this
}

/**
 * 改**第 [index] 个 CPS 占位符**统计的键。
 *
 * ============================================================
 * 为什么不能只改第一个
 * ============================================================
 * 一个文本组件可以写多个占位符（见 [CustomLayout.cpsPlaceholders]），
 * 每个各有一组键位、按出现顺序对应。所以属性面板里点第 3 条键位时，
 * 只能改第 3 组 —— 用 [withCpsKeyCodes] 会把整份列表替换掉，
 * 前面几组配置就全丢了。
 *
 * 列表比下标短时先补齐（用 [DEFAULT_CPS_KEY_CODES]），
 * 于是"新写了一个占位符再去选键位"这条路是通的。
 */
fun CustomComponent.withCpsKeyCodesAt(index: Int, keyCodes: List<Int>): CustomComponent {
    if (this !is TextComponent) return this
    if (index < 0) return this

    val groups = cpsKeyCodesPerPlaceholder.toMutableList()
    while (groups.size <= index) {
        // 补齐中间那些：新写的占位符还没配过，用默认值（鼠标左键）
        groups += listOf(DEFAULT_CPS_KEY_CODES)
    }
    groups[index] = keyCodes
    return copy(cpsKeyCodesPerPlaceholder = groups)
}

/** 改监听的按键（可多个，与键位映射同一个形状） */
fun CustomComponent.withInputKeyCodes(keyCodes: List<Int>): CustomComponent =
    when (this) {
        is KeyComponent -> copy(inputKeyCodes = keyCodes)
        is TextComponent -> this
    }
