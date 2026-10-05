package com.something.sthkey.data.config

import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.config.ANIMATION_DURATION_MAX
import com.something.sthkey.domain.config.ANIMATION_DURATION_MIN
import com.something.sthkey.domain.config.AnimationMode
import com.something.sthkey.domain.config.DEFAULT_FONT_ID
import com.something.sthkey.domain.config.ShadowMode
import com.something.sthkey.domain.custom.ComponentStyle
import com.something.sthkey.domain.custom.ComponentType
import com.something.sthkey.domain.custom.CustomComponent
import com.something.sthkey.domain.custom.CustomLayout
import com.something.sthkey.domain.custom.CustomLayoutSettings
import com.something.sthkey.domain.custom.DEFAULT_CPS_KEY_CODES
import com.something.sthkey.domain.custom.KeyComponent
import com.something.sthkey.domain.custom.JoystickComponent
import com.something.sthkey.domain.custom.StickSide
import com.something.sthkey.domain.custom.TextComponent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义 Key 布局的 JSON 编解码。
 *
 * ============================================================
 * 读取原则：宽容读、规范写
 * ============================================================
 * 与 [JsonConfigCodec] 一致，因为要面对三种输入：老配置（没有这一段）、
 * 别人（或更新版本）导出的配置包、以及用户手工改过的文件。
 *
 * - 缺字段 → 用默认值；
 * - 越界值 → clamp（缩放、透明度、圆角、动画时长）；
 * - **不认识的组件类型 → 跳过那一个组件**，而不是整份布局读失败 ——
 *   新版加了"图片组件"，旧版读到时应该少一个组件，而不是丢掉整个布局；
 * - 数字被写成字符串之类的脏数据 → 用 `optXxx` 兜住，不抛异常。
 *
 * 这样"打开一个新版配置包"最多是少几个组件，而不是白屏。
 */
object CustomLayoutCodec {

    private const val TAG = "CustomKey"

    /**
     * 这一段自己的结构版本。
     *
     * 与配置的 [JsonConfigCodec] 的 schemaVersion **分开**：
     * 布局结构演进（例如组件类型变多）不应该让整份配置的版本号跟着跳，
     * 否则老版本应用看到版本号变了会直接把配置判成"来自新版"。
     */
    private const val LAYOUT_VERSION = 1

    /** 组件坐标的合法范围：防止手工改坏的数据把窗口撑成几万像素 */
    private const val COORD_MIN = -10_000f
    private const val COORD_MAX = 10_000f
    private const val SIZE_MIN = 1f
    private const val SIZE_MAX = 10_000f

    /*
     * ============================================================
     * 写
     * ============================================================
     */

    fun encode(settings: CustomLayoutSettings): JSONObject = JSONObject().apply {
        put("version", LAYOUT_VERSION)
        /*
         * ⚠️ 曾经的 `snapEnabled` / `snapGapDp` 已经删掉（吸附功能一并去掉了）。
         *
         * 老配置里可能有这两个字段，读取时**直接忽略** ——
         * 编解码本来就是"宽容读"，多出来的键不会造成任何问题，
         * 所以不需要为删除写迁移代码。
         */
        put(
            "components",
            JSONArray().apply {
                settings.components.forEach { put(encodeComponent(it)) }
            },
        )
    }

