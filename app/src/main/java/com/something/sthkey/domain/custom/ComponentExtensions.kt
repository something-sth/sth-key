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
    is JoystickComponent -> copy(x = x, y = y)
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
 * ⚠️ 为什么改尺寸还是要连位置一起夹
 * ============================================================
 * 坐标范围现在**不依赖尺寸**了（固定 `-1000 .. 1000`），所以
 * "改小尺寸之后坐标突然越界"这种情况不会再发生。
 *
 * ⚠️ 但夹取**依然要留着**:数据可能来自手工改过的配置包
 * （x 写成 99999），而这条路径是编辑器唯一会碰尺寸的地方 ——
 * 让它顺手把坏数据修正掉，比在别处再补一次检查便宜。
 */
fun CustomComponent.resizedTo(width: Float, height: Float): CustomComponent {
    val w = width.coerceIn(CustomLayout.COMPONENT_SIZE_MIN, CustomLayout.COMPONENT_SIZE_MAX)
    val h = height.coerceIn(CustomLayout.COMPONENT_SIZE_MIN, CustomLayout.COMPONENT_SIZE_MAX)
    return when (this) {
        is KeyComponent -> copy(width = w, height = h).clampedToCanvas()
        is TextComponent -> copy(width = w, height = h).clampedToCanvas()
        is JoystickComponent -> copy(width = w, height = h).clampedToCanvas()
    }
}

/**
 * 改**边长**（宽高一起改，永远正方形）。
 *
 * ============================================================
 * ⚠️ 摇杆为什么是"边长"而不是"宽 / 高"
 * ============================================================
 * 用户的原话:"摇杆组件在自定义编辑中，应该是调**边长**，而不是像别的组件
 * 一样设计长、宽，摇杆本身就是圆角正方形，单调一个长或宽，显示都有 bug"。
 *
 * 摇杆的三层几何（底盘圆角、盘内那个圆的半径、摇杆帽能走多远）全部由
 * **一个边长**推出 —— 宽高不等时:
 *
 * - 盘内那个圆是按边长推半径的，宽高不等就会**看起来是椭圆**；
 * - 渲染层实际取的是 `min(宽, 高)`（见 `CustomKeyGrid` 里那段），
 *   于是"调宽"这个操作**画面上什么都不会变**，用户会以为滑块坏了。
 *
 * ⚠️ 所以对摇杆**根本不提供宽 / 高两个滑块**，只给一个"边长"。
 * 这个函数是它唯一的入口 —— 与 [resizedTo] 一样，
 * 顺手把坐标夹回合法范围（理由见 [resizedTo] 的说明）。
 */
fun CustomComponent.resizedToSide(side: Float): CustomComponent {
    val s = side.coerceIn(CustomLayout.COMPONENT_SIZE_MIN, CustomLayout.COMPONENT_SIZE_MAX)
    return when (this) {
        /*
         * 按键与文本**没有"边长"这个概念** —— 它们可以是任意长宽比
         * （空格 260×55、CPS 文本 120×30 都是正常的）。
         * 所以这里保持正方形（把两个方向都设成 s），而不是报错:
         * 调用方本来就只该对摇杆用它，但"传错了也不会得到一个畸形组件"更安全。
         */
        is KeyComponent -> copy(width = s, height = s).clampedToCanvas()
        is TextComponent -> copy(width = s, height = s).clampedToCanvas()
        is JoystickComponent -> copy(width = s, height = s).clampedToCanvas()
    }
}

/**
 * 把坐标夹进合法范围。
 *
 * 滑块理论上不会越界，但数据可能来自手工改过的配置包，
 * 所以每次几何改动后都过一遍这个函数，比"相信调用方"便宜得多。
 *
 * ⚠️ 坐标范围现在是**固定**的（`-1000 .. 1000`），**不依赖组件尺寸** ——
 * 早先下界是 `-尺寸`，于是"改宽高会让 X 滑块的范围跟着变"，
 * 那是一种很难解释的界面行为（见 [CustomLayout.COORDINATE_MIN]）。
 */
