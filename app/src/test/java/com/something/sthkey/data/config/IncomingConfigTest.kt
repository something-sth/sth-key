package com.something.sthkey.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 从外部 Intent 里挑出配置包。
 *
 * ============================================================
 * 为什么测 [IncomingConfig.pickUri] 而不是 [IncomingConfig.extract]
 * ============================================================
 * 本地 JVM 单测里 `android.net.Uri` 与 `android.content.Intent` **都是空壳** ——
 * 连 `Uri.parse` 都会抛 `Method ... not mocked`
 * （这一点实测过：早先想当然以为 `Uri` 能用，结果 11 条测试全红）。
 * 而本项目的测试环境没有 Robolectric，构建又是离线的，加不了依赖。
 *
 * 所以判断层只吃 `String`，这里也只用 `String` 测。
 *
 * ============================================================
 * 这里防的是什么
 * ============================================================
 * 取不到 Uri 的表现是"点了没反应"—— 真机上只能一个个应用去试，
 * 失败时连日志都没有。而各家应用的行为差异很大：
 * 有的用 VIEW、有的用 SEND；extra 有的塞单个 Uri、有的塞列表。
 */
class IncomingConfigTest {

    private val target = "content://media/1234"
    private val other = "content://media/5678"

    /*
     * 手写真实取值，而不是引用 `Intent.ACTION_*` ——
     * 那个类在本地单测里是空壳，读它的静态字段会直接抛异常。
     * 这两个常量从 API 1 起就没变过，写死是安全的。
     */
    private val actionSend = "android.intent.action.SEND"
    private val actionView = "android.intent.action.VIEW"
    private val actionMain = "android.intent.action.MAIN"

    /* ============================================================
     * 能取到的情况
     * ============================================================ */

    @Test
    fun `ACTION_SEND 从 EXTRA_STREAM 取到`() {
        val picked = IncomingConfig.pickUri(
            action = actionSend,
            data = null,
            stream = target,
            streamList = null,
        )

        assertNotNull("ACTION_SEND 必须能从 EXTRA_STREAM 取到", picked)
        assertEquals(target, picked!!.location)
        assertTrue("来自分享", picked.fromShare)
    }

    /**
     * ⚠️ 真实的坑：官方约定 `EXTRA_STREAM` 是单个 Uri，
     * 但确实有应用（哪怕只分享一个文件）塞的是 `ArrayList`。
     * 不认这种形态的话，表现就是"某些应用分享了没反应"。
     */
    @Test
    fun `ACTION_SEND 的 EXTRA_STREAM 是列表时取第一个`() {
        val picked = IncomingConfig.pickUri(
            action = actionSend,
            data = null,
            stream = null,
            streamList = listOf(target, other),
        )

        assertNotNull("列表形态也必须认", picked)
        assertEquals("只取第一个，不做批量导入", target, picked!!.location)
    }

    @Test
    fun `ACTION_VIEW 从 data 取到`() {
        val picked = IncomingConfig.pickUri(
            action = actionView,
            data = target,
            stream = null,
            streamList = null,
        )

        assertNotNull("ACTION_VIEW 必须能从 data 取到", picked)
        assertEquals(target, picked!!.location)
        assertTrue("来自打开而非分享", !picked.fromShare)
    }

    /** 不规范但存在：有的应用在 VIEW 时把 Uri 只放进 extra */
    @Test
    fun `ACTION_VIEW 只有 extra 时也能取到`() {
        val picked = IncomingConfig.pickUri(
            action = actionView,
            data = null,
            stream = target,
            streamList = null,
        )

        assertNotNull(picked)
        assertEquals(target, picked!!.location)
    }

    /**
     * ⚠️ `ACTION_SEND` **不看 `data`**。
     *
     * 两个 action 的约定不同：SEND 用 extra、VIEW 用 data。
     * 混用会取到一个不相干的东西（或者取不到）。
     */
    @Test
    fun `ACTION_SEND 不把 data 当成文件`() {
        val picked = IncomingConfig.pickUri(
            action = actionSend,
            data = other,
            stream = target,
            streamList = null,
        )

        assertEquals("应当用 extra 而不是 data", target, picked?.location)
    }

    /* ============================================================
     * 取不到的情况 —— 必须安静地返回 null，不能抛
     * ============================================================ */

    @Test
    fun `普通启动的 Intent 取不到`() {
        val picked = IncomingConfig.pickUri(
            action = actionMain,
            data = target,
            stream = target,
            streamList = listOf(target),
        )

        assertNull("主启动 Intent 不该被当成导入，哪怕带着 Uri", picked)
    }

    @Test
    fun `action 为 null 时取不到而不崩`() {
        assertNull(
            IncomingConfig.pickUri(
                action = null,
                data = target,
                stream = target,
                streamList = null,
            ),
        )
    }

    @Test
    fun `ACTION_SEND 但没带任何 extra 时取不到`() {
        assertNull(
            IncomingConfig.pickUri(
                action = actionSend,
                data = target,
                stream = null,
                streamList = null,
            ),
        )
    }

    @Test
    fun `ACTION_VIEW 但什么都没带时取不到`() {
        assertNull(
            IncomingConfig.pickUri(
                action = actionView,
                data = null,
                stream = null,
                streamList = null,
            ),
        )
    }

    /** 空列表不该被当成"有文件" */
    @Test
    fun `EXTRA_STREAM 是空列表时取不到`() {
        assertNull(
            IncomingConfig.pickUri(
                action = actionSend,
                data = null,
                stream = null,
                streamList = emptyList(),
            ),
        )
    }

    /**
     * ⚠️ **不按 MIME 过滤**，这是刻意的。
     *
     * 各家应用报的 MIME 五花八门，按 MIME 过滤会把一部分来源**静默丢掉** ——
     * 在用户看来就是"这个功能时好时坏"。
     *
     * 收下之后由 `ConfigPackageManager.import` 校验 manifest，
     * 不是我们的包会明确报错。**宁可多收一个再报错，也不要静默不响应。**
     *
     * 注意 [IncomingConfig.pickUri] **压根没有 MIME 参数** —— 这条测试是
     * 结构性的：如果以后有人加了 MIME 参数，这个调用就对不上了，
     * 于是会被迫回来看这段说明。
     */
    @Test
    fun `取包时不看 MIME`() {
        val picked = IncomingConfig.pickUri(
            action = actionSend,
            data = null,
            stream = target,
            streamList = null,
        )

        assertNotNull("无论 MIME 是什么都应当收下，交给内容校验兜底", picked)
    }
}
