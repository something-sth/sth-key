package com.something.sthkey.data.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * 导出包里的**人读说明**（`_comment` 字段）。
 *
 * ============================================================
 * ⚠️ 为什么不是真正的 JSON 注释
 * ============================================================
 * JSON 标准不支持 `//` / `/* */`，而本项目用 `org.json` 解析 ——
 * 真注释会让导入直接失败。所以这里用**合法的元数据字段**:
 *
 * ```json
 * { "_comment": ["本文件是…", "字段说明：…"], "schemaVersion": 1, … }
 * ```
 *
 * - 解析器只读认识的字段，`_comment` 自动被忽略；
 * - 旧版本 App / 后续 Windows 程序也都能安全忽略；
 * - `params.toString(2)` 输出后是一行一条的可读说明。
 *
 * ============================================================
 * ⚠️ 只加在**导出**里，不进本地存储
 * ============================================================
 * `ConfigStore` 的本地 JSON 不加（省体积、也没人看）；
 * 只有 `ConfigPackageManager.writePackage` 与两个 `encodeManifest` 注入。
 *
 * ============================================================
 * ⚠️ 字段说明按**实际写出的字段**生成
 * ============================================================
 * 不同样式写出的字段不同（见 `JsonConfigCodec.expectedKeysFor`），
 * 所以说明不写死一份，而是遍历实际字段 + 一张 `字段 → 说明` 表。
 * `ConfigJsonCommentsTest` 会断言**每个预期字段都有说明** ——
 * 以后加字段忘了写说明，测试会红，而不是让注释悄悄过期。
 */
object ConfigJsonComments {

    /** 元数据字段名；所有以 `_` 开头的顶层键在导入时都会被忽略 */
    const val KEY = "_comment"

    private val HEADER = listOf(
        "本文件是 sth key 的配置参数（params.json）。程序读取时会忽略本段（_comment）。",
        "键位绑定为明文 ID（如 KEY_A、MOUSE_LMB、GAMEPAD_A、JOYSTICK_LEFT_UP）；" +
            "详见 docs/key-id-format.md。",
        "坐标：组件 x/y/width/height 基于 600×600 画布；触摸区域 centerX/Y 是整屏 0..1、" +
            "size 是短边比例。",
        "百分比字段（scalePercent、opacity 等）取值 0..100；整数颜色为 24 位 RGB。",
        "字段说明（按本配置的样式列出实际写出的字段）：",
    )

    private val MANIFEST_COMMENT = listOf(
        "本文件是 sth key 配置包的元信息（manifest.json）。程序读取时会忽略本段（_comment）。",
        "format / formatVersion：包格式标识与版本，导入时会校验。",
        "appVersion / exportedAt：导出时的应用版本、Unix 毫秒时间。",
        "name / description / styleId：配置名称、描述、样式 id。",
        "fonts[]：随包字体；path 是包内路径，displayName 是显示名，sha256 用于去重。" +
            "图片字体还会平铺 bitmap 规格字段（maskMode 等）。",
        "live2d：随包 Live2D 模型或 null；media[]：随包媒体资源（图片/动图/视频）。",
    )

    private val GROUP_MANIFEST_COMMENT = listOf(
        "本文件是 sth key 配置组合包的元信息（manifest.json）。程序读取时会忽略本段（_comment）。",
        "format / formatVersion：包格式标识与版本，导入时会校验。",
        "appVersion / exportedAt：导出时的应用版本、Unix 毫秒时间。",
        "name / description：组合名称与描述。",
        "configs[]：组合成员；file 是包内 .sthkey 条目路径，name/description/styleId 是成员元信息。",
    )