fun CustomComponent.clampedToCanvas(): CustomComponent = movedTo(
    x = x.coerceIn(CustomLayout.minCoordinate(), CustomLayout.maxCoordinate()),
    y = y.coerceIn(CustomLayout.minCoordinate(), CustomLayout.maxCoordinate()),
)

/** 把组件摆到画布中央（给"居中"按钮用） */
fun CustomComponent.centeredOnCanvas(): CustomComponent = movedTo(
    x = ((CustomLayout.BASE_CANVAS - width) / 2f).roundToInt().toFloat(),
    y = ((CustomLayout.BASE_CANVAS - height) / 2f).roundToInt().toFloat(),
)

/**
 * 改外观主题，几何与内容不动。
 *
 * ============================================================
 * ⚠️ 只对**有文字**的组件（[TextualComponent]）有意义
 * ============================================================
 * [ComponentStyle] 是"键帽 + 文字"那一套。摇杆用的是 [JoystickStyle]
 * （三层结构，见 [JoystickComponent] 的说明），所以它**不在这里** ——
 * 硬给它一个 `copy(style = …)` 只会得到一个改了没反应的入口。
 *
 * 属性面板也因此**不为摇杆显示**颜色/透明度/圆角/描边那几组，
 * 而是显示它自己的「摇杆 / 摇杆帽 / 摇杆手感」。
 */
fun TextualComponent.withStyle(style: ComponentStyle): TextualComponent = when (this) {
    is KeyComponent -> copy(style = style)
    is TextComponent -> copy(style = style)
}

/**
 * 取出**有文字组件**的样式；摇杆没有 [ComponentStyle]，返回 null。
 *
 * 给"打开字体选择器时要知道当前选的是哪个字体"这类**只读**场景用 ——
 * 那种地方拿到 null 直接当成"没选字体"即可。
 */
fun CustomComponent.textStyle(): ComponentStyle? = (this as? TextualComponent)?.style

/**
 * 改样式，**不是文字组件就原样返回**。
 *
 * ============================================================
 * ⚠️ 为什么需要它
 * ============================================================
 * 调用点（编辑器里"导入完字体顺手应用给选中的组件"）拿到的是一个
 * `CustomComponent`，可能正好选中了摇杆。那种情况下 [withStyle] 用不了
 * （它只接受 [TextualComponent]）。
 *
 * 在这里写 `as? TextualComponent` 的好处是**整个项目只有这一处**
 * 做这个判断 —— 分散到各个调用点的话，"摇杆被当成文本改"这种错
 * 迟早会在某一处漏掉。
 */
fun CustomComponent.withTextStyle(
    transform: (ComponentStyle) -> ComponentStyle,
): CustomComponent {
    val textual = this as? TextualComponent ?: return this
    return textual.withStyle(transform(textual.style))
}

/** 改样式（直接给一份新样式），非文字组件原样返回 */
fun CustomComponent.withTextStyle(style: ComponentStyle): CustomComponent {
    val textual = this as? TextualComponent ?: return this
    return textual.withStyle(style)
}

/**
 * 组件的类型名；UI 列表与调试信息共用一处。
 *
 * ============================================================
 * ⚠️ 三种摇杆必须**分别报名字**（用户报过这个）
 * ============================================================
 * 用户的原话:"自定义编辑页内，创建出来的键盘摇杆和鼠标摇杆**都是显示
 * 「左摇杆」字样**，不过实际配置是正确的"。
 *
 * ⚠️ 成因:三种摇杆**共用同一个数据类** [JoystickComponent]，而
 * [summary] 原来一律返回 `side.label` —— 对键盘/鼠标摇杆来说
 * 那个"左摇杆"是**一个用不上的字段**（它们没有左右之分）。
 *
 * ⚠️ 所以显示要按 [JoystickComponent.source] 分。用户还要求
 * "手柄摇杆也修改一下，现在只是左摇杆、右摇杆，改成**手柄**左摇杆、
 * **手柄**右摇杆" —— 那在 [summary] 里加前缀。
 */
