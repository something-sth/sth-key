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
import com.something.sthkey.domain.config.KeyStrokesConfig
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
    private fun probeConfigForKnownKeys(): KeyStrokesConfig {
        val base = defaultConfig()
        return base.copy(
            outline = base.outline.copy(enabled = true),
            shadow = base.shadow.copy(enabled = true),
            cornerRadiusEnabled = true,
            showShiftKey = true,
            showMouseButtons = true,
            mouseCpsEnabled = true,
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
        "cpsTextTemplate" to "CPS 文本（模式 2/3）",
        "cpsTextTemplateMode1" to "CPS 文本（模式 1）",
        "keyMappings" to "键位映射",
        "live2d" to "Live2D 设置",
        "custom" to "自定义 Key 布局",
    )

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
            scalePercent = json.optInt("scalePercent", defaults.scalePercent).coerceIn(50, 200),
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
            mouseCpsEnabled = json.optBoolean("mouseCpsEnabled", false),
            mouseCpsMode = json.optInt("mouseCpsMode", 1).coerceIn(1, 3),
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
