package com.something.sthkey.domain.custom

import com.something.sthkey.domain.config.CPS_PLACEHOLDER
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.TouchRegion
import com.something.sthkey.domain.config.TouchSettings
import com.something.sthkey.domain.style.KeyLayout

/**
 * 把「触屏样式」的配置**转成**「自定义 Key 样式」。
 *
 * ============================================================
 * ⚠️⚠️ 它比"按键 → 自定义"只多一件事:把触摸区域搬过去
 * ============================================================
 * 用户的原话:
 *
 * > 可以开始做**触屏样式转自定义 key 样式**的功能了吧
 *
 * ⚠️ [KeyToCustomConverter] 只搬运**键位的外观与位置** ——
 * 那个对按键样式是够的（它的行为全在键位上）。
 *
 * ⚠️ 而触屏样式的行为**全在那几块触摸区域里**（`config.touch.regions`）:
 *
 * | 区域 | 含义 |
 * |---|---|
 * | [TouchSettings.JOYSTICK_SLOT_ID]（`@move`） | 手指按进去 = 推**移动轮盘** |
 * | 各个按键槽位 | 手指按进去 = 那个键**亮** |
 * | （没有区域的滑动） | 进**视角摇杆** |
 *
 * ⚠️ 所以只做 `KeyToCustomConverter` 那一层的话，转出来的自定义布局
 * **一块触摸区域都没有** —— 键不会亮、轮盘不会动，
 * 而用户看到的是一堆"长得一样但按了没反应"的方块。
 *
 * ============================================================
 * ⚠️⚠️ 位置、尺寸、外观**一律照抄**，一点都不另算
 * ============================================================
 * 用户的原话:
 *
 * > 坐标之类的，你看一下**手柄样式怎么转的**不就好了
 *
 * ⚠️ 他说得对。手柄那一支的做法是（见 `KeyToCustomConverter`）:
 *
 * | 项 | 来源 |
 * |---|---|
 * | 位置与尺寸 | `box.left` / `box.topY` / `box.width` / `box.height` |
 * | 外观 | `config.joystick`（整段照搬，一个字段都不丢） |
 * | 左右 | `box.slotId == JOYSTICK_RIGHT` |
 *
 * ⚠️ 而触屏样式的布局**本来就有两个摇杆槽位**（与手柄共用同一套结构，
 * 见 `KeyLayout.usesJoystickLayout`）—— 所以"两个摇杆摆在哪"
 * **布局函数已经回答过了**，不需要我另设坐标。
 *
 * ⚠️ 我第一版自己定了 `STICK_TOP` / `STICK_MARGIN` 之类，
 * 还**多加了一个视角摇杆组件** —— 那是重复的:布局里那个右摇杆槽位
 * 转出来就是视角摇杆，再加一个就会**两个叠在一起**。
 * 现在只做"改来源、挂区域、给手感"这三件事。
 *
 * ============================================================
 * ⚠️ 区域的几何**原样搬运，一个字都不换**
 * ============================================================
 * 触摸区域是**屏幕归一化坐标**（中心是整屏比例、边长是短边比例），
 * 而自定义布局的组件坐标是**编辑器画布坐标**（600×600 的逻辑定位区）——
 * 两者是**完全不同的两套坐标系**，但**不需要换算**:
 *
 * - 区域描述"手指按屏幕哪里" → 它与组件画在哪无关；
 * - 组件坐标描述"这个方块在编辑器里摆哪" → 那是给用户看的。
 *
 * ⚠️ 所以搬运区域时**直接用原值**，转换后的手感与原来**逐像素一致**
 * （触屏样式那边也是同一套归一化规则）。
 *
 * ⚠️ 转换后**视角摇杆变成"吃全屏滑动"的触屏摇杆** ——
 * 那正是触屏样式里"没落在任何区域里的触摸进视角"的等价物
 * （见 `JoystickSource.TOUCH` 的说明）。
 *
 * ============================================================
 * 为什么单独一个文件，而不是并进 [KeyToCustomConverter]
 * ============================================================
 * 那个文件已经 600 行、讲的是"怎么把布局搬成组件"；
 * 这里讲的是"触摸区域怎么挂上去"—— 两件事的**关注点不同**。
 * 合在一起会让"布局搬运"那段被区域逻辑淹掉，
 * 而它恰恰是"转完必须一模一样"的关键。
 */
object TouchToCustomConverter {

    /**
     * 转换结果。
     *
     * ⚠️ 与 [KeyToCustomConverter.Result] 形状一样但**独立的类型** ——
     * 两者的 `notes` 说的不是一回事（那边讲"丢了哪些专有开关"，
     * 这边讲"区域怎么挂的"）。
     */
    data class Result(
        val custom: CustomLayoutSettings,
        val notes: List<String>,
    )

