package com.something.sthkey.data.config

import com.something.sthkey.domain.config.ANIMATION_DURATION_MAX
import com.something.sthkey.domain.config.ANIMATION_DURATION_MIN
import com.something.sthkey.domain.config.AnimationMode
import com.something.sthkey.domain.config.ColorSet
import com.something.sthkey.domain.config.DEFAULT_CPS_TEMPLATE
import com.something.sthkey.domain.config.DEFAULT_CPS_TEMPLATE_MODE1
import com.something.sthkey.domain.config.DEFAULT_FONT_ID
import com.something.sthkey.domain.config.KeyMapping
import com.something.sthkey.domain.config.KeyOutline
import com.something.sthkey.domain.config.JoystickStyle
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.defaultShowSpaceKey
import com.something.sthkey.domain.config.TextOffset
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.domain.style.StyleId
import com.something.sthkey.domain.config.TextSpacing
import com.something.sthkey.domain.config.LIVE2D_MODEL_STANDARD
import com.something.sthkey.domain.config.Live2DSettings
import com.something.sthkey.domain.config.Opacity
import com.something.sthkey.domain.config.ShadowMode
import com.something.sthkey.domain.config.TextShadow
import com.something.sthkey.domain.config.TouchFeelSpec
import com.something.sthkey.domain.config.TouchRegionBinding
import com.something.sthkey.domain.config.TouchRegion
import com.something.sthkey.domain.config.TouchRegionSpec
import com.something.sthkey.domain.config.TouchSettings
import com.something.sthkey.domain.config.TouchStickFeel
import com.something.sthkey.domain.config.TouchStickSettings
import com.something.sthkey.domain.config.defaultConfig
import com.something.sthkey.domain.config.defaultKeyMappings
import com.something.sthkey.domain.config.migratedOutlineColors
import com.something.sthkey.domain.style.OverlayStyleRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置的 JSON 编解码。
 *
 * ============================================================
 * 当前形态 vs 最终形态
 * ============================================================
 * 最终形态是 **zip 压缩包**（见 domain/config/README.md），因为样式会带资源
 * （自定义 Live2D 模型、替换键帽的图片）。但那些资源现在还没有，
 * 参数本身必须先能存住 —— 否则用户建好的配置重启就没了。
 *
 * 所以这一版：**参数走 JSON，落盘到 SharedPreferences**。
 * 等样式资源进来时，把这一层换成 zip 读写即可，
 * 字段命名与结构刻意按最终 manifest.json 的形状设计，到时不用改结构。
 *
 * 读取容错原则：**宽容读、规范写**。
 * 缺字段用默认值、越界值 clamp、未知样式回落默认样式 ——
 * 否则以后加字段，老配置会直接读崩。
 */
object JsonConfigCodec {

    /** 结构版本；以后字段有破坏性变更时递增并写迁移 */
    private const val SCHEMA_VERSION = 1

    /**
     * 本版本认识的全部顶层字段 —— **由编码器自己推导，不是手工清单**。
     *
     * ============================================================
     * ⚠️ 这里换过一次做法（手工清单漏过一次）
     * ============================================================
     * 原来是手写的一长串字符串，规则是"加字段时必须同步加到这里"。
     * 而**没有任何机制会提醒你忘了** —— 实际就漏了：
     * 加「文字阴影」时编码器写了 `shadow`，清单里没加，
     * 于是用户导出再导回来，导入报告会说「未识别的字段 shadow」：
     * **我们自己写的字段，我们自己说不认识**。
     *
     * 现在改成：拿一份"字段全都不是默认值"的配置真的编码一遍，
     * 它写出来的键就是"我们认识的键"。加字段时只要编码器写了，
     * 这里自动就有 —— **结构上不可能再漏**。
     *
     * 用途有两个：
     * 1. 导入外部配置包时，找出"配置里有、我们不认识"的字段（多半来自新版）；
     * 2. 找出"我们认识、配置里没写"的字段（会用默认值）。
     *
     * ============================================================
     * ⚠️ 代价：它**没法在本地单元测试里用**
     * ============================================================
     * `org.json` 在本地单测里是空壳（一调就 `Method not mocked`），
     * 而这里要调 [encode]。所以"字段清单"这一类断言写不了单测 ——
     * 曾经写过三条，全部因为这个原因失败，只能删掉。
     *
     * 但这笔交易是划算的：**一个结构上不会错的机制，
     * 胜过一组要靠人记得维护的测试**。原来那三条测试就算写出来，
     * 也只是"事后发现漏了"；现在是"漏不了"。
     *
     * 唯一还需要人盯的是 [KEY_LABELS]（给人看的措辞，没法推导），
     * 漏了不会错数据，只会在导入报告里露出内部键名。
     *
     * ============================================================
     * ⚠️⚠️ 它必须遍历**每一种样式**（不能只探一份配置）
     * ============================================================
     * 编码改成"按样式只写相关字段"之后（见 [STYLE_SPECIFIC_KEYS]），
     * 手柄样式的 `showShoulderButtons` / `aButtonOnTop` 这类字段
     * **在键盘样式的探针里根本不会被写出来** —— 于是它们掉出这个集合，
     * 导入游戏手柄配置时会被报成"未识别字段"。
     *
     * ⚠️ 所以这里改成**每种样式各探一份、取并集**。
     * "从编码结果反推字段清单"这个机制本身没问题，
     * 但**探针必须覆盖编码的所有分支** —— 条件写、按样式写，都算分支。
     */
    val KNOWN_KEYS: Set<String> by lazy {
        probeConfigsForKnownKeys()
            .flatMap { encode(it).keys().asSequence().toList() }
            .toSet()
    }

    /**
     * **某个样式的配置本该有哪些字段**。
     *
     * ============================================================
     * ⚠️⚠️ 为什么需要它（用户报的第二个问题）
     * ============================================================
     * 用户的原话:
     *
     * > 不过现在导入配置显示的字段差异还没改，我**导出再导入有一大堆字段差异**，
     * > 不过实际上没什么区别
     *
     * ⚠️ 成因:导入报告的"缺失字段"拿 [KNOWN_KEYS] 当基准，
     * 而那是**所有样式的并集**。编码改成按样式只写相关字段之后
     * （见 [STYLE_SPECIFIC_KEYS]），一份自定义 Key 配置里
     * 自然没有键盘/手柄那一堆字段 —— 于是每导入一次自家导出的包，
     * 报告都会列出一长串"这些字段用了默认值"。
     *
     * ⚠️ 那是**把正常情况说成异常** —— 与 `MANIFEST_OWNED_KEYS`
     * 当初要解决的问题一模一样（见那边的注释）。
     *
     * ⚠️ 所以基准要**按归档的样式**算:自定义 Key 的包只跟自定义 Key
     * 该有的字段比，不多不少。
     *
     * ⚠️ 认不出的 `styleId`（来自更新版本的包）一律回落到
     * [OverlayStyleRegistry.defaultStyleId] —— 那时报告的"未知字段"
     * 才是真正该提示的东西。
     */
    fun expectedKeysFor(styleId: String): Set<String> {
        val resolved = OverlayStyleRegistry.resolveOrDefault(styleId).id
        return probeConfigsForKnownKeys()
            .firstOrNull { it.styleId == resolved }
            ?.let { encode(it).keys().asSequence().toSet() }
            ?: KNOWN_KEYS
    }

