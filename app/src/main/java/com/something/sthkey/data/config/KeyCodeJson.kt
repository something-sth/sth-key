package com.something.sthkey.data.config

import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.keys.KeyCodes
import com.something.sthkey.domain.keys.KeyIds
import org.json.JSONArray

/**
 * 键位数组的 **JSON 双读 / 明文写**（2026-11-15）。
 *
 * ============================================================
 * 规则只有三条
 * ============================================================
 * | 方向 | 行为 |
 * |---|---|
 * | 读数字（老配置） | 当成 evdev 码，先走 [KeyCodes.migrateLegacyCode] |
 * | 读字符串（新配置） | 走 [KeyIds.decode] |
 * | 写 | **只写** [KeyIds.encode] 的明文 ID |
 *
 * ⚠️ 数字和字符串可以**混在同一个数组里**（迁移期、手改配置）。
 * 不认识的值**跳过并记日志**，不要让整份配置读不出来。
 *
 * ⚠️ 这个文件是"所有键码字段"的唯一读写口。各编解码器不要自己
 * `optInt` / `put(code)` —— 漏一处就会让那个字段继续写数字。
 */
internal object KeyCodeJson {

    private const val TAG = "KeyCodeJson"

    /** 读一个 JSON 值（Number / String）；不认识返回 null */
    fun readValue(value: Any?): Int? = when (value) {
        /*
         * ⚠️ 负数码不是合法键位（老 `decodeKeyCodes` 也是直接丢掉）。
         * 脏数据不该把 -1 之类的值带进 `pressedCodes`。
         */
        is Number -> value.toInt().takeIf { it >= 0 }?.let { KeyCodes.migrateLegacyCode(it) }
        is String -> {
            val id = value.trim()
            KeyIds.decode(id)
                ?: run {
                    AppLog.w(TAG, "不认识的键位 ID：$id（已跳过）")
                    null
                }
        }
        else -> null
    }

    /** 读一组键码；null / 空数组都返回空列表 */
    fun readList(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                readValue(array.opt(index))?.let { add(it) }
            }
        }
    }

    /** 写一个键码（永远是明文 ID） */
    fun put(array: JSONArray, code: Int) {
        array.put(KeyIds.encode(code))
    }

    /** 写一组键码 */
    fun putList(array: JSONArray, codes: List<Int>) {
        codes.forEach { put(array, it) }
    }

    /**
     * 写组合键：外层数组的每一项是"一组同时按下的键"。
     *
     * 落盘仍然是**明文 ID**（[KeyIds.encode]），与单键保持同一套约定，
     * 方便以后多端互通。
     */
    fun putCombos(array: JSONArray, combos: List<List<Int>>) {
        combos.forEach { combo ->
            array.put(
                JSONArray().apply { combo.forEach { put(this, it) } },
            )
        }
    }

    /**
     * 读组合键：允许数字（老配置）与明文字符串混用；
     * 内层空数组保留 —— "这一组不绑定任何键"是有意义的。
     */
    fun readCombos(array: JSONArray?): List<List<Int>> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val combo = array.optJSONArray(index) ?: continue
                add(readList(combo))
            }
        }
    }
}