    /**
     * 转换:触屏样式 → 自定义 Key。
     *
     * ⚠️ 分两步，**顺序不能反**:
     *
     * | 步骤 | 做什么 |
     * |---|---|
     * | ① 复用 [KeyToCustomConverter] | 把布局搬成组件（位置/尺寸/外观/文字，全部照抄） |
     * | ② 再叠一层"触屏的那部分" | 摇杆改成触屏来源、区域挂到对应组件上、手感搬过去 |
     *
     * ⚠️ **不重写布局搬运** —— 那会让两份实现漂开，
     * 而"转完位置必须一样"是这个功能的全部价值。
     */
    fun convert(config: KeyStrokesConfig): Result {
        /* ① 先把键位搬过来（这一步与按键/手柄样式完全相同） */
        val base = KeyToCustomConverter.convert(config)

        /*
         * ⚠️⚠️ **不要照抄 `base.notes`** —— 那一句是按键样式的说法:
         *
         * > "转换后不再有「显示 Shift」「显示鼠标键」「CPS 模式」这些开关，
         * >  要增删按键请直接在自定义编辑器里加删组件"
         *
         * ⚠️ 而本转换器**自己会说一句更贴切的**（见 [buildNotes]）——
         * 两句都留着的话，弹窗/日志里会出现两条几乎一样的说明。
         *
         * ⚠️ 而 `base.notes` 里**CPS 那一条是有效的**（触屏也可能开着 CPS），
         * 所以只滤掉那一句固定的，其余照留。
         */
        val notes = base.notes
            .filterNot { it.startsWith("转换后不再有") }
            .toMutableList()

        val touchRegions = config.touch.activeRegions

        /* ② 逐个组件:摇杆换来源、给手感（区域不再挂在组件上） */
        val components = base.custom.components.map { component ->
            when (component) {
                /*
                 * ⚠️ 摇杆:布局里那两个槽位转出来的就是它们，
                 * 位置尺寸已经由 `KeyToCustomConverter` 照 `box` 摆好了。
                 *
                 * ⚠️ 这里只改两件事:
                 *
                 * | 改什么 | 为什么 |
                 * |---|---|
                 * | `source` → `TOUCH` | 触屏样式的摇杆跟的是**触摸**，不是手柄 |
                 * | `touchFeel` | ⚠️ 用户调好的手感要跟着走 |
                 *
                 * ⚠️ **不再有 `touchRegion`** —— 新模型下区域是**配置级的
                 * 一等数据**（见 [CustomTouchRegion]），它靠**绑定**
                 * 连到摇杆组件上（见下面 ③）。
                 */
                is JoystickComponent -> component.copy(
                    source = JoystickSource.TOUCH,
                    /*
                     * ⚠️ 手感取触屏样式的**对应那一份** ——
                     * 移动摇杆用 `move`、视角摇杆用 `camera`。
                     *
                     * ⚠️ 取错的话用户只会觉得"转完之后手感变了"，
                     * 而不会想到是这里 —— 两边都有死区/灵敏度/时间，
                     * 数值不通用。
                     */
                    touchFeel = if (component.side == StickSide.LEFT) {
                        config.touch.move.feel
                    } else {
                        config.touch.camera.feel
                    },
                )

                /*
                 * ⚠️ 按键与文本组件**不用改** ——
                 * 它们与区域的联系现在走**绑定**（在下面 ③ 统一建），
                 * 而不是"组件自己带一份区域"。
                 */
                is KeyComponent, is TextComponent -> component
            }
        }

        /* ③ 把每一块区域转成 [CustomTouchRegion]，并绑到对应的组件上 */
        val converted = touchRegions.mapIndexed { index, binding ->
            val kind = if (binding.slotId == TouchSettings.JOYSTICK_SLOT_ID) {
                CustomTouchRegion.Kind.MOVE_STICK
            } else {
                CustomTouchRegion.Kind.BUTTON
            }

            /*
             * ⚠️ 找**这个槽位转出来的是哪个组件** —— 靠组件 id 反推
             * （见 [slotIdOf]）。认不出来就**不绑**（空绑定），
             * 而不是瞎猜一个:猜错会让区域挂到**别的键**上，
             * 而那个现象（"按这块区域亮的是另一个键"）极难查。
             *
             * ⚠️ 轮盘区域绑的是**移动摇杆**那个组件（`JOYSTICK_LEFT`
             * 转出来的）—— 不加方向，因为摇杆上报的是**轴**。
             */
            val targetSlot = binding.slotId
            val target = components.firstOrNull { slotIdOf(it) == targetSlot }

            CustomTouchRegion(
                id = "${targetSlot}_$index",
                kind = kind,
                region = TouchRegion(binding.centerX, binding.centerY, binding.size),
                bindings = if (target == null) {
                    emptyList()
                } else {
                    listOf(CustomTouchRegionBinding(componentId = target.id))
                },
            )
        }

        notes += buildNotes(
            attached = converted.count { it.bindings.isNotEmpty() },
            hasMove = touchRegions.any { it.slotId == TouchSettings.JOYSTICK_SLOT_ID },
        )

        return Result(
            custom = CustomLayoutSettings(
                components = components,
                touchRegions = converted,
            ),
            notes = notes,
        )
    }