    /**
     * **哪些字段只对某些样式有意义** —— 编码时按 `styleId` 门控的唯一真源。
     *
     * ============================================================
     * ⚠️⚠️ 为什么要这张表（用户报的问题）
     * ============================================================
     * 用户的原话:
     *
     * > 现在这个自定义 key 的配置字段里为什么会有 aButtonOnTop，showSpaceKey，
     * > spaceKeyOnTop，showAButton 这些字段，这些字段不应该出现在自定义 key 里，
     * > 因为本身就没什么用，自定义 key 的组件都是自己创建调整的
     * >
     * > 也不止自定义 key 字段有问题，别的也是，例如键盘样式配置字段里居然
     * > 出现了 showAButton 和 aButtonOnTop，这两个应该是独属于手柄样式的字段
     * > …… 我不想搞这种冗余的逻辑 …… 不要让配置字段里出现这个配置中多余的字段
     *
     * ⚠️ 他说得对。以前的做法是"所有字段无条件全写"，理由写的是
     * "用户可能临时切走样式再切回来，不写会丢设置"。
     *
     * ⚠️⚠️ **而那个理由是不成立的**:样式创建后就**不能切换** ——
     * 见 `ConfigListScreen` 里那句"不同样式的配置互不通用，创建后不能切换样式"
     * 以及 `ConfigStore` 的样式解析。所以"切走再切回"这个场景**不存在**，
     * 无条件写只换来一堆与这份配置无关的字段。
     *
     * ============================================================
     * 表里没列出的字段 = **所有样式都写**（身份、外观等共用字段）
     * ============================================================
     * 只有"确实只属于某个样式"的才登记在这里 ——
     * 表越短越好维护，而漏登记一个的后果只是"多写一个字段"（旧行为），
     * 不会丢数据。
     *
     * ⚠️ 判据一律是**布局代码真的读它**，不是"看起来像"。
     * 每一行的注释都写了读它的位置，改之前先看那里。
     */
    /**
     * 有**键位网格**的三个样式（键盘 / 手柄 / **触屏**）。
     *
     * ⚠️ 布局、文字、外观那一大批字段只有这三个样式会读 ——
     * 自定义 Key 与 Live2D 各有自己的一套（见 [STYLE_SPECIFIC_KEYS]）。
     *
     * ============================================================
     * ⚠️⚠️ 触屏原来**不在这里** —— 那是"配置没做持久化"的根因
     * ============================================================
     * 用户的原话:
     *
     * > 刚刚发现的一些漏洞，**触屏配置有些东西没做配置持久化**，
     * > 我就不一一列出了你自己排查
     *
     * ⚠️ 排查结果:触屏的**外观**（文字缩放/圆角/描边/阴影/透明度/字体）、
     * **CPS**、**按键高度与间距**、**文字偏移与字间距**……
     * **全都没被编码**，于是每次重启应用都回到默认值。
     *
     * ⚠️ 根因是这一行只列了两个样式，而触屏**本来就是"键盘布局 + 摇杆"**
     * （见 `KeyLayout.usesJoystickLayout`）—— 它读的就是这一批字段。
     *
     * ⚠️ 这与用户在行为栏上的要求是同一个道理:
     *
     * > 触屏样式内，行为栏的功能外显都做成**与键盘样式一样**的
     */
    private val KEY_GRID_STYLES = setOf(StyleId.KEYSTROKES, StyleId.GAMEPAD2, StyleId.TOUCHING)

    private val STYLE_SPECIFIC_KEYS: Map<String, Set<String>> = mapOf(
        /*
         * ── 「显示 SPACE 键」与「SPACE 键置顶」──
         *
         * ⚠️⚠️ **键盘与触屏都有**，只是含义按样式对齐到"那个 Space 组件"
         * ============================================================
         * 用户的原话:
         *
         * > 你应该想的是，把现在的「显示 SPACE 键」**改为绑定在现在触屏样式
         * > 已经默认有的这个 Space 组件上**，并让他**默认打开**，
         * > 而不是删去，**Space 置顶也用得到啊**，你这样改不合适吧
         *
         * ⚠️ 他说得对 —— 我上一版想"删掉开关"，方向错了。
         *
         * ⚠️ 正确的做法见 `touchingKeyMappings`:触屏**改用键盘那个 `SPACE`
         * 槽位**当 Space（并把多余的 `A_BUTTON` 行藏起来），
         * 于是开关与置顶**都作用在同一个组件上**。
         *
         * ⚠️ 手柄**不在这一组里** —— 它拿 `A_BUTTON` 当空格位、
         * 显示 "A"，那个槽位有自己的开关（「显示 A 键」/「A 键置顶」），
         * 再加一对会重复。
         */
        "showSpaceKey" to setOf(StyleId.KEYSTROKES, StyleId.TOUCHING),
        "spaceKeyOnTop" to setOf(StyleId.KEYSTROKES, StyleId.TOUCHING),

        /* ── 只属于手柄（标准）样式 —— 全在 KeyLayout.joystickLayoutBoxes 里 ── */
        "showShoulderButtons" to setOf(StyleId.GAMEPAD2),
        "showAButton" to setOf(StyleId.GAMEPAD2),
        "aButtonOnTop" to setOf(StyleId.GAMEPAD2),

        /*
         * ⚠️ **摇杆互换：手柄与触屏共用** —— 用户明确要求触屏保留它。
         *
         * ⚠️ 判据是"有没有两个摇杆"，而那正是 `usesJoystickLayout`
         * （键盘与自定义 Key 没有摇杆，显示这个开关点了也没反应）。
         */
        "swapSticks" to setOf(StyleId.GAMEPAD2, StyleId.TOUCHING),

        /* ── 只属于 Live2D 样式 ── */
        "live2d" to setOf(StyleId.KEYBOARD_CAT),

        /*
         * ============================================================
         * ⚠️⚠️ 下面这一大批**只写给有键位网格的两个样式**
         * ============================================================
         * 自定义 Key 连"窗口里有什么"都是用户在编辑器里摆出来的 ——
         * 所以这些"布局 / 文字 / 外观"字段它**一个都不读**。
         *
         * ⚠️ 这不是新判断，是 [KeyStrokesConfig] 里**早就写好的规格**
         * （见它 `custom` 字段的注释）:
         *
         * > 上面那整套字段（keySize / keyGap / colors / outline / opacity /
         * > cornerRadius / animationMode / showShiftKey / showMouseButtons /
         * > mouseCpsEnabled / keyMappings…）**一个都不适用**……
         * > 唯一仍然共用的是 `scalePercent`（整体缩放）
         *
         * ⚠️ 只是**编码器一直没照做**，于是自定义配置里堆了一大堆
         * 与它无关的字段（用户报的正是这个）。
         *
         * ⚠️ 每一项的"自定义 Key 用什么替代":
         *
         * | 字段 | 自定义 Key 里的对应物 |
         * |---|---|
         * | 文字缩放 / 圆角 / 动画 / 描边 / 阴影 / 透明度 / 字体 | **每个组件自己的 `ComponentStyle`** |
         * | 文字偏移 / 字间距 | **每个组件自己的字段**（`textOffsetX` 等） |
         * | 键高 / 键间距 | 画布尺寸来自**组件包围盒**（`CustomLayout.bounds`） |
         * | CPS 开关与模板 | 由**文本里的 `(cps)` / `(cps2)` 占位符**决定 |
         * | 显示 SHIFT / 鼠标键 | 画布上没有这两个槽位 |
         */
        "textScalePercent" to KEY_GRID_STYLES,
        "cornerRadiusEnabled" to KEY_GRID_STYLES,
        "cornerRadiusPercent" to KEY_GRID_STYLES,
        "animationMode" to KEY_GRID_STYLES,
        "animationDurationSec" to KEY_GRID_STYLES,
        "showShiftKey" to KEY_GRID_STYLES,
        "showMouseButtons" to KEY_GRID_STYLES,
        "keyHeightPercent" to KEY_GRID_STYLES,
        "keyGapPercent" to KEY_GRID_STYLES,
        "mouseCpsEnabled" to KEY_GRID_STYLES,
        "mouseCpsMode" to KEY_GRID_STYLES,
        "cpsTextTemplate" to KEY_GRID_STYLES,
        "cpsTextTemplateMode1" to KEY_GRID_STYLES,
        "bitmapFontId" to KEY_GRID_STYLES,
        "textOffsetX" to KEY_GRID_STYLES,
        "textOffsetY" to KEY_GRID_STYLES,
        "slotTextOffsets" to KEY_GRID_STYLES,
        "textSpacing" to KEY_GRID_STYLES,
        "slotTextSpacings" to KEY_GRID_STYLES,
        "outline" to KEY_GRID_STYLES,
        "shadow" to KEY_GRID_STYLES,
        "opacity" to KEY_GRID_STYLES,

        /* ── 只属于触屏样式 ── */
        "touch" to setOf(StyleId.TOUCHING),

        /* ── 只属于自定义 Key 样式 ── */
        "custom" to setOf(StyleId.CUSTOM_KEY),
        "customOpacityPercent" to setOf(StyleId.CUSTOM_KEY),

        /*
         * ============================================================
         * ⚠️ 这三个**所有样式都写**，虽然看起来"像"某个样式专有
         * ============================================================
         * | 字段 | 为什么留着 |
         * |---|---|
         * | `scalePercent` | 规格里明写"唯一仍然共用的是它" |
         * | `colors` / `fontId` | ⚠️ 它们是**新建组件时的初始值来源** —— `ComponentFactory` 会读它给新组件定初始颜色与字体。删了的话用户新建的按键组件颜色就回默认了 |
         * | `joystick` | 同理:新建摇杆组件时拿它当初始观感 |
         * | `keyMappings` | 自定义 Key 的 CPS 占位符要靠它把键码解析到槽位（`KeyLayout.codeToSlotMap`） |
         */
    )