    private fun encodeComponent(component: CustomComponent): JSONObject = JSONObject().apply {
        put("type", typeIdOf(component))
        put("id", component.id)
        put("x", component.x.toDouble())
        put("y", component.y.toDouble())
        put("width", component.width.toDouble())
        put("height", component.height.toDouble())

        /*
         * ⚠️ `style`（[ComponentStyle]）**只有**有文字的组件才有；
         * 摇杆用的是 `joystick`（[JoystickStyle]），两者字段几乎不重叠。
         *
         * 所以这里分两条路写，而不是"所有组件都写 style" ——
         * 后者会给摇杆写进去一份**永远不会被读**的垃圾数据，
         * 手工看 JSON 的人会以为它生效。
         */
        when (component) {
            is KeyComponent -> {
                put("style", encodeStyle(component.style))
                put("label", component.label)
                put(
                    "inputKeyCodes",
                    JSONArray().apply { component.inputKeyCodes.forEach { put(it) } },
                )
                put("textScalePercent", component.textScalePercent)
                // CPS 那一行的额外缩放（模式 3 的第二行比主文字小）
                put("cpsTextScalePercent", component.cpsTextScalePercent)
                put("textOffsetX", component.textOffsetX.toDouble())
                put("textOffsetY", component.textOffsetY.toDouble())
                put("animationMode", component.animationMode.id)
                put("animationDurationSec", component.animationDurationSec.toDouble())
            }

            is TextComponent -> {
                put("style", encodeStyle(component.style))
                put("text", component.text)
                /*
                 * ⚠️ 这个字段**只在文字里含占位符时才有意义**，但仍然照实写。
                 *
                 * 不按"当前文字里有没有占位符"决定写不写：用户可能先把
                 * `(cps)` 删掉、过一会儿又加回来，中途保存一次的话
                 * 选择就被抹掉了。写全比写"当前有用的部分"更不容易丢信息。
                 *
                 * ⚠️ 两组字段都写：`cpsKeyCodesPerPlaceholder` 是新的
                 * （每个占位符一组），`cpsKeyCodes` 是旧的（扁平）。
                 * 写旧的只有一个原因 —— **让新导出的包能被老版本读**，
                 * 老版本认那个字段。读取时以新的为准（见 decodeTextComponent）。
                 */
                put(
                    "cpsKeyCodes",
                    JSONArray().apply { component.cpsKeyCodes.forEach { put(it) } },
                )
                put(
                    "cpsKeyCodesPerPlaceholder",
                    JSONArray().apply {
                        component.cpsKeyCodesPerPlaceholder.forEach { group ->
                            put(JSONArray().apply { group.forEach { put(it) } })
                        }
                    },
                )
                put("textScalePercent", component.textScalePercent)
                put("textOffsetX", component.textOffsetX.toDouble())
                put("textOffsetY", component.textOffsetY.toDouble())
            }

            is JoystickComponent -> {
                /* 监听哪一边；用 `StickSide.id` 而不是枚举名 —— 改枚举名不该弄坏配置 */
                put("stickSide", component.side.id)
                put("joystick", JsonConfigCodec.encodeJoystick(component.joystick))
            }
        }
    }

    /**
     * 组件的类型 id。
     *
     * ⚠️ 早期这里是 `if (component is KeyComponent) KEY else TEXT` —— 只有两种
     * 类型时能跑，加第三种就会把所有摇杆**当成文本**写出去。所以改成穷尽 `when`，
     * 以后再加类型时编译器会直接报错。
     */
    private fun typeIdOf(component: CustomComponent): String = when (component) {
        is KeyComponent -> ComponentType.KEY.id
        is TextComponent -> ComponentType.TEXT.id
        is JoystickComponent -> ComponentType.JOYSTICK.id
    }

