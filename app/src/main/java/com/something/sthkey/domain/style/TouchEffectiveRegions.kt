package com.something.sthkey.domain.style

import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.TouchRegionBinding
import com.something.sthkey.domain.config.TouchRegionSpec
import com.something.sthkey.domain.config.TouchSettings

/**
 * 触屏样式这一刻**真正生效的区域** = 用户配过的 + 没配过的槽位按**默认坐标**补齐。
 *
 * ============================================================
 * ⚠️⚠️ 为什么要有"默认区域"（用户反馈）
 * ============================================================
 * 用户的原话:
 *
 * > 新建的触屏配置现在不会有反应，默认的触摸区域位置没有实际作用，
 * > 开启时会显示"这个配置还没有配置"，按键不会亮……
 * > 修改为：默认的触摸区域位置也起效果，但是 toast 依然存在，只是换个说法
 *
 * ⚠️ 原来的行为:悬浮窗只吃 [TouchSettings.activeRegions]（`pinned == true`），
 * 而新建配置的 `regions` 是**空的** —— 于是:
 *
 * | 现象 | 原因 |
 * |---|---|
 * | 碰哪里都没反应 | 没有任何区域，触点不归属任何槽位 |
 * | 按键不亮 | 同上（触屏的点亮判据是"槽位被按到"）|
 * | toast 说"还没有配置" | 判据是 `activeRegions.isEmpty()`，确实没配 |
 *
 * ⚠️ 而编辑器里其实**一直画着**默认位置的方块（`TouchRegionSpec.defaultCenterFor`）——
 * 只是那些方块是"给你拖的占位"，不碰就不写进配置、也不生效。
 * 用户看到的是"编辑器里有方块，开悬浮窗却没反应"，那当然说不通。
 *
 * 现在统一成:**没配过的槽位 = 用默认坐标**。用户配过的那些原样保留。
 *
 * ============================================================
 * ⚠️ 默认坐标从哪来（必须与编辑器**同一个函数**）
 * ============================================================
 * | 区域 | 默认 |
 * |---|---|
 * | 轮盘 | [TouchRegionSpec.MOVE_DEFAULT]（左下）|
 * | 按键槽位 | [TouchRegionSpec.defaultCenterFor]（从右上阶梯排开）|
 *
 * ⚠️ 槽位列表取 `KeyLayout.keys(config)`（排除两个摇杆槽位），
 * 与渲染、与编辑器**同一个函数** —— 自己另列一份的话，
 * 以后布局加槽位就会出现"悬浮窗上有、但没默认区域"。
 *
 * ⚠️ 合成出来的条目 `pinned = true`:下游凡是按 `pinned` 过滤的地方
 * （以及旧编辑器读回来的判断）都把它当成"真的存在"。
 * 它只活在内存里，**不会**被写进配置 —— 用户不动它，配置里就仍然没有它。
 */
fun touchEffectiveRegions(config: KeyStrokesConfig): List<TouchRegionBinding> {
    /*
     * ⚠️ 只认 `pinned == true`:
     * `regions` 里可能残留编辑器画出来的占位条目（`pinned == false`），
     * 那些从来没有"生效"过，不该盖掉默认坐标。
     */
    val configured = config.touch.regions
        .filter { it.pinned }
        .associateBy { it.slotId }

    val slots = touchButtonSlotIds(config)

    return buildList {
        /* 轮盘:始终存在（没有它就没有移动摇杆） */
        add(
            configured[TouchSettings.JOYSTICK_SLOT_ID] ?: TouchRegionBinding(
                slotId = TouchSettings.JOYSTICK_SLOT_ID,
                centerX = TouchRegionSpec.MOVE_DEFAULT.centerX,
                centerY = TouchRegionSpec.MOVE_DEFAULT.centerY,
                size = TouchRegionSpec.MOVE_DEFAULT.size,
                pinned = true,
            ),
        )

        slots.forEachIndexed { index, slotId ->
            val saved = configured[slotId]
            if (saved != null) {
                add(saved)
            } else {
                val (centerX, centerY) = TouchRegionSpec.defaultCenterFor(index)
                add(
                    TouchRegionBinding(
                        slotId = slotId,
                        centerX = centerX,
                        centerY = centerY,
                        size = TouchRegionSpec.SIZE_DEFAULT,
                        pinned = true,
                    ),
                )
            }
        }
    }
}

/**
 * 触屏样式里**会显示在悬浮窗上的按键槽位**（按渲染顺序）。
 *
 * ⚠️ 与 `KeyLayout.keys` 同源:那里画什么，这里就能有哪些槽位。
 * 两个摇杆槽位被排除 —— 轮盘由 [TouchSettings.JOYSTICK_SLOT_ID] 单独表示，
 * 给摇杆再配一块"按键区域"只会让同一根手指被算两次。
 */
fun touchButtonSlotIds(config: KeyStrokesConfig): List<String> =
    KeyLayout.keys(config)
        .map { it.slotId }
        .filter { it != KeyLayout.Id.JOYSTICK_LEFT && it != KeyLayout.Id.JOYSTICK_RIGHT }
        .distinct()
