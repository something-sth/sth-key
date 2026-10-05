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
     * ============================================================
     * ⚠️ 这个计数器是**会出人命的**（踩过）
     * ============================================================
     * 它按引用计数，所以:
     *
     * - **重复 DOWN** 会把计数顶高，而一个 UP 只还原 1 → 计数回不到 0
     *   → 那个键**永久卡在按下态**，只能重启应用（[releaseAll] 才清得掉）；
     * - **多余的 UP** 会把计数减成负数 → 之后真正的 DOWN 被抵消掉
     *   → 那个键**再也点不亮**。
     *
     * 前一种正是用户遇到的那个 bug:手柄开机瞬间轴值抖动，扳机反复跨过
     * 阈值，于是发了**多条 DOWN**。
     *
     * 根治在**调用方**（`CaptureController.setGamepadButton` 现在幂等，
     * 状态没变就不发事件），这里只做最后一道保护 ——
     * down 时**夹到 1 而不是累加**。
     *
     * @param device 设备路径；空串表示"路径未知"（某些 ROM 的公告里没有路径）
     */
    @Synchronized
    fun onKey(code: Int, pressed: Boolean, device: String?) {
        val key = device.orEmpty()
        if (pressed) {
            val byDevice = holders.getOrPut(code) { mutableMapOf() }
            /*
             * ⚠️ 夹到 1，不是 `+ 1`。
             *
             * 老写法是全项目**唯一**能把计数顶到 2 的地方，而那个 2
             * 就是一个永久卡键 —— 一个设备不可能同时把同一个键按两次，
             * 所以 1 就是上限。
             *
             * ⚠️ 这样改也保住了一个正确的场景:同一个键码由**两个不同设备**
             * 报上来（比如键盘与手柄都报了同一个键码）时，两个 key 各记 1，
             * 松开其中一个仍然按着 —— 那正是引用计数存在的意义。
             */
            byDevice[key] = 1
        } else {
            val byDevice = holders[code] ?: return
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