    /**
     * 字段说明表。
     *
     * ⚠️ 键名必须与 `JsonConfigCodec` 写出的**顶层字段**一致。
     * 漏了会走下面的兜底文案，而测试会直接报出来。
     */
    private val FIELD_DOCS: Map<String, String> = mapOf(
        "schemaVersion" to "配置格式版本（内部用，不随配置内容变）。",
        "id" to "配置标识；导入时会换成新的 id。",
        "name" to "配置名称（只写在 manifest 里；params 里通常没有）。",
        "description" to "配置描述（只写在 manifest 里；params 里通常没有）。",
        "builtIn" to "是否为内置配置；内置配置不可删除。",
        "styleId" to "悬浮窗样式 id：keystrokes / keyboard_cat / gamepad2 / touching / custom_key。",
        "scalePercent" to "整体缩放百分比，100 = 原大小。",
        "keyHeightPercent" to "按键高度百分比（相对默认高度）。",
        "keyGapPercent" to "按键间距百分比（相对默认间距）。",
        "textScalePercent" to "文字缩放百分比，100 = 原大小。",
        "cornerRadiusEnabled" to "是否启用圆角。",
        "cornerRadiusPercent" to "圆角大小百分比（相对短边）。",
        "colors" to "颜色组：按下 / 未按下等状态的 24 位 RGB 颜色。",
        "outline" to "描边设置（粗细百分比与颜色）。",
        "shadow" to "文字阴影设置（模式、颜色、模糊/偏移）。",
        "opacity" to "不透明度组：键帽 / 文字 / 描边 / 阴影，取值 0..100。",
        "fontId" to "字体 id：system:* / builtin:* / imported:*。",
        "bitmapFontId" to "图片字体 id（Minecraft 风格图集）；空串 = 不用。",
        "textOffsetX" to "文字在组件内的水平偏移（基础坐标）。",
        "textOffsetY" to "文字在组件内的垂直偏移（基础坐标）。",
        "slotTextOffsets" to "每个槽位各自的文字偏移（槽位 id → 偏移）。",
        "textSpacing" to "多行文字的行距（基础坐标）。",
        "slotTextSpacings" to "每个槽位各自的行距。",
        "animationMode" to "按下动画模式：无 / 颜色渐变 / 扩散收缩。",
        "animationDurationSec" to "按下动画时长（秒）。",
        "showShiftKey" to "是否显示 Shift 键。",
        "showSpaceKey" to "是否显示空格键。",
        "spaceKeyOnTop" to "空格键是否置顶（与 A 键位置互换）。",
        "showMouseButtons" to "是否显示鼠标键（LMB / RMB）。",
        "showAButton" to "是否显示 A 键（手柄样式）。",
        "aButtonOnTop" to "A 键是否置顶。",
        "showShoulderButtons" to "是否显示肩键（LB / RB）。",
        "swapSticks" to "是否交换左右摇杆的轴来源（只换摇杆，不动键位映射）。",
        "mouseCpsEnabled" to "是否显示 CPS。",
        "mouseCpsMode" to "CPS 显示模式（1 / 2 / 3）。",
        "cpsTextTemplate" to "CPS 文本模板（模式 2）。",
        "cpsTextTemplateMode1" to "CPS 文本模板（模式 1）。",
        "keyMappings" to "键位映射；inputKeyCodes 是明文键位 ID 数组。",
        "joystick" to "摇杆外观与手感（三种摇杆共用）。",
        "touch" to "触屏样式设置：regions / move / camera。",
        "live2d" to "Live2D 模型设置：modelId / opacityPercent。",
        "custom" to "自定义 Key 布局：components / touchRegions。",
        "customOpacityPercent" to "自定义 Key 的整体不透明度，0..100。",
        "customTextScalePercent" to "自定义 Key 的整体字符缩放，100 = 原大小。",
    )

    /** params.json 的说明行；[keys] 是实际写出的顶层字段（顺序保持） */
    fun paramsFor(styleId: String, keys: List<String>): List<String> = buildList {
        addAll(HEADER)
        add("配置样式：$styleId。")
        keys.filterNot { it.startsWith("_") }.forEach { key ->
            add("  $key（${JsonConfigCodec.labelOf(key)}）：${docOf(key)}")
        }

        add("")
        add("嵌套结构补充：")
        if (keys.contains("keyMappings")) {
            add("  keyMappings[]：{ id: 槽位名, displayText: 显示文字, inputKeyCodes: 明文键位 ID 数组 }。")
        }
        if (keys.contains("custom")) {
            add(
                "  custom.components[]：type = key / text / joystick / image / gif / video；" +
                    "x/y 是左上角，width/height 是尺寸。",
            )
            add(
                "  custom.touchRegions[]：kind = button / move_stick；" +
                    "bindings[].componentId 指向组件 id，direction 为八向之一或 null。",
            )
        }
        if (keys.contains("touch")) {
            add("  touch.regions[]：pinned=true 才生效；move / camera 是两个摇杆的手感。")
        }
        if (keys.contains("live2d")) {
            add("  live2d.modelId：内置模型 id 或 imported:<uuid>；opacityPercent 为 0..100。")
        }
    }

    fun manifest(): List<String> = MANIFEST_COMMENT

    fun groupManifest(): List<String> = GROUP_MANIFEST_COMMENT

    /**
     * 把 `_comment` 放到**最前面**，再原样拷贝其它字段。
     *
     * ⚠️ `JSONObject` 在 Android 上保持插入顺序，所以导出后注释会在文件顶部。
     * 这里不直接往原对象里 `put`，因为那样注释会跑到末尾。
     */
    fun withParamsComment(params: JSONObject, styleId: String): JSONObject {
        val keys = params.keys().asSequence().filterNot { it.startsWith("_") }.toList()
        val out = JSONObject()
        out.put(KEY, JSONArray(paramsFor(styleId, keys)))
        keys.forEach { key -> out.put(key, params.get(key)) }
        return out
    }

    /** 给 manifest 这类小对象直接加 `_comment`（放在第一个 key） */
    fun putManifestComment(target: JSONObject, lines: List<String>) {
        target.put(KEY, JSONArray(lines))
    }

    private fun docOf(key: String): String =
        FIELD_DOCS[key] ?: "（暂无说明：$key）"
}