    /**
     * 这个样式该不该写这个键。
     *
     * ⚠️ 用 `getOrElse` 而不是 `get`:表里没有的键 = **所有样式都写**。
     */
    private fun appliesTo(styleId: String, key: String): Boolean =
        STYLE_SPECIFIC_KEYS[key]?.let { styleId in it } ?: true

    /**
     * 用来推导 [KNOWN_KEYS] 的探针配置：**每个开关都打开**。
     *
     * 为什么不能直接用 `defaultConfig()`：有些字段只在功能开启时才有意义
     * （`shadow` 就是），默认配置里它们走的是"关闭"分支，
     * 于是推导出来的清单会缺掉那些键 —— 又是同一个坑。
     */
    /**
     * 读每个槽位各自的文字偏移。
     *
     * 容错：任何一项坏了就**丢掉那一项**，不影响其它键，也不让整份配置读不出来
     * （与项目里其它解码器的取舍一致）。
     *
     * 值为 0/0 的项会被丢掉：它和"没有这一项"在渲染上完全等价，
     * 留着只会让配置越存越大、也让"这个键调过没有"变得看不出来。
     */
    private fun decodeSlotTextOffsets(json: JSONObject?): Map<String, TextOffset> {
        if (json == null) return emptyMap()
        val result = mutableMapOf<String, TextOffset>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = json.optJSONObject(key) ?: continue
            val x = item.optDouble("x", 0.0).toFloat()
            val y = item.optDouble("y", 0.0).toFloat()
            if (x == 0f && y == 0f) continue
            result[key] = TextOffset(x = x, y = y)
        }
        return result
    }

    /** 读全局的字间距 / 行间距；缺字段时用默认（0 = 不动） */
    private fun decodeTextSpacing(json: JSONObject?): TextSpacing =
        if (json == null) {
            TextSpacing()
        } else {
            TextSpacing(
                letter = json.optDouble("letter", 0.0).toFloat(),
                line = json.optDouble("line", 0.0).toFloat(),
            )
        }

    /**
     * 读每个槽位各自的字间距 / 行间距。
     *
     * 容错与 [decodeSlotTextOffsets] 一致：坏掉的项丢掉，
     * 全零的项也丢掉（它与"没有这一项"在渲染上等价）。
     */
    private fun decodeSlotTextSpacings(json: JSONObject?): Map<String, TextSpacing> {
        if (json == null) return emptyMap()
        val result = mutableMapOf<String, TextSpacing>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = json.optJSONObject(key) ?: continue
            val spacing = TextSpacing(
                letter = item.optDouble("letter", 0.0).toFloat(),
                line = item.optDouble("line", 0.0).toFloat(),
            )
            if (spacing.letter == 0f && spacing.line == 0f) continue
            result[key] = spacing
        }
        return result
    }

    private fun probeConfigForKnownKeys(): KeyStrokesConfig {
        val base = defaultConfig()
        return base.copy(
            outline = base.outline.copy(enabled = true),
            shadow = base.shadow.copy(enabled = true),
            cornerRadiusEnabled = true,
            showShiftKey = true,
            showMouseButtons = true,
            mouseCpsEnabled = true,
            slotTextOffsets = emptyMap(),
            /*
             * ============================================================
             * ⚠️⚠️ `showSpaceKey` **必须显式打开**（踩过一次，很难查）
             * ============================================================
             * 这个键在编码时是**条件写**的 —— 只有"与样式默认值不同"才写
             * （理由见 `encodeForStorage` 里那段）。
             *
             * 而 `defaultConfig()` 是键盘样式、`showSpaceKey` 本来就是 `true`
             * （= 默认），于是编码**跳过它** → [KNOWN_KEYS] 里缺了这个键。
             *
             * 后果不是"少一个名字"那么轻，而是**两次都出错**:
             *
             * | 用到 [KNOWN_KEYS] 的地方 | 出错表现 |
             * |---|---|
             * | 导入报告的 `missing` | 把"本版本认识的字段"报成**未知字段** |
             * | **导出时保留未知字段** | 老配置里带过来的值被当成未知键**原样透传**， 而它与本版本的默认值不一致时就固化下来 |
             *
             * 用户报过:"我把旧配置导入进去再导出来发现有 showSpaceKey
             * 这个配置项，但是是 false 状态，说明你有地方没处理好导致
             * 默认值没有对应到老配置上"。
             *
             * ⚠️ 教训:**凡是"从编码结果反推字段清单"的机制，都必须
             * 考虑到编码本身可能是条件性的** —— 探针要把每个开关都
             * 打开不只是"更保险"，而是**必需**。
             */
            showSpaceKey = true,
            /* 同理，为将来加"条件写"的新字段留个位置 */
            spaceKeyOnTop = true,
        )
    }

    /**
     * [KNOWN_KEYS] 的探针:**每种样式各一份**。
     *
     * ============================================================
     * ⚠️ 为什么不能只探一份
     * ============================================================
     * 编码现在是**按样式门控**的（见 [STYLE_SPECIFIC_KEYS]）:
     * 键盘样式的配置里不会写 `showAButton`、手柄样式的配置里不会写
     * `spaceKeyOnTop`。只探一份的话，另一种样式的专有字段就**不在
     * [KNOWN_KEYS] 里**，于是导入那种配置时会被报成"未识别字段" ——
     * 「我们自己写的字段，我们自己说不认识」那个老毛病又回来了。
     *
     * ⚠️ 所以"探针要覆盖编码的所有分支"这条规则，在引入样式门控之后
     * 就升级成"每种样式都要探"。
     *
     * ⚠️ 三种 **活跃**样式都列上。`gamepad1` 已下线（只剩渲染代码、
     * 注册表里被注释掉），不必为它保字段 —— 真要重新上线，
     * 它的字段就是 `gamepad2` 那一套。
     *
     * ⚠️ **每加一个样式都要往这里加一项** —— 漏了的话那个样式的
     * 专属字段就掉出 [KNOWN_KEYS]，导入时被报成"未识别字段"。
     * `StyleSpecificFieldsTest` 会在门控表出现新样式时报红，提醒这里。
     */
    private fun probeConfigsForKnownKeys(): List<KeyStrokesConfig> =
        listOf(
            StyleId.KEYSTROKES,
            StyleId.GAMEPAD2,
            StyleId.KEYBOARD_CAT,
            StyleId.TOUCHING,
            StyleId.CUSTOM_KEY,
        ).map { styleId ->
            probeConfigForKnownKeys().copy(styleId = styleId)
        }

    /**
     * 字段 → 中文说明。
     *
     * 导入报告里直接展示这个说明，而不是让用户看 `animationDurationSec`
     * 这种键名 —— 他们没理由知道我们的内部命名。
     */
    private val KEY_LABELS: Map<String, String> = mapOf(
        "schemaVersion" to "配置格式版本",
        "name" to "名称",
        "description" to "描述",
        "id" to "配置标识",
        "builtIn" to "内置标记",
        "styleId" to "悬浮窗样式",
        /*
         * ⚠️ `keySize` / `keyGap` 的说明**故意不在这里** ——
         * 这两个字段已经不再写出（见 `encodeForStorage` 里那段），
         * 留着说明只会让人以为它们还在用。
         *
         * ⚠️ 解码端**仍然接受**它们（旧配置里带着）——
         * 那种情况下导入报告会露出内部键名，这是可接受的代价:
         * 它只在读**老配置**时出现，而"不让人以为死字段还活着"更重要。
         */
        "keyHeightPercent" to "按键高度",
        "keyGapPercent" to "按键间距",
        "scalePercent" to "整体缩放",
        "textScalePercent" to "文字缩放",
        "cornerRadiusEnabled" to "圆角开关",
        "cornerRadiusPercent" to "圆角大小",
        "colors" to "颜色",
        "outline" to "描边",
        "shadow" to "文字阴影",
        "opacity" to "透明度",
        "fontId" to "字体",
        "animationMode" to "按键动画",
        "animationDurationSec" to "动画时长",
        "showShiftKey" to "显示 Shift 键",
        "showMouseButtons" to "显示鼠标按键",
        "mouseCpsEnabled" to "显示 CPS",
        "mouseCpsMode" to "CPS 显示模式",
        "cpsTextTemplate" to "CPS 文本（模式 2）",
        "cpsTextTemplateMode1" to "CPS 文本（模式 1）",
        "customOpacityPercent" to "自定义 Key 的整体透明度",
        "keyMappings" to "键位映射",
        "live2d" to "Live2D 设置",
        "custom" to "自定义 Key 布局",
    )

    /* ============================================================
     * 摇杆设置（`JoystickStyle`）
     * ============================================================ */

    /**
     * 把摇杆设置编码成一个 JSON 对象。
     *
     * ⚠️ 单独一个子对象，而不是把十几个字段平铺到顶层 ——
     * 那几个字段是**一组**（都属于摇杆），平铺之后
     * `joystickCornerRatio` 和 `cornerRadiusPercent` 挨在一起，
     * 读 JSON 的人分不清哪个是摇杆的、哪个是按键的。
     *
     * ⚠️ 缺字段时的默认值由 [decodeJoystick] 兜 ——
     * 老配置里没有这个对象，解码时整块用默认值。
     */
    internal fun encodeJoystick(style: JoystickStyle): JSONObject = JSONObject().apply {
        put("sizeScale", style.sizeScale.toDouble())
        put("cornerRatio", style.cornerRatio.toDouble())
        put("opacity", style.opacity.toDouble())
        put("color", style.color)
        put("strokeColor", style.strokeColor)
        put("strokeOpacity", style.strokeOpacity.toDouble())
        put("strokeWidthRatio", style.strokeWidthRatio.toDouble())
        put("ringColor", style.ringColor)
        put("ringOpacity", style.ringOpacity.toDouble())
        put("ringWidthRatio", style.ringWidthRatio.toDouble())
        put("knobScale", style.knobScale.toDouble())
        put("knobColor", style.knobColor)
        put("knobOpacity", style.knobOpacity.toDouble())
        put("knobCornerRatio", style.knobCornerRatio.toDouble())
        put("knobStrokeColor", style.knobStrokeColor)
        put("knobStrokeOpacity", style.knobStrokeOpacity.toDouble())
        put("knobStrokeWidthRatio", style.knobStrokeWidthRatio.toDouble())
        put("deadZone", style.deadZone.toDouble())
        put("sensitivity", style.sensitivity.toDouble())
        put("smoothingMs", style.smoothingMs.toDouble())
    }

    /**
     * 解码摇杆设置。
     *
     * ⚠️ 每一项都**夹到合法范围**，而且范围与 `JoystickStyle` 里的
     * 文档一致 —— 手改过的 JSON 可以塞进 `sensitivity: 999`，
     * 那会让摇杆永远画在边上（而且不报错）。
     *
     * ⚠️ 传入 `null`（老配置没有这个对象）时整块用默认值 ——
     * 这是**向后兼容**的关键:不能因为加了这个对象就让老配置读不出来。
     */
    /**
     * 读取一个摇杆颜色，并把**可能残留的 alpha 清成不透明**。
     *
     * ============================================================
     * ⚠️ 这是一次**数据修复**，不只是容错
     * ============================================================
     * 第一版的默认颜色带了 alpha（`0xB3000000`），而配置页的颜色控件
     * （`HexColorRow`）只处理 RGB —— 用户**滑到"摇杆"那一栏**就会
     * 把 alpha 抹成 `00`，颜色变成 `0x00xxxxxx`、渲染时全透明、
     * **摇杆消失**，而且写进了存档（"除了重新创建一个配置，无法恢复"）。
     *
     * 现在不透明度只由 `opacity` 字段表达，颜色里的 alpha 一律忽略 ——
     * 所以这里把读到的 alpha 强制成 `0xFF`。
     *
     * ⚠️ 这样**已经损坏的存档也能直接恢复**，不需要用户重建配置。
     *
     * ⚠️ 只保留低 24 位（RGB）:高 8 位无论是 `00`（被抹过）还是别的，
     * 都不是用户选的"颜色"，而是历史遗留。
     */
    private fun readJoystickColor(json: JSONObject, key: String, fallback: Int): Int =
        (json.optInt(key, fallback) and 0xFFFFFF) or 0xFF000000.toInt()
    internal fun decodeJoystick(json: JSONObject?): JoystickStyle {
        val d = JoystickStyle()
        if (json == null) return d
        return JoystickStyle(
            sizeScale = json.optDouble("sizeScale", d.sizeScale.toDouble())
                .toFloat().coerceIn(0.3f, 3f),
            cornerRatio = json.optDouble("cornerRatio", d.cornerRatio.toDouble())
                .toFloat().coerceIn(0f, 0.5f),
            opacity = json.optDouble("opacity", d.opacity.toDouble())
                .toFloat().coerceIn(0f, 1f),
            color = readJoystickColor(json, "color", d.color),
            strokeColor = readJoystickColor(json, "strokeColor", d.strokeColor),
            strokeOpacity = json.optDouble("strokeOpacity", d.strokeOpacity.toDouble())
                .toFloat().coerceIn(0f, 1f),
            strokeWidthRatio = json.optDouble("strokeWidthRatio", d.strokeWidthRatio.toDouble())
                .toFloat().coerceIn(0f, JoystickStyle.MAX_WIDTH_RATIO),
            ringColor = readJoystickColor(json, "ringColor", d.ringColor),
            ringOpacity = json.optDouble("ringOpacity", d.ringOpacity.toDouble())
                .toFloat().coerceIn(0f, 1f),
            ringWidthRatio = json.optDouble("ringWidthRatio", d.ringWidthRatio.toDouble())
                .toFloat().coerceIn(0f, JoystickStyle.MAX_WIDTH_RATIO),
            knobScale = json.optDouble("knobScale", d.knobScale.toDouble())
                .toFloat().coerceIn(0.2f, 2f),
            knobColor = readJoystickColor(json, "knobColor", d.knobColor),
            knobOpacity = json.optDouble("knobOpacity", d.knobOpacity.toDouble())
                .toFloat().coerceIn(0f, 1f),
            knobCornerRatio = json.optDouble("knobCornerRatio", d.knobCornerRatio.toDouble())
                .toFloat().coerceIn(0f, 0.5f),
            knobStrokeColor = readJoystickColor(json, "knobStrokeColor", d.knobStrokeColor),
            knobStrokeOpacity = json.optDouble("knobStrokeOpacity", d.knobStrokeOpacity.toDouble())
                .toFloat().coerceIn(0f, 1f),
            knobStrokeWidthRatio = json.optDouble(
                "knobStrokeWidthRatio",
                d.knobStrokeWidthRatio.toDouble(),
            ).toFloat().coerceIn(0f, JoystickStyle.MAX_WIDTH_RATIO),
            deadZone = json.optDouble("deadZone", d.deadZone.toDouble())
                .toFloat().coerceIn(0f, 0.5f),
            sensitivity = json.optDouble("sensitivity", d.sensitivity.toDouble())
                .toFloat().coerceIn(0.2f, 3f),
            smoothingMs = json.optDouble("smoothingMs", d.smoothingMs.toDouble())
                .toFloat().coerceIn(0f, 300f),
        )
    }
    /** 取字段的中文说明；没有登记时回落到键名本身 */
    fun labelOf(key: String): String = KEY_LABELS[key] ?: key

    /**
     * 说明表里登记的全部键名。
     *
     * 给"说明表不该留着已经不导出的字段"那条测试用 ——
     * 那个反向检查能发现"删了字段却忘了删说明"。
     */
    fun allLabeledKeys(): Set<String> = KEY_LABELS.keys

    /** 这个字段名是否为本版本认识的 */
    fun isKnownKey(key: String): Boolean = key in KNOWN_KEYS

    /*
     * ============================================================
     * 写
     * ============================================================
     */

    fun encodeList(configs: List<KeyStrokesConfig>): String {
        val array = JSONArray()
        configs.forEach { array.put(encodeForStorage(it)) }
        return array.toString()
    }

    /**
     * 编码为**配置包参数**：写进 .sthkey 的 params.json。
     *
     * 去掉 name / description —— 这两个字段在配置包里的唯一出处是
     * manifest.json。两处都写就会出现两个真源：改了一个忘了另一个，
     * 导入时按哪个算？既然 manifest 一定在、且信息更全（还带 appVersion、
     * 导出时间），params 里那份就纯属误导，直接不写。
     */
    fun encode(config: KeyStrokesConfig): JSONObject =
        encodeForStorage(config).apply {
            remove("name")
            remove("description")
        }

    /**
     * 编码为**完整对象**：应用内部存储用。
     *
     * 包含名称与描述 —— 本地存储里它们是配置的一部分。
     */
    fun encodeForStorage(config: KeyStrokesConfig): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("id", config.id)
        put("name", config.name)
        put("description", config.description)
        put("builtIn", config.builtIn)
        put("styleId", config.styleId)
        /*
         * ============================================================
         * ⚠️⚠️ `keySize` / `keyGap` **不再写**（它们对所有样式都是死字段）
         * ============================================================
         * 这两个字段被**声明、被往返、被"恢复默认参数"复制**，
         * 但**没有任何布局代码读它们**:
         *
         * - 真实键宽是常量 `KeyLayout.KEY_SIZE`；
         * - 真实间距是常量 `KeyLayout.GAP × keyGapPercent / 100`。
         *
         * ⚠️ 项目自己的注释早就写明了这件事 —— 见 `KeyToCustomConverter`
         * 开头那段:"配置里的 `keySize` / `keyGap` 两个字段其实没有被布局使用"。
         * 只是编码器一直还在往外写。
         *
         * ⚠️ 读的那一侧**保留**（`decode` 仍然接受它们）——
         * 旧配置里带着这两个键、删了会变成"未识别字段"。
         * 写的时候不写、读的时候认得，是最省事且不丢数据的方向。
         */
        put("scalePercent", config.scalePercent)
        if (appliesTo(config.styleId, "textScalePercent")) {
            put("textScalePercent", config.textScalePercent)
            put("cornerRadiusEnabled", config.cornerRadiusEnabled)
            put("cornerRadiusPercent", config.cornerRadiusPercent.toDouble())
            put("animationMode", config.animationMode.id)
            put("animationDurationSec", config.animationDurationSec.toDouble())
            put("showShiftKey", config.showShiftKey)
            put("showMouseButtons", config.showMouseButtons)
        }
        /*
         * ============================================================
         * ⚠️ 下面这几个**只写给用得到的样式**（见 [STYLE_SPECIFIC_KEYS]）
         * ============================================================
         * `showShoulderButtons` / `showAButton` / `aButtonOnTop` / `swapSticks`
         * 只有手柄（标准）样式会读（全在 `KeyLayout.joystickLayoutBoxes` 里），
         * `showSpaceKey` / `spaceKeyOnTop` 只有键盘样式会读
         * （`KeyLayout.keys` 的键盘分支）。
         *
         * ⚠️ `showSpaceKey` 那段的"必须无条件写"依然成立 ——
         * 这里只是**对它适用的样式**无条件写，与"要不要按样式门控"
         * 是两件事（那段讲的是"不能因为等于默认值就不写"）。
         */
        if (appliesTo(config.styleId, "showShoulderButtons")) {
            put("showShoulderButtons", config.showShoulderButtons)
        }
        if (appliesTo(config.styleId, "showAButton")) {
            put("showAButton", config.showAButton)
            put("aButtonOnTop", config.aButtonOnTop)
        }
        /*
         * ============================================================
         * ⚠️ `showSpaceKey` **无条件写**（试过条件写，翻车了）
         * ============================================================
         * 曾经改成"只在与样式默认值不同时才写"，想省掉冗余的键。
         * 结果用户立刻报:**"space 关闭状态的配置持久化没了，关闭之后把软件
         * 杀掉重进，space 键又被打开了"** —— 因为不写 = 下次读回来只能靠
         * 默认值 = 用户的"关掉"被当成"没设过"。
         *
         * ⚠️ 所以它必须**无条件写**，与项目里其它所有开关一致。
         * 读取那侧的分支说明见 `decode` 里 `showSpaceKey` 那段。
         *
         * ⚠️ 顺带:这里写的是 `config.showSpaceKey` 本身，不做任何判断 ——
         * 判断一多就会有人（包括我自己）在解码那边试图"反推意图"，
         * 而那种反推必然吃掉用户的真实选择。
         *
         * ⚠️ 它**只写给键盘样式**（`spaceKeyOnTop` 同理）——
         * gamepad2 那边管行顺序的是 `aButtonOnTop`，两个字段互不相干。
         */
        if (appliesTo(config.styleId, "showSpaceKey")) {
            put("showSpaceKey", config.showSpaceKey)
            put("spaceKeyOnTop", config.spaceKeyOnTop)
        }
        /* ⚠️ 原来这里还有一行重复的 `put("spaceKeyOnTop", ...)`，已删 */
        if (appliesTo(config.styleId, "swapSticks")) {
            put("swapSticks", config.swapSticks)
        }
        if (appliesTo(config.styleId, "keyHeightPercent")) {
            put("keyHeightPercent", config.keyHeightPercent)
            put("keyGapPercent", config.keyGapPercent)
        }
        /*
         * ⚠️ `joystick` **所有样式都写** —— 它不只是"手柄的观感"，
         * 还是**新建摇杆组件时的初始观感**（见 `ComponentFactory`）。
         * 自定义 Key 里加一个摇杆组件时读的就是它。
         */
        put("joystick", encodeJoystick(config.joystick))
        if (appliesTo(config.styleId, "mouseCpsEnabled")) {
            put("mouseCpsEnabled", config.mouseCpsEnabled)
            put("mouseCpsMode", config.mouseCpsMode)
            put("cpsTextTemplate", config.cpsTextTemplate)
            put("cpsTextTemplateMode1", config.cpsTextTemplateMode1)
        }

        put("colors", JSONObject().apply {
            put("keyUp", config.colors.keyUp)
            put("keyDown", config.colors.keyDown)
            put("textUp", config.colors.textUp)
            put("textDown", config.colors.textDown)
            // 描边与阴影各按状态存一份（老版本只有一个描边色，见 decodeColors 的迁移）
            put("outlineUp", config.colors.outlineUp)
            put("outlineDown", config.colors.outlineDown)
            put("shadowUp", config.colors.shadowUp)
            put("shadowDown", config.colors.shadowDown)
        })

        // 字体标识：不存的话重启后会回落到默认字体
        put("fontId", config.fontId)
        if (appliesTo(config.styleId, "bitmapFontId")) {
            put("bitmapFontId", config.bitmapFontId)
        }

        /*
         * 文字偏移 / 每槽位偏移 / 字间距 —— 与上面同一类，
         * 只有**有键位网格的两个样式**会读（自定义 Key 是每组件各一套）。
         */
        if (appliesTo(config.styleId, "textOffsetX")) {
            // 文字偏移：图片字体常需要按图集微调，不存的话重启就白调了
            put("textOffsetX", config.textOffsetX.toDouble())
            put("textOffsetY", config.textOffsetY.toDouble())
        }

        /*
         * 每个槽位各自的偏移。
         *
         * 存成对象（`{ "SPACE": {"x":0,"y":3} }`）而不是两个扁平数组：
         * 数组要靠下标与槽位列表对应，布局一变就会错位；
         * 用槽位 id 做键则天然稳定，也不必保证"两个数组一样长"。
         *
         * ⚠️ **在适用的样式里无条件写**，哪怕为空。
         *
         * 早先写的是"非空才写"，结果有两处不对：
         * 1. 读的时候无条件读、写的时候却不写 —— 编解码本身就是不对称的；
         * 2. [KNOWN_KEYS] 是从"把探针配置编码一遍"推导出来的，于是这个键
         *    不在已知清单里，**带它的配置包导入时会被报成"未识别字段"**
         *    （用户实测遇到的就是这个）。
         *
         * 一个空对象（`{}`）的开销可以忽略，换来的是"读写对称 + 键始终已注册"。
         */
        if (appliesTo(config.styleId, "slotTextOffsets")) {
            put(
                "slotTextOffsets",
                JSONObject().apply {
                    config.slotTextOffsets.forEach { (slotId, offset) ->
                        put(
                            slotId,
                            JSONObject().apply {
                                put("x", offset.x.toDouble())
                                put("y", offset.y.toDouble())
                            },
                        )
                    }
                },
            )
        }

        /*
         * 字间距 / 行间距：与文字偏移**同一套结构**（全局 + 每槽位）。
         *
         * 同样在适用样式里**无条件写** —— 理由与上面 `slotTextOffsets` 完全一样
         * （读写对称 + 键始终在 KNOWN_KEYS 里）。
         */
        if (appliesTo(config.styleId, "textSpacing")) {
            put(
                "textSpacing",
                JSONObject().apply {
                    put("letter", config.textSpacing.letter.toDouble())
                    put("line", config.textSpacing.line.toDouble())
                },
            )
        }
        if (appliesTo(config.styleId, "textSpacing")) {
            put(
                "slotTextSpacings",
                JSONObject().apply {
                    config.slotTextSpacings.forEach { (slotId, spacing) ->
                        put(
                            slotId,
                            JSONObject().apply {
                                put("letter", spacing.letter.toDouble())
                                put("line", spacing.line.toDouble())
                            },
                        )
                    }
                },
            )
        }

        /* ⚠️ 描边 / 阴影 / 透明度都是"键帽级"的外观 —— 只有键位网格样式读它们 */
        if (appliesTo(config.styleId, "outline")) {
            put(
                "outline",
                JSONObject().apply {
                    put("enabled", config.outline.enabled)
                    put("width", config.outline.width.toDouble())
                    /*
                     * ⚠️ 颜色**不写在这里了**（已搬到 colors.outlineUp/outlineDown）。
                     * 老版本读的是这个字段，所以导出时不再写它 ——
                     * 新导入的包一律走 colors 那一套，兼容分支只对真正的老数据生效。
                     */
                },
            )
        }

        if (appliesTo(config.styleId, "shadow")) {
            put(
                "shadow",
                JSONObject().apply {
                    put("enabled", config.shadow.enabled)
                    put("mode", config.shadow.mode.id)
                    put("size", config.shadow.size.toDouble())
                },
            )
        }

        if (appliesTo(config.styleId, "opacity")) {
            put(
                "opacity",
                JSONObject().apply {
                    // 八个值：四个元素 × 按下/未按下
                    put("keyUp", config.opacity.keyUp)
                    put("keyDown", config.opacity.keyDown)
                    put("textUp", config.opacity.textUp)
                    put("textDown", config.opacity.textDown)
                    put("outlineUp", config.opacity.outlineUp)
                    put("outlineDown", config.opacity.outlineDown)
                    put("shadowUp", config.opacity.shadowUp)
                    put("shadowDown", config.opacity.shadowDown)
                },
            )
        }

        put(
            "keyMappings",
            JSONArray().apply {
                config.keyMappings.forEach { mapping ->
                    put(
                        JSONObject().apply {
                            put("id", mapping.id)
                            put("displayText", mapping.displayText)
                            put(
                                "inputKeyCodes",
                                JSONArray().apply {
                                    mapping.inputKeyCodes.forEach { put(it) }
                                },
                            )
                        },
                    )
                }
            },
        )

        /*
         * Live2D 设置单独一块。
         *
         * ⚠️ 只写给 **Live2D 样式**。
         *
         * ⚠️ 原来这里写的是"按键样式用不到它，但照样写出去 ——
         * 用户可能随时把样式切到 Live2D"。**那个理由不成立**:
         * 样式创建后不能切换（见 [STYLE_SPECIFIC_KEYS] 那段）。
         * 一份键盘配置里出现 `live2d` 只是与它无关的字段。
         */
        if (appliesTo(config.styleId, "live2d")) {
            put(
                "live2d",
                JSONObject().apply {
                    put("modelId", config.live2d.modelId)
                    put("opacityPercent", config.live2d.opacityPercent)
                },
            )
        }

        /* ⚠️ 自定义 Key 布局：只写给自定义样式（理由同上） */
        if (appliesTo(config.styleId, "custom")) {
            put("custom", CustomLayoutCodec.encode(config.custom))
        }

        /*
         * 自定义 Key 的**整体透明度**。
         *
         * ⚠️ 同样只写给自定义样式。原来写的是"换样式时不该把它丢掉
         * （用户可能只是临时切走看一眼，再切回来）" —— 但**切不了**。
         */
        if (appliesTo(config.styleId, "customOpacityPercent")) {
            put("customOpacityPercent", config.customOpacityPercent)
        }

        /*
         * ⚠️ 触屏设置 —— **只写给触屏样式**。
         *
         * 它里面装着两个摇杆的缩放与手感、以及移动轮盘的触摸区域，
         * 别的样式一个都不读（见 [TouchSettings] 的说明）。
         */
        if (appliesTo(config.styleId, "touch")) {
            put("touch", encodeTouch(config.touch))
        }
    }

    /*
     * ============================================================
     * 读
     * ============================================================
     */

    /** 解析失败时返回空列表，由调用方决定兜底 */
    fun decodeList(raw: String?): List<KeyStrokesConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(decode(it)) }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun decode(json: JSONObject): KeyStrokesConfig {
        val defaults = defaultConfig()

        return KeyStrokesConfig(
            id = json.optString("id").ifBlank { defaults.id },
            name = json.optString("name").ifBlank { defaults.name },
            description = json.optString("description", ""),
            builtIn = json.optBoolean("builtIn", false),
            // 未知或未实现的样式回落到默认样式，避免配置损坏导致界面空白
            styleId = OverlayStyleRegistry.resolveOrDefault(
                json.optString("styleId", OverlayStyleRegistry.defaultStyleId),
            ).id,
            keySize = json.optDouble("keySize", defaults.keySize.toDouble())
                .toFloat()
                .coerceIn(20f, 200f),
            keyGap = json.optDouble("keyGap", defaults.keyGap.toDouble())
                .toFloat()
                .coerceIn(0f, 60f),
                        /*
             * ⚠️ 范围**引用 `KeyLayout` 的常量**，不要写字面量。
             *
             * 写死 `coerceIn(50, 200)` 的话，改滑块下限时很容易漏掉这里，
             * 症状是"我调的缩放存不住"：调到 20 保存、再打开就变回 50。
             * 编译期完全看不出来。
             */
            scalePercent = json.optInt("scalePercent", defaults.scalePercent)
                .coerceIn(KeyLayout.SCALE_PERCENT_MIN, KeyLayout.SCALE_PERCENT_MAX),
            textScalePercent = json.optInt("textScalePercent", defaults.textScalePercent)
                .coerceIn(50, 150),
            cornerRadiusEnabled = json.optBoolean("cornerRadiusEnabled", false),
            cornerRadiusPercent = json.optDouble("cornerRadiusPercent", 0.0)
                .toFloat()
                .coerceIn(0f, 50f),
            /*
             * ⚠️ 描边色的迁移需要**同时看到 colors 与 outline 两个对象**：
             * 颜色原本住在 outline 里，现在要搬进 colors。
             * 所以先把老值取出来，再交给 decodeColors。
             */
            colors = decodeColors(
                json = json.optJSONObject("colors"),
                legacyOutlineColor = readLegacyOutlineColor(json),
            ),
            // 字体 id：老配置没有这个字段时回落到默认字体
            fontId = json.optString("fontId", DEFAULT_FONT_ID).ifBlank { DEFAULT_FONT_ID },
            bitmapFontId = json.optString("bitmapFontId", ""),
            textOffsetX = json.optDouble("textOffsetX", 0.0).toFloat(),
            textOffsetY = json.optDouble("textOffsetY", 0.0).toFloat(),
            slotTextOffsets = decodeSlotTextOffsets(json.optJSONObject("slotTextOffsets")),
            textSpacing = decodeTextSpacing(json.optJSONObject("textSpacing")),
            slotTextSpacings = decodeSlotTextSpacings(json.optJSONObject("slotTextSpacings")),
            outline = decodeOutline(json.optJSONObject("outline")),
            shadow = decodeShadow(json.optJSONObject("shadow")),
            opacity = decodeOpacity(json.optJSONObject("opacity")),
            /*
             * 动画模式。
             *
             * 兼容旧数据：早期版本存的是布尔 `animationEnabled`，
             * 这里做一次转换（true → 颜色渐变，false → 无动画），
             * 否则老配置读出来会莫名其妙变成"无动画"。
             */
            animationMode = if (json.has("animationMode")) {
                AnimationMode.fromId(json.optString("animationMode"))
            } else {
                if (json.optBoolean("animationEnabled", true)) {
                    AnimationMode.FADE
                } else {
                    AnimationMode.NONE
                }
            },
            animationDurationSec = json.optDouble("animationDurationSec", 0.1)
                .toFloat()
                .coerceIn(ANIMATION_DURATION_MIN, ANIMATION_DURATION_MAX),
            showShiftKey = json.optBoolean("showShiftKey", false),
            showMouseButtons = json.optBoolean("showMouseButtons", true),
            showShoulderButtons = json.optBoolean("showShoulderButtons", false),
            /*
             * ============================================================
             * ⚠️ `showAButton` 用**朴素缺省**就够了 —— 不需要按样式判断
             * ============================================================
             * ⚠️ 我上一版给它加了一段"按样式取缺省"的**特例处理**，
             * 理由是"触屏不写这个字段 → 解码回 `true` → A 键行冒出来"。
             *
             * ⚠️ 但用户指出了更根本的修法:
             *
             * > 你应该**直接在触屏样式的配置字段直接去掉 showAButton** 吧。。。
             * > 不然不是字段冗余了吗
             *
             * ⚠️ 对 —— 于是 `KeyLayout.joystickLayoutBoxes` 里加了
             * `usesAButton`（**只有手柄读它**），触屏压根不看这个字段。
             * 既然没人读，它存 `true` 还是 `false` 都无所谓，
             * 那段特例自然就**没有存在的必要**了（相关函数也一并删掉）。
             *
             * ⚠️ 这就是"补丁"与"根治"的区别:补丁要维护两条必须一致的逻辑，
             * 根治只需要**一个读它的地方都不留**。
             */
            showAButton = json.optBoolean("showAButton", true),
            aButtonOnTop = json.optBoolean("aButtonOnTop", false),
            /*
             * ============================================================
             * ⚠️ `showSpaceKey`:最朴素的两行 —— 存了就用存的，没存就给样式默认
             * ============================================================
             * 这个字段在两种样式里语义不同（键盘 = 显不显示空格那一行；
             * gamepad2 = 显不显示 SPACE 槽位，与 `showAButton` 同位置），
             * 所以**缺省值按样式给** —— 这是它唯一特殊的地方。
             *
             * ⚠️ **不要再加任何"判断这个值是不是坏数据"的分支。**
             * 试过两个方向，都翻车了:
             *
             * | 写法 | 后果 |
             * |---|---|
             * | 缺省固定 `false` | 键盘老配置的空格**消失** |
             * | 缺省按样式 + "等于另一样式默认值就当成误写" | 用户关掉空格后**重启又自己开了** |
             *
             * ⚠️ 根因是**这两件事在数据上完全一样**:
             *
             * ```
             * 键盘配置里存着 false
             *   ├─ 用户真的关掉了         → 必须保留
             *   └─ 某版本误写的默认值     → 想修
             * ```
             *
             * 数据里没有任何信息能区分它们，所以**任何"猜"的规则都必然
             * 吃掉其中一种**。而"吃掉用户的选择"是更严重的那种错误
             * （重启后设置自己变了，用户只会以为软件有毛病）。
             *
             * ⚠️ 结论:**存储的值就是用户的意图**。要修历史数据只能用
             * 一次性的迁移（带持久化标记），绝不能放在读取路径上。
             * 本次决定不做迁移 —— 老配置里那个 `false` 用户自己开一次即可。
             */
            showSpaceKey = if (json.has("showSpaceKey")) {
                json.optBoolean("showSpaceKey", false)
            } else {
                /*
                 * ⚠️ 老配置（v2.6.0 之前没有这个开关）:键盘样式那时候
                 * 空格是**恒定显示**的，所以补 `true` 才是"保持原样"；
                 * gamepad2 那时候根本没有 SPACE 槽位，补 `false`。
                 */
                defaultShowSpaceKey(
                    OverlayStyleRegistry.resolveOrDefault(
                        json.optString("styleId", OverlayStyleRegistry.defaultStyleId),
                    ).id,
                )
            },
            spaceKeyOnTop = json.optBoolean("spaceKeyOnTop", false),
            swapSticks = json.optBoolean("swapSticks", false),
            /*
             * ⚠️⚠️ 这两个 `coerceIn` 的范围**必须与滑块给得出一致**。
             *
             * 用户报过:"按键间距配置没有持久化，我把软件杀一下重进就恢复默认值了"。
             *
             * 成因:滑块下限早就改成了 **0**（`KEY_GAP_PERCENT_MIN`），
             * 而这里还是旧的字面量 `50` —— 于是用户设成 0~49 的值
             * **存进去了、读回时被夹回 50**，看起来就是"恢复默认值"。
             *
             * ⚠️ 现在**引用同一批常量**，两边不可能再漂
             * （它们是 `internal`，设置页与布局层共用一份）。
             */
            keyHeightPercent = json.optInt("keyHeightPercent", 100)
                .coerceIn(
                    KeyLayout.KEY_HEIGHT_PERCENT_MIN,
                    KeyLayout.KEY_HEIGHT_PERCENT_MAX,
                ),
            keyGapPercent = json.optInt("keyGapPercent", 100)
                .coerceIn(
                    KeyLayout.KEY_GAP_PERCENT_MIN,
                    KeyLayout.KEY_GAP_PERCENT_MAX,
                ),
            joystick = decodeJoystick(json.optJSONObject("joystick")),
            mouseCpsEnabled = json.optBoolean("mouseCpsEnabled", false),
            /*
             * ⚠️ **模式 2（独立 CPS 组件）已删除** —— 老配置里存着 2 的
             * 要迁移成 3（键内两行）。
             *
             * 不迁移的话它落在**空模式**上:布局不生成任何 CPS 内容，
             * 表现是"CPS 打开了但什么都不显示" —— 而用户会以为是开关坏了。
             *
             * ⚠️ 界面上现在只有"模式 1 / 模式 2"两个按钮，但**内部编号
             * 仍是 1 与 3** —— 改编号会让所有老配置静默变掉。
             */
            mouseCpsMode = json.optInt("mouseCpsMode", 1).let {
                when {
                    it == 2 -> 3
                    else -> it.coerceIn(1, 3)
                }
            },
            cpsTextTemplate = json.optString("cpsTextTemplate", DEFAULT_CPS_TEMPLATE)
                .ifBlank { DEFAULT_CPS_TEMPLATE },
            cpsTextTemplateMode1 = json.optString(
                "cpsTextTemplateMode1",
                DEFAULT_CPS_TEMPLATE_MODE1,
            ).ifBlank { DEFAULT_CPS_TEMPLATE_MODE1 },
            keyMappings = decodeKeyMappings(json.optJSONArray("keyMappings")),
            live2d = decodeLive2D(json.optJSONObject("live2d")),
            /*
             * 自定义 Key 布局。
             *
             * 老配置（2.2.0 及以前）没有这一段 → 解码器回落到默认布局
             * （一个 Q 键 + 一个 CPS 文本），于是"新建一份自定义 Key 配置"
             * 与"老配置被切到自定义 Key"看到的是同一个起点。
             */
            custom = CustomLayoutCodec.decode(json.optJSONObject("custom")),

            /*
             * ⚠️ `coerceIn(0, 100)` —— 手改过的 JSON 可能塞进 500 或 -20，
             * 不夹的话 alpha 会越界（>1 在某些设备上表现为整块不画）。
             */
            customOpacityPercent = json.optInt("customOpacityPercent", 100).coerceIn(0, 100),
            touch = decodeTouch(json.optJSONObject("touch")),
        )
    }

    /**
     * 编码**触屏设置**。
     *
     * ⚠️ 每个数值都写**它自己那一份**，不做"与默认值相同就省掉"的优化:
     * 省掉的话读回来只能靠默认值，而用户"特意调成默认值"与"没设过"
     * 就分不出来了（`showSpaceKey` 那件事就是这么翻车的）。
     *
     * ⚠️ `regions` 是**列表**（每块区域绑一个槽位）:
     * 空列表 = 用户还没配过（打开悬浮窗要弹提示）。
     * 所以这里**不能**在空的时候写一个"默认区域"进去 ——
     * 那会让"没配过"变成"配在了左下角"。
     */
    private fun encodeTouch(touch: TouchSettings): JSONObject = JSONObject().apply {
        put(
            "move",
            JSONObject().apply {
                put("sizeScale", touch.move.sizeScale.toDouble())
                put("feel", encodeTouchFeel(touch.move.feel))
            },
        )
        put(
            "camera",
            JSONObject().apply {
                put("sizeScale", touch.camera.sizeScale.toDouble())
                put("feel", encodeTouchFeel(touch.camera.feel))
            },
        )
        /* ⚠️ 只在真有区域时写这个键 —— 见上面那段说明 */
        if (touch.regions.isNotEmpty()) {
            put(
                "regions",
                JSONArray().apply {
                    touch.regions.forEach { region ->
                        put(
                            JSONObject().apply {
                                put("slotId", region.slotId)
                                put("centerX", region.centerX.toDouble())
                                put("centerY", region.centerY.toDouble())
                                put("size", region.size.toDouble())
                                /*
                                 * ⚠️ `pinned` **必须写** —— 它决定这块区域生不生效。
                                 *
                                 * ⚠️ 不写的话读回来一律是 `false`，于是
                                 * **所有区域都失效、触屏彻底没反应**（一个很隐蔽的失能）。
                                 *
                                 * ⚠️ 而解码那边对"键不存在"有兼容处理（按 `true` 算），
                                 * 见 [decodeTouch] —— 两者必须配对看。
                                 */
                                put("pinned", region.pinned)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun encodeTouchFeel(feel: TouchStickFeel): JSONObject = JSONObject().apply {
        put("deadZone", feel.deadZone.toDouble())
        put("sensitivity", feel.sensitivity.toDouble())
        put("smoothingMs", feel.smoothingMs.toDouble())
    }

    /**
     * 读**触屏设置**。
     *
     * ⚠️ 每一处都 `coerceIn`:手改过的 JSON 里什么都可能有，
     * 而越界的死区/灵敏度会让摇杆**永远不动**或**瞬间满偏** ——
     * 那种表现很难让人联想到"某个数超了"。
     */
    private fun decodeTouch(json: JSONObject?): TouchSettings {
        val defaults = TouchSettings()
        if (json == null) return defaults

        fun decodeStick(
            obj: JSONObject?,
            fallback: TouchStickSettings,
        ): TouchStickSettings {
            if (obj == null) return fallback
            val feelObj = obj.optJSONObject("feel")
            val feel = if (feelObj == null) {
                fallback.feel
            } else {
                TouchFeelSpec.clamp(
                    TouchStickFeel(
                        deadZone = feelObj.optDouble("deadZone", fallback.feel.deadZone.toDouble())
                            .toFloat(),
                        sensitivity = feelObj.optDouble(
                            "sensitivity",
                            fallback.feel.sensitivity.toDouble(),
                        ).toFloat(),
                        smoothingMs = feelObj.optDouble(
                            "smoothingMs",
                            fallback.feel.smoothingMs.toDouble(),
                        ).toFloat(),
                    ),
                )
            }
            return TouchStickSettings(
                sizeScale = TouchFeelSpec.clampScale(
                    obj.optDouble("sizeScale", fallback.sizeScale.toDouble()).toFloat(),
                ),
                feel = feel,
            )
        }

        /*
         * ⚠️ `regions` **只有 JSON 里真的有这个键时**才给内容 ——
         * 不能 `?: 默认区域`（那会把"没配过"变成"配在左下角"，
         * 于是用户再也看不到那句提示）。
         *
         * ⚠️ 逐项 `clamp` 并且**丢掉坏条目**（没有 slotId 的、坐标不是数的）——
         * 手改过的 JSON 里什么都可能有，而一块坐标是 NaN 的区域
         * 会让命中测试永远失败（表现是"这块区域按了没反应"）。
         */
        val regionsArray = json.optJSONArray("regions")
        val regions = buildList {
            if (regionsArray != null) {
                for (i in 0 until regionsArray.length()) {
                    val obj = regionsArray.optJSONObject(i) ?: continue
                    val slotId = obj.optString("slotId")
                    if (slotId.isBlank()) continue
                    add(
                        TouchRegionSpec.clamp(
                            TouchRegionBinding(
                                slotId = slotId,
                                centerX = obj.optDouble("centerX", 0.5).toFloat(),
                                centerY = obj.optDouble("centerY", 0.5).toFloat(),
                                size = obj.optDouble("size", TouchRegionSpec.SIZE_DEFAULT.toDouble())
                                    .toFloat(),
                                /*
                                 * ============================================================
                                 * ⚠️⚠️ **缺省按 `true`**（与 `pinned` 字段声明的默认值相反）
                                 * ============================================================
                                 * 这是个**刻意的兼容处理**，理由:
                                 *
                                 * | 存档来源 | 有 `pinned` 键吗 | 该怎么办 |
                                 * |---|---|---|
                                 * | 旧版本写的 | ❌ 没有 | 那时**没有占位条目这回事**，写进去的都是用户真配过的 → 按 `true` |
                                 * | 新版本写的 | ✅ 有 | 按它自己的值 |
                                 *
                                 * ⚠️ 按 `false` 的话，**所有老存档的触屏区域会一起失效** ——
                                 * 用户升级后触屏突然不能用，而他什么都没改。
                                 *
                                 * ⚠️ 这正是 `pinned` 字段声明里那句"默认 false"要小心的地方:
                                 * 那个默认值是给**新建**用的，不是给**读存档**用的。
                                 */
                                pinned = if (obj.has("pinned")) obj.optBoolean("pinned", true) else true,
                            ),
                        ),
                    )
                }
            }
        }

        return TouchSettings(
            move = decodeStick(json.optJSONObject("move"), defaults.move),
            camera = decodeStick(json.optJSONObject("camera"), defaults.camera),
            regions = regions,
        )
    }

    /**
     * Live2D 设置。
     *
     * 缺字段（老配置、按键样式的配置）一律用默认值 ——
     * 它只在 Live2D 样式下被读取，对按键样式完全无影响。
     */
    private fun decodeLive2D(json: JSONObject?): Live2DSettings {
        val defaults = Live2DSettings()
        if (json == null) return defaults
        return Live2DSettings(
            /*
             * 模型 id；老配置里存的是布尔 `mouseMode`，这里做一次迁移：
             * true（右侧鼠标）→ 内置鼠标版；false（右侧方向键）→ 保持默认（内置键盘版）。
             */
            modelId = json.optString("modelId").ifBlank {
                if (json.optBoolean("mouseMode", false)) {
                    LIVE2D_MODEL_STANDARD
                } else {
                    defaults.modelId
                }
            },
            /*
             * 下限 20 与编辑页的滑块一致。
             *
             * 允许 0 的话，用户（或手工改过的配置包）能把猫调成完全透明 ——
             * 表现是"悬浮窗开着，屏幕上什么都没有"，很难判断是坏了还是设成这样。
             */
            opacityPercent = json.optInt("opacityPercent", defaults.opacityPercent)
                .coerceIn(20, 100),
        )
    }

    /*
     * ============================================================
     * 外观字段：全部委托给 [AppearanceCodec]
     * ============================================================
     * 颜色 / 透明度 / 阴影（含老数据迁移）的规则都在那个纯 Kotlin 文件里，
     * 因为它必须能被单元测试直接调 —— 这里的 `org.json` 在本地测试里是空壳。
     *
     * 下面这些只是把 `JSONObject` 适配成 [AppearanceCodec.Reader]，
     * **不要再往这里搬业务规则**，否则测试又覆盖不到了。
     */

    /** 把 `JSONObject` 适配成 [AppearanceCodec.Reader] */
    private class JsonReader(private val json: JSONObject) : AppearanceCodec.Reader {
        override fun objectOrNull(key: String): AppearanceCodec.Reader? =
            json.optJSONObject(key)?.let { JsonReader(it) }

        override fun has(key: String): Boolean = json.has(key)

        override fun long(key: String, fallback: Long): Long =
            if (json.has(key)) json.optLong(key, fallback) else fallback

        override fun int(key: String, fallback: Int): Int =
            if (json.has(key)) json.optInt(key, fallback) else fallback

        override fun boolean(key: String, fallback: Boolean): Boolean =
            if (json.has(key)) json.optBoolean(key, fallback) else fallback

        override fun float(key: String, fallback: Float): Float =
            if (json.has(key)) json.optDouble(key, fallback.toDouble()).toFloat() else fallback

        override fun string(key: String, fallback: String): String =
            if (json.has(key)) json.optString(key, fallback) else fallback

        override fun isNull(key: String): Boolean = json.isNull(key)
    }

    private fun JSONObject?.asReader(): AppearanceCodec.Reader? = this?.let { JsonReader(it) }

    private fun decodeColors(json: JSONObject?, legacyOutlineColor: Long?): ColorSet =
        AppearanceCodec.colors(json.asReader(), legacyOutlineColor)

    private fun readLegacyOutlineColor(json: JSONObject?): Long? =
        AppearanceCodec.legacyOutlineColor(json.asReader())

    private fun decodeShadow(json: JSONObject?): TextShadow =
        AppearanceCodec.shadow(json.asReader())

    private fun decodeOpacity(json: JSONObject?): Opacity =
        AppearanceCodec.opacity(json.asReader())

    /**
     * 解析描边。
     *
     * ⚠️ 这里的 `color` **不再读取**：颜色已经搬到 `colors.outlineUp/Down`，
     * 由 [decodeColors] 统一处理（那边要先读老字段才能做迁移）。
     * 保留这个字段定义只是为了老 JSON 能解析出来不报错。
     */
    private fun decodeOutline(json: JSONObject?): KeyOutline {
        val defaults = KeyOutline()
        if (json == null) return defaults
        return KeyOutline(
            enabled = json.optBoolean("enabled", defaults.enabled),
            width = json.optDouble("width", defaults.width.toDouble())
                .toFloat()
                .coerceIn(0.5f, 5f),
            color = null,
        )
    }

    /**
     * 解析键位映射。
     *
     * 缺失时回落默认键位：映射为空会让悬浮窗上什么都不显示，
     * 用户会以为功能坏了。
     */
    private fun decodeKeyMappings(array: JSONArray?): List<KeyMapping> {
        if (array == null || array.length() == 0) return defaultKeyMappings()

        val result = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue

                val codes = item.optJSONArray("inputKeyCodes")
                val codeList = if (codes == null) {
                    emptyList()
                } else {
                    buildList {
                        for (codeIndex in 0 until codes.length()) {
                            add(codes.optInt(codeIndex))
                        }
                    }
                }

                add(
                    KeyMapping(
                        id = id,
                        inputKeyCodes = codeList,
                        displayText = item.optString("displayText").ifBlank { id },
                    ),
                )
            }
        }

        return result.ifEmpty { defaultKeyMappings() }
    }
}
