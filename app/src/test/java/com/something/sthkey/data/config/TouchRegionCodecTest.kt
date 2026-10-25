package com.something.sthkey.data.config

import com.something.sthkey.domain.custom.CustomTouchRegion
import com.something.sthkey.domain.custom.CustomTouchRegionBinding
import com.something.sthkey.domain.custom.TouchDirection
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **触摸区域的持久化**（源码层断言）。
 *
 * ============================================================
 * ⚠️⚠️ 为什么是"读源码"而不是真的往返一次
 * ============================================================
 * `CustomLayoutCodec` 依赖 `org.json`，而它在 **JVM 单元测试里是个桩**
 * （方法全抛 `RuntimeException`）—— 所以"编码再解码"那类测试
 * **在 JVM 上跑不起来**（这一条我实测过:一个往返诊断测试连编译都过不了）。
 *
 * ⚠️ 那就没有别的办法证明"它真的写进去了"吗？有 ——
 * 用户已经被这一类 bug 坑过一次:
 *
 * > 实际测试**只有移动摇杆**的触摸区域转过来了**别的都没有**
 *
 * ⚠️ 那次正是"字段没被写进 JSON"（旧模型里区域挂在组件上，
 * 而那条路悄悄丢了）。所以这里守住**最容易再犯的那一点**:
 * 编解码里有没有真的处理 `touchRegions`。
 *
 * ⚠️ 这类测试**不能证明运行结果**，只能证明"那段代码还在" ——
 * 它挡的是"以后重构时顺手把这一段删了/漏了"，那才是真正会复发的东西。
 * 真机验证仍然要用户做。
 */
class TouchRegionCodecTest {

    private val codec: String by lazy {
        File(
            "src/main/java/com/something/sthkey/data/config/CustomLayoutCodec.kt",
        ).readText()
    }

    @Test
    fun `编码时写 touchRegions`() {
        assertTrue(
            "CustomLayoutCodec.encode 里没有写 touchRegions —— " +
                "用户新建的触摸区域在重启后会全部消失",
            codec.contains("\"touchRegions\""),
        )
    }

    @Test
    fun `解码时读 touchRegions`() {
        assertTrue(
            "CustomLayoutCodec.decode 里没有读 touchRegions",
            codec.contains("decodeTouchRegions("),
        )
    }

    @Test
    fun `区域的四个字段一个都不能少`() {
        /*
         * ⚠️ 逐字段守:少任何一个都是一种**具体的**故障（见 `encodeTouchRegion`
         * 的注释里那张表）—— 而它们的症状互不相同，
         * 所以不能"看到方法在"就算过了。
         *
         * ⚠️ 匹配的是**带引号、带逗号**的键字面量（`"region",`）——
         * 因为四个字段里有两个是 `put("id", …)`（值同行）、
         * 另外两个是 `put(` 换行再写 `"region",`（值是个大对象）。
         * 只匹配一种形式会误报（我第一版就是这么写的，测试当场抓到了）。
         */
        listOf("id", "kind", "region", "bindings").forEach { field ->
            assertTrue(
                "encodeTouchRegion 里少了 $field —— 读回来时那一项会退化",
                codec.contains("\"$field\","),
            )
        }
    }

    @Test
    fun `绑定的方向也写进去`() {
        assertTrue(
            "绑定没有写 direction —— 轮盘区域绑的按键会变成\"摇到哪儿都触发\"",
            codec.contains("binding.direction?.let { put(\"direction\", it.id) }"),
        )
    }

    @Test
    fun `没有区域的条目会整条丢掉（不是读成空区域）`() {
        /*
         * ⚠️ 坐标是 NaN 的区域会让命中测试**永远失败**，
         * 而那个症状（"这块区域按不亮"）极难查到。
         */
        assertTrue(
            "decodeTouchRegions 没有丢弃缺区域的条目",
            codec.contains("跳过没有区域的触摸区域"),
        )
    }

    @Test
    fun `没有 id 的区域也会丢掉`() {
        /*
         * ⚠️ 没有 id 就删不掉、也绑不了 —— 那会变成一块
         * "界面上看得见但永远处理不了"的野区域。
         */
        assertTrue(
            "decodeTouchRegions 没有丢弃缺 id 的条目",
            codec.contains("跳过没有 id 的触摸区域"),
        )
    }

    @Test
    fun `区域几何用同一套 clamp`() {
        /*
         * ⚠️ 与触屏样式**同一个** `TouchRegionSpec.clamp` ——
         * 两处各写一套范围的话，手改过的 JSON 会让区域大到覆盖全屏，
         * 而用户看不出是"被夹过"还是"自己拖成这样的"。
         */
        assertTrue(
            "区域几何没有 clamp",
            codec.contains("TouchRegionSpec.clamp("),
        )
    }

    @Test
    fun `不存在旧模型残留的 touchBindings 编解码`() {
        /*
         * ⚠️ 反向断言:旧模型（区域挂在组件上）已经**整个撤掉**，
         * 留着那些方法会让下一个人以为"组件上还能挂区域"。
         */
        assertTrue(
            "CustomLayoutCodec 里还留着旧模型的 touchBindings 编解码 —— " +
                "那是已经被否掉的方案（见 CustomTouchRegion 的说明）",
            !codec.contains("encodeTouchBindings") && !codec.contains("decodeTouchBindings"),
        )
    }

    @Test
    fun `数据模型本身默认是空的`() {
        /*
         * ⚠️ 与 `CustomTouchRegionTest` 那条配对 —— 这里守住的是
         * "编码端不会给一个没有区域的布局凭空造出一块"。
         */
        assertTrue(
            "默认布局带了触摸区域 —— 纯键盘/手柄用户会被白起一份触摸采集",
            com.something.sthkey.domain.custom.CustomLayoutSettings()
                .touchRegions
                .isEmpty(),
        )
    }

    /* ⚠️ 下面两个只是让上面的 import 有意义（编译期检查字段名没被改） */
    @Suppress("unused")
    private fun shape(): CustomTouchRegion = CustomTouchRegion(
        id = "x",
        kind = CustomTouchRegion.Kind.MOVE_STICK,
        region = com.something.sthkey.domain.config.TouchRegion(0.5f, 0.5f, 0.2f),
        bindings = listOf(
            CustomTouchRegionBinding(componentId = "c", direction = TouchDirection.LEFT),
        ),
    )
}
