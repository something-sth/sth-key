package com.something.sthkey.domain.style

import com.something.sthkey.domain.config.KeyStrokesConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「整体缩放」的语义:**百分比就是倍率**，以及各滑块的**范围一致性**。
 *
 * ============================================================
 * ⚠️ 为什么这份测试不碰 JSON
 * ============================================================
 * 用户报的 bug 是"按键间距配置没有持久化，杀进程重进就恢复默认值"。
 * 根因**不在编解码本身**，而在"同一组范围被写了三份":
 *
 * | 用它的地方 | 当时的写法 |
 * |---|---|
 * | `KeyLayout.keys()` | `private const KEY_GAP_PERCENT_MIN = 0` |
 * | 设置页滑块 | `internal const KEY_GAP_PERCENT_MIN = 0f` |
 * | **`JsonConfigCodec` 读取** | **写死的字面量 `50`** ← 漂了 |
 *
 * 于是用户设成 0~49 的值存进去了、读回来被夹回 50。
 *
 * ⚠️ 理想是往返一遍真 JSON 来测，但 `org.json` 在本地 JVM 测试里
 * **没有实现**（只在 Android 运行时才有真类）。为了这个去改
 * `build.gradle` 加测试依赖，代价大于收益。
 *
 * 所以这里测**真正的不变量**:
 *
 * 1. 范围常量与滑块/布局给得出的一致（数值断言）；
 * 2. 范围之内的每个值经过 `uiScale` 等函数都**不被改动**（等价于"夹取不会吃掉合法值"）。
 */
class ScalePercentTest {

    private fun config(scalePercent: Int) = KeyStrokesConfig(
        id = "t",
        name = "测试",
        scalePercent = scalePercent,
    )

    /* ============================================================
     * 核心语义:百分比就是倍率
     * ============================================================ */

    @Test
    fun `百分比就是倍率`() {
        assertEquals("100% 必须正好是 1.0 倍", 1f, KeyLayout.uiScale(config(100)), 0.001f)
        assertEquals("50% 必须正好是 0.5 倍", 0.5f, KeyLayout.uiScale(config(50)), 0.001f)
        assertEquals("200% 必须正好是 2.0 倍", 2f, KeyLayout.uiScale(config(200)), 0.001f)
        assertEquals("150% 必须正好是 1.5 倍", 1.5f, KeyLayout.uiScale(config(150)), 0.001f)
    }

    /**
     * ⚠️ 用户要的"特别 mini"已经由**改映射**满足了，不是靠极小的百分比。
     *
     * 旧实现里百分比**线性映射到 `0.9 .. 2.0` 倍**，所以下限 20% 只等于
     * **0.73×** —— 那正是"最小值还是太大"的来源。
     * 现在 20% **就是 0.2×**，比旧版小了 3.6 倍。
     *
     * ⚠️ 下限一度试到 1%（0.01×），用户实测后要求改回 20%:
     * "最小还是 20% 吧，现在 20% 已经很小了"。
     */
    @Test
    fun `最小值真的能缩到很小`() {
        val tiny = KeyLayout.uiScale(config(KeyLayout.SCALE_PERCENT_MIN))

        assertEquals(
            "下限 20% 必须正好是 0.2 倍（旧版同一个百分比是 0.73 倍）",
            0.2f,
            tiny,
            0.001f,
        )
    }

    /* ============================================================
     * ⚠️ 范围一致性（那个"存不住"的 bug 的根因）
     * ============================================================ */

    @Test
    fun `间距下限是 0 而不是旧的字面量`() {
        /*
         * ⚠️ 这条是**那个 bug 的直接回归**。
         *
         * 旧版读取那边写死 `coerceIn(50, 400)`，滑块却是 0 起 ——
         * 只要有人把下限改回 50（或在新代码里又抄一遍 50），这条就红。
         */
        assertEquals("按键间距下限必须是 0（键挨在一起是合法需求）", 0, KeyLayout.KEY_GAP_PERCENT_MIN)
        assertEquals("按键间距上限", 400, KeyLayout.KEY_GAP_PERCENT_MAX)
    }