    private fun encodeStyle(style: ComponentStyle): JSONObject = JSONObject().apply {
        put("fillUp", style.fillUp)
        put("fillDown", style.fillDown)
        put("textUp", style.textUp)
        put("textDown", style.textDown)
        put("fillOpacityUp", style.fillOpacityUp)
        put("fillOpacityDown", style.fillOpacityDown)
        put("textOpacityUp", style.textOpacityUp)
        put("textOpacityDown", style.textOpacityDown)
        put("outlineEnabled", style.outlineEnabled)
        put("outlineWidth", style.outlineWidth.toDouble())
        // 描边与阴影都按状态存一份颜色与不透明度（老版本只有一个 outlineColor）
        put("outlineUp", style.outlineUp)
        put("outlineDown", style.outlineDown)
        put("outlineOpacityUp", style.outlineOpacityUp)
        put("outlineOpacityDown", style.outlineOpacityDown)
        put("shadowEnabled", style.shadowEnabled)
        put("shadowMode", style.shadowMode.id)
        put("shadowSize", style.shadowSize.toDouble())
        put("shadowUp", style.shadowUp)
        put("shadowDown", style.shadowDown)
        put("shadowOpacityUp", style.shadowOpacityUp)
        put("shadowOpacityDown", style.shadowOpacityDown)
        put("cornerRadiusEnabled", style.cornerRadiusEnabled)
        put("cornerRadiusPercent", style.cornerRadiusPercent.toDouble())
        put("fontId", style.fontId)
        put("bitmapFontId", style.bitmapFontId)
        // 字间距 / 行间距（相对字号的百分比，0 = 不动）
        put("letterSpacing", style.letterSpacing.toDouble())
        put("lineSpacing", style.lineSpacing.toDouble())
    }

    /*
     * ============================================================
     * 读
     * ============================================================
     */

    /**
     * 解析自定义布局。
     *
     * @return 解析结果；**没有任何可用组件时返回默认布局** ——
     *   空布局会让悬浮窗显示成一片空白，用户会以为配置坏了。
     *   用户真的删光组件时，保存流程写入的是"空列表"，
     *   这里刻意区分不开 —— 相比"打开就白屏"，回到默认布局是更好的失败方式。
     */
    fun decode(json: JSONObject?): CustomLayoutSettings {
        val defaults = CustomLayoutSettings()
        if (json == null) return defaults

        val components = decodeComponents(json.optJSONArray("components"))
            .ifEmpty { return defaults }

        return CustomLayoutSettings(components = components)
    }

