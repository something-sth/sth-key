package com.something.sthkey.data.config

import com.something.sthkey.domain.custom.ComponentCategory
import com.something.sthkey.domain.custom.ComponentType
import com.something.sthkey.domain.custom.JoystickSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三种摇杆的**类型 ↔ 输入源**映射。
 *
 * ============================================================
 * ⚠️⚠️ 这个测试防的是一次"静默串味"，而且它极难被发现
 * ============================================================
 * 三种摇杆**共用同一个数据类** `JoystickComponent`，只靠
 * [JoystickComponent.source] 区分。而存档写的是 [ComponentType.id] ——
 * 于是"存成哪个 id、读回哪个 source"这条映射就是**唯一的桥**。
 *
 * 桥写错的话:
 *
 * | 写错的方式 | 症状 |
 * |---|---|
 * | 三个都存成 `joystick` | 加了个键盘摇杆，**重启后它开始跟手柄动** |
 * | `sourceOf` 漏一个分支 | 同上（落进默认值 = 手柄） |
 * | 两个类型用了同一个 id | 其中一个**永远存不住自己的类型** |
 *
 * ⚠️ 而"重启后才串味"意味着**当次编辑完全正常** —— 用户会以为
 * "是随机出现的毛病"，而开发者复现时要先想到"存档"这一层。
 *
 * ============================================================
 * ⚠️ 为什么直接测这两个函数，而不是 `encode`/`decode` 往返
 * ============================================================
 * 那两个走 `org.json`，而它在 JVM 单测里是**没有实现的桩**
 * （项目里所有测试都因此绕开编解码）。
 *
 * ⚠️ 但这里要防的那部分（映射）是**纯函数**，与 JSON 无关 ——
 * 直接测它反而更准:JSON 那一层只是把字符串搬来搬去，不会改变映射。
 */
class JoystickTypeMappingTest {

    @Test
    fun `三种输入源的类型 id 互不相同`() {
        val ids = JoystickSource.entries.map { ComponentType.of(it).id }
        assertEquals(
            "三个摇杆不能用同一个 type id（否则有一个永远存不住自己的类型）: $ids",
            ids.size,
            ids.distinct().size,
        )
    }

    @Test
    fun `每个输入源都能原样往返`() {
        JoystickSource.entries.forEach { source ->
            val typeId = ComponentType.of(source).id
            val type = ComponentType.fromId(typeId)
            assertTrue("typeId 「$typeId」认不出来（fromId 返回 null）", type != null)

            val back = ComponentType.joystickSource(type!!)
            assertEquals(
                "$source 存成「$typeId」之后读回来变成了 $back",
                source,
                back,
            )
        }
    }

    /**
     * ⚠️ 反向也要查:每个 `JOYSTICK*` 类型都必须能换回一个输入源，
     * 而且**不能全落进 GAMEPAD**（那正是"漏一个分支"的表现）。
     */
    @Test
    fun `三个摇杆类型各自映射到不同的输入源`() {
        val types = ComponentType.entries.filter { it.id.startsWith("joystick") }
        assertEquals("摇杆类型应该正好三个", 3, types.size)

        val sources = types.map { ComponentType.joystickSource(it) }
        assertEquals(
            "三个摇杆类型不能映射到同一个输入源（漏分支就会这样）: $sources",
            sources.size,
            sources.distinct().size,
        )
    }

    /**
     * ⚠️ 两个新摇杆**必须都在「键盘」栏** —— 用户的原话是
     * "我想加两个**键盘栏**的组件，都是摇杆"。
     *
     * ⚠️ 手柄摇杆留在「手柄」栏（用户明确说了"保留原样，只新增两个"）。
     */
    @Test
    fun `键盘摇杆在键盘栏、手柄摇杆在手柄栏`() {
        assertEquals(ComponentCategory.KEYBOARD, ComponentType.JOYSTICK_KEYBOARD.category)
        assertEquals(ComponentCategory.KEYBOARD, ComponentType.JOYSTICK_MOUSE.category)
        assertEquals(ComponentCategory.GAMEPAD, ComponentType.JOYSTICK.category)
    }

    /**
     * ⚠️ [ComponentType.id] 会写进 JSON，**发布之后不能改**。
     *
     * 这个测试把当前的值钉住:以后有人"顺手改个名字"时会红，
     * 而那时他才有机会意识到"老配置会读不出来"。
     */
    @Test
    fun `类型 id 是稳定的（写进 JSON，不能改）`() {
        assertEquals("key", ComponentType.KEY.id)
        assertEquals("text", ComponentType.TEXT.id)
        assertEquals("joystick", ComponentType.JOYSTICK.id)
        assertEquals("joystick_keyboard", ComponentType.JOYSTICK_KEYBOARD.id)
        assertEquals("joystick_mouse", ComponentType.JOYSTICK_MOUSE.id)
    }
}
