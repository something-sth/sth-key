package com.something.sthkey.capture

/**
 * 记录"每个按下的键由哪些设备持有"。
 *
 * ============================================================
 * 为什么需要它
 * ============================================================
 * 全局监听（`getevent -lt`）下，物理设备可能在**按键还按着**的时候被拔掉 ——
 * 那种情况内核**不会**补发 UP 事件。不处理的话那个键会**永久卡在按下态**：
 * 悬浮窗上一直亮着，用户完全不知道为什么，只能去重启监听。
 *
 * 而"清理"不能粗暴地清空全部按键：键盘上按着 `W`、这时拔掉鼠标 ——
 * 清空会把 `W` 也灭掉，而它明明还按着。用户看到的是
 * "动了动鼠标，键盘上按着的键突然灭了"。
 *
 * 所以要知道**每个键由哪些设备按着**，只释放"走掉那个设备独有的"。
 *
 * ============================================================
 * ⚠️ 用计数而不是布尔集合
 * ============================================================
 * 同一个键可能**同时**来自多个输入节点（一个手柄可能既暴露标准
 * evdev 节点、又暴露厂商扩展节点）。用 `Set<String>` 记"持有者"
 * 也能表达，但每次增删都要分配集合；用计数更省，语义也够：
 * 计数归零 = 没人在按。
 *
 * ============================================================
 * 线程模型
 * ============================================================
 * 只在**读取线程**上被调用（读循环里串行），所以方法都加 `synchronized`
 * 只是为了 [releaseDevice] 可能被其它线程看到时不出错 —— 实际上
 * 目前两者都在同一个线程。
 */
internal class HeldKeyTracker {

    /** 键码 → 持有它的设备 → 该设备上的引用计数 */
    private val holders = mutableMapOf<Int, MutableMap<String, Int>>()

    /**
     * 记录一个按键边沿。
     *
     * @param device 设备路径；空串表示"路径未知"（某些 ROM 的公告里没有路径）
     */
    @Synchronized
    fun onKey(code: Int, pressed: Boolean, device: String?) {
        if (pressed) {
            val byDevice = holders.getOrPut(code) { mutableMapOf() }
            byDevice[device.orEmpty()] = (byDevice[device.orEmpty()] ?: 0) + 1
        } else {
            val byDevice = holders[code] ?: return
            val key = device.orEmpty()
            val next = (byDevice[key] ?: 0) - 1
            if (next <= 0) byDevice.remove(key) else byDevice[key] = next
            if (byDevice.isEmpty()) holders.remove(code)
        }
    }

    /**
     * 某个设备走了，交出"需要补发 UP 的键码"。
     *
     * @param device 走掉的设备路径；**空串表示路径未知** ——
     *   那种情况无法判断是哪一个，于是释放**全部**键。
     *
     *   保守但安全：漏清会永久卡键，误清只是"松手了"，
     *   用户再按一下就回来。
     */
    @Synchronized
    fun releaseDevice(device: String): List<Int> {
        if (device.isEmpty()) {
            val all = holders.keys.toList()
            holders.clear()
            return all
        }

        val released = mutableListOf<Int>()
        val iterator = holders.entries.iterator()
        while (iterator.hasNext()) {
            val (code, byDevice) = iterator.next()
            byDevice.remove(device)
            if (byDevice.isEmpty()) {
                released += code
                iterator.remove()
            }
        }
        return released
    }

    /** 当前有多少个键被认为按着（诊断用） */
    @Synchronized
    fun heldCount(): Int = holders.size

    /** 全部释放，返回键码（停止采集时用） */
    @Synchronized
    fun releaseAll(): List<Int> {
        val all = holders.keys.toList()
        holders.clear()
        return all
    }
}