    /**
     * 保证文字里有 CPS 占位符。
     *
     * ⚠️ 文本组件的触摸区域要"让 CPS 加"，而**没有占位符就没有地方显示** ——
     * 那块区域的点击会**无处可去**（用户看到"按了没反应"）。
     *
     * ⚠️ 只在**真的没有**时补，不覆盖用户自己的文字 ——
     * 触屏样式开着 CPS 时转出来的文本组件本来就带占位符
     * （`KeyToCustomConverter` 那一步会重建模板）。
     */
    private fun ensureCpsPlaceholder(text: String): String =
        if (CustomLayout.cpsPlaceholders(text).isNotEmpty()) text else text + CPS_PLACEHOLDER

    /**
     * 一个组件对应**哪个槽位**（用来把区域挂上去）。
     *
     * ============================================================
     * ⚠️⚠️ 组件 id 的真实格式是 `conv_<槽位小写>_<序号>`
     * ============================================================
     * 见 [KeyToCustomConverter.componentId]:
     *
     * ```
     * conv_lmb_0              ← 槽位 LMB
     * conv_space_3            ← 槽位 SPACE
     * conv_joystick_left_1    ← ⚠️ 槽位名里**自己带下划线**
     * ```
     *
     * ⚠️ 我第一版写的是"去掉最后一段、与槽位名比" —— **两处都错**:
     *
     * | 错 | 后果 |
     * |---|---|
     * | 没去掉 `conv_` 前缀 | 与槽位名永远不相等 |
     * | 没统一大小写 | `lmb` ≠ `LMB` |
     *
     * ⚠️ 两处叠加的结果是**一块区域都挂不上** ——
     * 而"转完之后按了不亮"看起来像"整个转换功能坏了"，
     * 不会有任何报错指向这里。
     *
     * ⚠️ 所以反过来做:**用槽位名拼出 id**（与生成方用同一个格式），
     * 再比。槽位名里带下划线这件事**不影响** ——
     * 因为是"拼出来比"，不是"拆开猜"。
     */
    private fun slotIdOf(component: CustomComponent): String? =
        ALL_SLOT_IDS.firstOrNull { slotId ->
            component.id.startsWith("conv_${slotId.lowercase()}_")
        }

    /**
     * 触屏样式可能出现的**全部槽位 id**。
     *
     * ⚠️ 取自 [KeyLayout.Id] 与 [TouchSettings.JOYSTICK_SLOT_ID] ——
     * 不手写字符串:手写的话，以后改了槽位名这里会**静默失效**
     * （区域挂不上去，而没有任何报错）。
     */
    private val ALL_SLOT_IDS: List<String> = listOf(
        KeyLayout.Id.W,
        KeyLayout.Id.A,
        KeyLayout.Id.S,
        KeyLayout.Id.D,
        KeyLayout.Id.SPACE,
        KeyLayout.Id.SHIFT,
        KeyLayout.Id.LMB,
        KeyLayout.Id.RMB,
        KeyLayout.Id.CPS_L,
        KeyLayout.Id.CPS_R,
        KeyLayout.Id.JOYSTICK_LEFT,
        KeyLayout.Id.JOYSTICK_RIGHT,
        TouchSettings.JOYSTICK_SLOT_ID,
    )

    /**
     * 转换说明。
     *
     * ⚠️ 这些不是"客套话" —— 用户按了确认之后就**退不回去**了，
     * 所以必须如实告诉他"哪些东西变成了什么"。
     */
    private fun buildNotes(attached: Int, hasMove: Boolean): List<String> = buildList {
        if (attached > 0) {
            add("$attached 块触摸区域已挂到对应的按键组件上（位置与大小原样保留）")
        } else {
            add(
                "这个配置还没有配过按键触摸区域 —— 转出来的按键不会因为触摸而亮，" +
                    "要在自定义编辑器里给它们加区域",
            )
        }

        if (hasMove) {
            add("移动轮盘已转成「摇杆-触屏（移动）」，它的触摸区域与手感原样保留")
        } else {
            add("原来没有配移动轮盘区域 —— 转出来的移动摇杆要先给它配一块区域才会动")
        }

        add("视角摇杆已转成「摇杆-触屏（视角）」—— 它吃全屏滑动，不需要区域")
        add("转换后不再有「显示 Shift」「显示鼠标键」这些开关，要增删按键请直接编辑组件")
    }
}
