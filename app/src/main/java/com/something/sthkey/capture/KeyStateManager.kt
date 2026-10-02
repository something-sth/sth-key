package com.something.sthkey.capture

/**
 * 按键状态机。
 *
 * 把一串"按下 / 抬起 / 重复"的原始事件，归约成"当前哪些键是按下的"。
 *
 * ============================================================
 * 为什么必须由它来做（而不是直接相信 value）
 * ============================================================
 * - 键盘会长按自动重复（value = 2）：如果把它当成"按下"再处理一次，
 *   会反复触发状态变化；如果误当成"抬起"，键就会一闪一闪。
 * - 设备断开、读取线程被杀时可能丢掉"抬起"事件，留下幽灵按键。
 *   因此停止采集时上层必须调用 [clear]。
 *
 * 线程安全：采集线程会调 [update]，主线程可能读 [pressedKeys]，
 * 因此内部统一用 synchronized 保护。
 */
class KeyStateManager {

    private val keys = mutableSetOf<Int>()

    /**
     * 处理一条按键事件。
     *
     * @param code  Linux 按键码（evdev）
     * @param value 0 抬起 / 1 按下 / 2 重复
     */
    @Synchronized
    fun update(code: Int, value: Int) {
        when (value) {
            InputEvent.VALUE_DOWN -> keys.add(code)
            InputEvent.VALUE_UP -> keys.remove(code)
            // REPEAT 不改变状态：键已经按下，重复事件没有新信息
            InputEvent.VALUE_REPEAT -> Unit
        }
    }

    /** 当前按下的键（快照，外部修改不影响内部状态） */
    @Synchronized
    fun snapshot(): Set<Int> = keys.toSet()

    /** 清空状态；停止采集、配置切换时必须调用 */
    @Synchronized
    fun clear() {
        keys.clear()
    }
}