    private fun decodeComponents(array: JSONArray?): List<CustomComponent> {
        if (array == null) return emptyList()

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val type = ComponentType.fromId(item.optString("type"))
                if (type == null) {
                    // 多半来自更新版本：只丢这一个组件，其余照常读
                    AppLog.w(TAG, "跳过不认识的组件类型：${item.optString("type")}")
                    continue
                }

                val id = item.optString("id").ifBlank { "component_$index" }
                val geometry = Geometry(
                    x = number(item, "x", 0f),
                    y = number(item, "y", 0f),
                    width = number(item, "width", 80f).coerceIn(SIZE_MIN, SIZE_MAX),
                    height = number(item, "height", 80f).coerceIn(SIZE_MIN, SIZE_MAX),
                )
                val style = decodeStyle(item.optJSONObject("style"))

                when (type) {
                    ComponentType.KEY -> add(
                        KeyComponent(
                            id = id,
                            x = geometry.x,
                            y = geometry.y,
                            width = geometry.width,
                            height = geometry.height,
                            style = style,
                            label = item.optString("label", "Q").ifBlank { "Q" },
                            inputKeyCodes = decodeKeyCodes(item.optJSONArray("inputKeyCodes")),
                            textScalePercent = item.optInt("textScalePercent", 100)
                                .coerceIn(CustomLayout.TEXT_SCALE_MIN, CustomLayout.TEXT_SCALE_MAX),
                            // CPS 行缩放是本版新增的；老组件没有这个字段 → 默认 100（一样大）
                            cpsTextScalePercent = item.optInt("cpsTextScalePercent", 100)
                                .coerceIn(CustomLayout.CPS_LINE_SCALE_MIN, CustomLayout.CPS_LINE_SCALE_MAX),
                            textOffsetX = number(item, "textOffsetX", 0f),
                            textOffsetY = number(item, "textOffsetY", 0f),
                            animationMode = AnimationMode.fromId(item.optString("animationMode")),
                            animationDurationSec = number(item, "animationDurationSec", 0.1f)
                                .coerceIn(ANIMATION_DURATION_MIN, ANIMATION_DURATION_MAX),
                        ),
                    )

                    ComponentType.TEXT -> {
                        /*
                         * 先把老的扁平字段解析出来（里面还含更早几种格式的迁移），
                         * 它既是旧字段的值，也是新字段缺失时的回退。
                         */
                        val legacyCpsKeyCodes = decodeCpsKeyCodes(item)
                        add(
                            TextComponent(
                                id = id,
                                x = geometry.x,
                                y = geometry.y,
                                width = geometry.width,
                                height = geometry.height,
                                style = style,
                                text = item.optString("text", "CPS: (cps)"),
                                cpsKeyCodesPerPlaceholder = decodeCpsKeyCodesPerPlaceholder(
                                    item = item,
                                    legacy = legacyCpsKeyCodes,
                                ),
                                cpsKeyCodes = legacyCpsKeyCodes,
                                textScalePercent = item.optInt("textScalePercent", 100)
                                    .coerceIn(CustomLayout.TEXT_SCALE_MIN, CustomLayout.TEXT_SCALE_MAX),
                                textOffsetX = number(item, "textOffsetX", 0f),
                                textOffsetY = number(item, "textOffsetY", 0f),
                            ),
                        )
                    }

                    ComponentType.JOYSTICK -> add(
                        JoystickComponent(
                            id = id,
                            x = geometry.x,
                            y = geometry.y,
                            width = geometry.width,
                            height = geometry.height,
                            /*
                             * ⚠️ 用 `StickSide.fromId` 而不是 `valueOf`:
                             * 手改过的 JSON 里可能是空串或别的写法，
                             * `valueOf` 会**抛异常**，整份配置都读不出来。
                             * 认不出来时回退到左摇杆。
                             */
                            side = StickSide.fromId(item.optString("stickSide")),
                            /*
                             * 整段摇杆外观复用 JsonConfigCodec 的那一对函数 ——
                             * 摇杆的字段有二十来个（含描边、内圆、手感），
                             * 在这里再写一份迟早会与原样式漂移
                             * （改了一边忘了另一边，两处摇杆长得不一样）。
                             */
                            joystick = JsonConfigCodec.decodeJoystick(
                                item.optJSONObject("joystick"),
                            ),
                        ),
                    )
                }
            }
        }
    }

    /** 组件的位置与尺寸，读的时候统一 clamp */
    private data class Geometry(val x: Float, val y: Float, val width: Float, val height: Float)

    /**
     * 读一个数字字段。
     *
     * 用 `optDouble` 而不是 `getDouble`：脏数据（字符串、null）会走默认值而不是抛异常，
     * 一份手改坏的配置只该少几个属性，不该整份读不出来。
     */
    private fun number(json: JSONObject, key: String, fallback: Float): Float =
        json.optDouble(key, fallback.toDouble()).toFloat().coerceIn(COORD_MIN, COORD_MAX)

    private fun decodeKeyCodes(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                // 负数码不是合法键码，直接丢掉
                val code = array.optInt(index, -1)
                if (code >= 0) add(code)
            }
        }
    }

    /**
     * 读文本组件"CPS 统计哪些键"。
     *
     * 四种输入要分清，这是本函数存在的全部理由：
     *
     * 1. **有 `cpsKeyCodes` 数组**（当前格式）→ 用它；
     * 2. **有 `cpsKeyCode` 单个数**（上一版的格式，那时只能选一个）→ 包成单元素列表；
     * 3. **有 `cps.keyCode`**（更早，那时是一个 `cps` 对象）→ 迁移过来。
     *    那一版还有个 `cps.text` 模板字段，**刻意不读** ——
     *    模板只能有一个来源（组件自己的 `text`），读它会造出第二个真源；
     * 4. **只有 `cpsSlot`**（最早，只能选左/右键）→ 翻译成对应键码。
     *
     * 都没有就用默认值（鼠标左键）。注意这里**不存在"关闭 CPS"这个取值**了：
     * 要不要显示 CPS 由文字里有没有占位符决定，删掉占位符就是关闭。
     *
     * 空数组同样落回默认值：一个键都不选的话 CPS 恒为 0，
     * 那不是用户想要的"关闭"（关闭靠删占位符），更像配置坏了。
     */
    private fun decodeCpsKeyCodes(item: JSONObject): List<Int> {
        item.optJSONArray("cpsKeyCodes")
            ?.let { array -> decodeKeyCodes(array).takeIf { it.isNotEmpty() } }
            ?.let { return it }

        item.optInt("cpsKeyCode", -1).takeIf { it >= 0 }?.let { return listOf(it) }

        item.optJSONObject("cps")?.let { legacy ->
            if (!legacy.isNull("keyCode")) {
                legacy.optInt("keyCode", -1).takeIf { it >= 0 }?.let { return listOf(it) }
            }
        }

        CustomLayout.legacyKeyCodeOf(item.optString("cpsSlot").ifBlank { null })
            ?.let { return listOf(it) }

        return DEFAULT_CPS_KEY_CODES
    }

    /**
     * 读"每个 CPS 占位符各统计哪些键"。
     *
     * ============================================================
     * 老配置的迁移（这里的关键）
     * ============================================================
     * 老版本只有**一份**键位（扁平的 `cpsKeyCodes`），
     * 而那时一个组件也只能有一个占位符 —— 所以那一份键位就是
     * "第一个占位符"的键位。
     *
     * 于是迁移规则很简单：**把老的那一份放进第一组**。
     * 用户升级后看到的键位与之前一模一样。
     *
     * 新字段存在时以它为准；只有一组时也照常返回一组 ——
     * 用户之后新写占位符时，[CustomLayout.cpsKeyCodesAt] 会回落到第一组。
     *
     * @param legacy 由 [decodeCpsKeyCodes] 解析出的老字段值（已含更早格式的迁移）
     */
    private fun decodeCpsKeyCodesPerPlaceholder(item: JSONObject, legacy: List<Int>): List<List<Int>> {
        val array = item.optJSONArray("cpsKeyCodesPerPlaceholder")
            ?: return listOf(legacy)

        val groups = buildList {
            for (index in 0 until array.length()) {
                val group = array.optJSONArray(index) ?: continue
                val codes = decodeKeyCodes(group)
                /*
                 * ⚠️ 空的**内层**要保留，不能跳过。
                 *
                 * "这一处不统计任何键"是一个有意义的选择（用户可能想临时关掉
                 * 某一路 CPS），跳过它会让后面几组整体前移、颜色标记全错位。
                 */
                add(codes)
            }
        }

        return groups.ifEmpty { listOf(legacy) }
    }

    private fun decodeStyle(json: JSONObject?): ComponentStyle {
        val defaults = ComponentStyle()
        if (json == null) return defaults

        /*
         * ⚠️ 老组件的描边色迁移。
         *
         * 老版本描边只有一个 `outlineColor`，而且默认是白色。
         * 现在颜色分按下/未按下，所以要把那一个值**同时填给两个状态** ——
         * 与 Key 样式那边同一条规则：迁移不该改变观感。
         *
         * 用 `has()` 而不是"取到 0 就当没设"：纯黑描边完全合法。
         */
        val textUp = json.optLong("textUp", defaults.textUp).and(0xFFFFFF)
        val textDown = json.optLong("textDown", defaults.textDown).and(0xFFFFFF)

        val hasNewOutline = json.has("outlineUp") || json.has("outlineDown")
        val legacyOutline = if (json.has("outlineColor")) {
            json.optLong("outlineColor", defaults.outlineUp).and(0xFFFFFF)
        } else {
            null
        }
        val outlineUp: Long
        val outlineDown: Long
        if (hasNewOutline) {
            outlineUp = json.optLong("outlineUp", textUp).and(0xFFFFFF)
            outlineDown = json.optLong("outlineDown", textDown).and(0xFFFFFF)
        } else if (legacyOutline != null) {
            // 老配置设过颜色 → 两个状态都用它
            outlineUp = legacyOutline
            outlineDown = legacyOutline
        } else {
            // 老配置没设过 → 跟随文字色（旧版实际显示的就是这个）
            outlineUp = textUp
            outlineDown = textDown
        }

        val legacyOutlineOpacity = percent(json, "outlineOpacity", defaults.outlineOpacityUp)

        return ComponentStyle(
            // 颜色统一掩到 24 位：写进来的高 8 位（透明度）不属于这里，透明度单独存
            fillUp = json.optLong("fillUp", defaults.fillUp).and(0xFFFFFF),
            fillDown = json.optLong("fillDown", defaults.fillDown).and(0xFFFFFF),
            textUp = textUp,
            textDown = textDown,
            fillOpacityUp = percent(json, "fillOpacityUp", defaults.fillOpacityUp),
            fillOpacityDown = percent(json, "fillOpacityDown", defaults.fillOpacityDown),
            textOpacityUp = percent(json, "textOpacityUp", defaults.textOpacityUp),
            textOpacityDown = percent(json, "textOpacityDown", defaults.textOpacityDown),
            outlineEnabled = json.optBoolean("outlineEnabled", defaults.outlineEnabled),
            outlineWidth = json.optDouble("outlineWidth", defaults.outlineWidth.toDouble())
                .toFloat()
                .coerceIn(CustomLayout.OUTLINE_WIDTH_MIN, CustomLayout.OUTLINE_WIDTH_MAX),
            outlineUp = outlineUp,
            outlineDown = outlineDown,
            // 老配置只有一个描边透明度 → 两个状态都用它
            outlineOpacityUp = percent(json, "outlineOpacityUp", legacyOutlineOpacity),
            outlineOpacityDown = percent(json, "outlineOpacityDown", legacyOutlineOpacity),
            // 阴影是本版新增的，老组件没有 → 用默认值（关闭）
            shadowEnabled = json.optBoolean("shadowEnabled", defaults.shadowEnabled),
            shadowMode = ShadowMode.fromId(
                json.optString("shadowMode", defaults.shadowMode.id)
                    .takeIf { it.isNotBlank() },
            ),
            shadowSize = json.optDouble("shadowSize", defaults.shadowSize.toDouble())
                .toFloat()
                .coerceIn(CustomLayout.SHADOW_SIZE_MIN, CustomLayout.SHADOW_SIZE_MAX),
            shadowUp = json.optLong("shadowUp", textUp).and(0xFFFFFF),
            shadowDown = json.optLong("shadowDown", textDown).and(0xFFFFFF),
            shadowOpacityUp = percent(json, "shadowOpacityUp", defaults.shadowOpacityUp),
            shadowOpacityDown = percent(json, "shadowOpacityDown", defaults.shadowOpacityDown),
            cornerRadiusEnabled = json.optBoolean(
                "cornerRadiusEnabled",
                defaults.cornerRadiusEnabled,
            ),
            cornerRadiusPercent = json.optDouble(
                "cornerRadiusPercent",
                defaults.cornerRadiusPercent.toDouble(),
            ).toFloat().coerceIn(
                CustomLayout.CORNER_PERCENT_MIN,
                CustomLayout.CORNER_PERCENT_MAX,
            ),
            fontId = json.optString("fontId", defaults.fontId).ifBlank { DEFAULT_FONT_ID },
            bitmapFontId = json.optString("bitmapFontId", ""),
            letterSpacing = json.optDouble("letterSpacing", 0.0).toFloat(),
            lineSpacing = json.optDouble("lineSpacing", 0.0).toFloat(),
        )
    }

    /** 百分比统一 clamp 到 0..100 */
    private fun percent(json: JSONObject, key: String, fallback: Int): Int =
        json.optInt(key, fallback).coerceIn(0, 100)
}
