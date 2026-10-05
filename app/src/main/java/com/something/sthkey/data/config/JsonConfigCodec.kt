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
     */
    val KNOWN_KEYS: Set<String> by lazy {
        encode(probeConfigForKnownKeys()).keys().asSequence().toSet()
    }

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
        "keySize" to "按键大小",
        "keyGap" to "键间距",
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
                .toFloat().coerceIn(0f, 0.2f),
            ringColor = readJoystickColor(json, "ringColor", d.ringColor),
            ringOpacity = json.optDouble("ringOpacity", d.ringOpacity.toDouble())
                .toFloat().coerceIn(0f, 1f),
            ringWidthRatio = json.optDouble("ringWidthRatio", d.ringWidthRatio.toDouble())
                .toFloat().coerceIn(0f, 0.2f),
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
            ).toFloat().coerceIn(0f, 0.3f),
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
        put("keySize", config.keySize.toDouble())
        put("keyGap", config.keyGap.toDouble())
        put("scalePercent", config.scalePercent)
        put("textScalePercent", config.textScalePercent)
        put("cornerRadiusEnabled", config.cornerRadiusEnabled)
        put("cornerRadiusPercent", config.cornerRadiusPercent.toDouble())
        put("animationMode", config.animationMode.id)
        put("animationDurationSec", config.animationDurationSec.toDouble())
        put("showShiftKey", config.showShiftKey)
        put("showMouseButtons", config.showMouseButtons)
        put("showShoulderButtons", config.showShoulderButtons)
        put("showAButton", config.showAButton)
        put("aButtonOnTop", config.aButtonOnTop)
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
         */
        put("showSpaceKey", config.showSpaceKey)
        put("spaceKeyOnTop", config.spaceKeyOnTop)
        put("swapSticks", config.swapSticks)
        put("keyHeightPercent", config.keyHeightPercent)
        put("keyGapPercent", config.keyGapPercent)
        put("joystick", encodeJoystick(config.joystick))
        put("mouseCpsEnabled", config.mouseCpsEnabled)
        put("mouseCpsMode", config.mouseCpsMode)
        put("cpsTextTemplate", config.cpsTextTemplate)
        put("cpsTextTemplateMode1", config.cpsTextTemplateMode1)

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
        put("bitmapFontId", config.bitmapFontId)

        // 文字偏移：图片字体常需要按图集微调，不存的话重启就白调了
        put("textOffsetX", config.textOffsetX.toDouble())
        put("textOffsetY", config.textOffsetY.toDouble())

        /*
         * 每个槽位各自的偏移。
         *
         * 存成对象（`{ "SPACE": {"x":0,"y":3} }`）而不是两个扁平数组：
         * 数组要靠下标与槽位列表对应，布局一变就会错位；
         * 用槽位 id 做键则天然稳定，也不必保证"两个数组一样长"。
         *
         * ⚠️ **无条件写**，哪怕为空。
         *
         * 早先写的是"非空才写"，结果有两处不对：
         * 1. 读的时候无条件读、写的时候却不写 —— 编解码本身就是不对称的；
         * 2. [KNOWN_KEYS] 是从"把探针配置编码一遍"推导出来的，于是这个键
         *    不在已知清单里，**带它的配置包导入时会被报成"未识别字段"**
         *    （用户实测遇到的就是这个）。
         *
         * 一个空对象（`{}`）的开销可以忽略，换来的是"读写对称 + 键始终已注册"。
         */
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

        /*
         * 字间距 / 行间距：与文字偏移**同一套结构**（全局 + 每槽位）。
         *
         * 同样**无条件写** —— 理由与上面 `slotTextOffsets` 完全一样
         * （读写对称 + 键始终在 KNOWN_KEYS 里）。
         */
        put(
            "textSpacing",
            JSONObject().apply {
                put("letter", config.textSpacing.letter.toDouble())
                put("line", config.textSpacing.line.toDouble())
            },
        )
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

        put(
            "shadow",
            JSONObject().apply {
                put("enabled", config.shadow.enabled)
                put("mode", config.shadow.mode.id)
                put("size", config.shadow.size.toDouble())
            },
        )

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
         * 按键样式用不到它，但**照样写出去** —— 用户可能随时把样式切到 Live2D，
         * 不写的话那些设置会在切换时悄悄丢回默认值。
         */
        put(
            "live2d",
            JSONObject().apply {
                put("modelId", config.live2d.modelId)
                put("opacityPercent", config.live2d.opacityPercent)
            },
        )

        // 自定义 Key 布局；同样与样式无关地照写（理由同上）
        put("custom", CustomLayoutCodec.encode(config.custom))

        /*
         * 自定义 Key 的**整体透明度**。
         *
         * ⚠️ 与上面那些字段一样"与样式无关地照写" —— 只在
         * [StyleId.CUSTOM_KEY] 下有意义，但换样式时不该把它丢掉
         * （用户可能只是临时切走看一眼，再切回来）。
         */
        put("customOpacityPercent", config.customOpacityPercent)
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