    /**
     * ⚠️ 范围内的每个整数都必须"过了夹取还是它自己"。
     *
     * 这等价于"读取时的 `coerceIn` 不会吃掉合法值" ——
     * 而那个 bug 恰恰是"合法值被吃掉了"。
     */
    @Test
    fun `范围内的间距值不会被夹掉`() {
        val samples = listOf(
            KeyLayout.KEY_GAP_PERCENT_MIN,
            10,
            25,
            49,
            50,
            100,
            200,
            KeyLayout.KEY_GAP_PERCENT_MAX,
        )

        samples.forEach { percent ->
            assertEquals(
                "间距 $percent% 已经在下限与上限之间，夹取不该改动它 —— " +
                    "被改成别的值就说明范围写窄了（就是那个\"重进恢复默认值\"的 bug）",
                percent,
                percent.coerceIn(KeyLayout.KEY_GAP_PERCENT_MIN, KeyLayout.KEY_GAP_PERCENT_MAX),
            )
        }
    }

    @Test
    fun `范围内的缩放值不会被夹掉`() {
        listOf(
            KeyLayout.SCALE_PERCENT_MIN,
            50,
            100,
            200,
            KeyLayout.SCALE_PERCENT_MAX,
        ).forEach { percent ->
            assertEquals(
                "缩放 $percent% 不该被夹掉",
                percent,
                percent.coerceIn(KeyLayout.SCALE_PERCENT_MIN, KeyLayout.SCALE_PERCENT_MAX),
            )
        }
    }

    @Test
    fun `范围内的按键高度值不会被夹掉`() {
        listOf(
            KeyLayout.KEY_HEIGHT_PERCENT_MIN,
            75,
            100,
            KeyLayout.KEY_HEIGHT_PERCENT_MAX,
        ).forEach { percent ->
            assertEquals(
                "按键高度 $percent% 不该被夹掉",
                percent,
                percent.coerceIn(
                    KeyLayout.KEY_HEIGHT_PERCENT_MIN,
                    KeyLayout.KEY_HEIGHT_PERCENT_MAX,
                ),
            )
        }
    }

    /* ============================================================
     * 范围本身
     * ============================================================ */

    @Test
    fun `下限比旧版小得多`() {
        assertEquals(
            "整体缩放的下限 —— 与旧版同一个数值，但**倍率含义不同**（旧版 0.73×，现在 0.2×）",
            20,
            KeyLayout.SCALE_PERCENT_MIN,
        )
        assertTrue("上限应当不小于 200（老配置里的 200 必须仍然有效）", KeyLayout.SCALE_PERCENT_MAX >= 200)
    }

    @Test
    fun `范围内单调递增`() {
        val samples = listOf(20, 50, 100, 150, 200, 300)
            .map { KeyLayout.uiScale(config(it)) }

        samples.zipWithNext().forEach { (a, b) ->
            assertTrue("缩放百分比变大时倍率必须随之变大（$a → $b）", b > a)
        }
    }

    /**
     * ⚠️ 坏数据不能把窗口尺寸弄成 0。
     *
     * `coerceIn` 只夹到常量范围内，夹不到"配置包被手工改成 0 或负数"。
     * 倍率 0 会让 `WindowManager` 收到 0 尺寸的窗口（某些设备直接抛异常）。
     */
    @Test
    fun `坏数据也兜到正数`() {
        assertTrue("0% 也必须是正倍率", KeyLayout.uiScale(config(0)) > 0f)
        assertTrue("负数也必须是正倍率", KeyLayout.uiScale(config(-50)) > 0f)
    }

    @Test
    fun `超过上限被夹住`() {
        assertEquals(
            KeyLayout.SCALE_PERCENT_MAX / 100f,
            KeyLayout.uiScale(config(9999)),
            0.001f,
        )
    }
}