fun CustomComponent.typeLabel(): String = when (this) {
    is KeyComponent -> "按键"
    is TextComponent -> "文本"
    is JoystickComponent -> when (source) {
        JoystickSource.GAMEPAD -> "手柄摇杆"
        JoystickSource.KEYBOARD -> "键盘摇杆"
        JoystickSource.MOUSE -> "鼠标摇杆"
    }
}

/**
 * 组件在列表里显示的一行摘要。
 *
 * ⚠️ 它是**用户唯一能区分几个摇杆的地方**（画布上它们长得一样），
 * 所以必须写清"这个摇杆在听什么":
 *
 * | 组件 | 摘要 |
 * |---|---|
 * | 手柄摇杆 | `手柄左摇杆` / `手柄右摇杆` |
 * | 键盘摇杆 | `WASD` |
 * | 鼠标摇杆 | `鼠标位移` |
 *
 * ⚠️ 手柄那两个的"手柄"前缀是用户明确要求的:不写的话，
 * 三个摇杆的摘要会是"左摇杆 / WASD / 鼠标位移" —— 只有第一个
 * 看不出它在跟手柄，而它恰恰最需要说明。
 */
fun CustomComponent.summary(): String = when (this) {
    is KeyComponent -> label.ifBlank { "（无键名）" }
    is TextComponent -> text.ifBlank { "（空文本）" }
    /*
     * ⚠️ **按来源分**:键盘/鼠标摇杆没有"左/右"可言，
     * 报 `side.label` 就是那个"都显示左摇杆"的 bug。
     */
    is JoystickComponent -> when (source) {
        JoystickSource.GAMEPAD -> "手柄${side.label}"
        JoystickSource.KEYBOARD -> "WASD"
        JoystickSource.MOUSE -> "鼠标位移"
    }
}

/*
 * ============================================================
 * 文字相关的操作：只对 [TextualComponent] 有效
 * ============================================================
 * ⚠️ 摇杆**没有文字**，所以这几个函数刻意**不**接受 `CustomComponent`。
 *
 * 早先它们的签名是 `CustomComponent.xxx`，加摇杆之后就得在实现里写
 * `is JoystickComponent -> this`（原样返回）—— 那样调用方传错了类型
 * **编译期不会报错**，只会在运行时静默什么都不做。
 * 收窄到 [TextualComponent] 之后，编译器就替我们挡住这一类错误。
 */

/** 主体文字：按键组件是键面文字，文本组件是文本内容 */
fun TextualComponent.primaryText(): String = when (this) {
    is KeyComponent -> label
    is TextComponent -> text
}

/** 改主体文字 */
fun TextualComponent.withPrimaryText(value: String): TextualComponent = when (this) {
    is KeyComponent -> copy(label = value)
    is TextComponent -> copy(text = value)
}

/** 改文字缩放 */
fun TextualComponent.withTextScale(percent: Int): TextualComponent {
    val clamped = percent.coerceIn(CustomLayout.TEXT_SCALE_MIN, CustomLayout.TEXT_SCALE_MAX)
    return when (this) {
        is KeyComponent -> copy(textScalePercent = clamped)
        is TextComponent -> copy(textScalePercent = clamped)
    }
}

/**
 * 改文字偏移。
 *
 * 只挪文字、**不影响边框** —— 文字偏一点不该把元件的占位也跟着挪走。
 */
fun TextualComponent.withTextOffset(x: Float? = null, y: Float? = null): TextualComponent =
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
    /* 摇杆没有 CPS 可言 —— 它显示的是摇杆位置，不是次数 */
    is JoystickComponent -> this
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
        /* 摇杆上报的是**轴**，不是键码 —— 绑键码没有意义（同键位映射那边） */
        is JoystickComponent -> this
    }
