package com.something.sthkey.capture

import com.something.sthkey.core.log.AppLog
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * root 输入源。
 *
 * ============================================================
 * 实现方式（沿用以验证过的旧方案）
 * ============================================================
 * 每个设备一个子进程 `su -c cat /dev/input/eventX`，
 * 由一个专用线程按固定长度读取并解析 `struct input_event`。
 *
 * 为什么不用 FileInputStream 直接读节点：
 * 普通应用进程即使有 root 授权，也没有权限 open 那些设备节点
 * （SELinux 限制），必须让 `su` 派生的 shell 去读。
 *
 * ============================================================
 * 线程模型（旧项目踩过坑的地方）
 * ============================================================
 * - 一设备一线程，**全部是后台线程**；读取是阻塞式 read，
 *   因此停止时必须主动 `destroy()` 子进程（中断线程无法唤醒阻塞的 read）。
 * - [onEvent] / [onError] 回调**发生在这条读取线程上**，
 *   调用方（[CaptureController]）负责把状态切换到主线程，
 *   绝不能在回调里直接碰 Compose 的 UI 状态。
 * - 停止是幂等的：重复 stop 不会抛异常。
 */
class RootInputSource : InputSource {

    override val channel: CaptureChannel = CaptureChannel.ROOT

    private val running = AtomicBoolean(false)

    private val workers = mutableListOf<DeviceWorker>()

    override fun isRunning(): Boolean = running.get()

    override fun start(
        devicePaths: List<String>,
        onEvent: (InputEvent) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) {
            AppLog.w(TAG, "已经在运行，忽略重复启动")
            return
        }

        if (devicePaths.isEmpty()) {
            running.set(false)
            onError("没有可读取的输入设备")
            return
        }

        AppLog.i(TAG, "启动 root 采集，设备数 ${devicePaths.size}")

        synchronized(workers) {
            workers.clear()
            devicePaths.forEach { path ->
                val worker = DeviceWorker(
                    eventPath = path,
                    isRunning = { running.get() },
                    onEvent = onEvent,
                    onError = onError,
                )
                workers += worker
                worker.start()
            }
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) {
            // 没在运行也要清一遍，保证"停止后没有任何残留线程/进程"
            releaseWorkers()
            return
        }

        AppLog.i(TAG, "停止 root 采集")
        releaseWorkers()
    }

    private fun releaseWorkers() {
        synchronized(workers) {
            workers.forEach { it.stop() }
            workers.clear()
        }
    }

    /**
     * 单个设备的读取线程。
     *
     * 一个设备一个实例：任一设备断开只影响自己，
     * 不会把其它设备的监听一起带崩。
     */
    private class DeviceWorker(
        private val eventPath: String,
        private val isRunning: () -> Boolean,
        private val onEvent: (InputEvent) -> Unit,
        private val onError: (String) -> Unit,
    ) {
        private val stopped = AtomicBoolean(false)

        @Volatile
        private var thread: Thread? = null

        @Volatile
        private var process: Process? = null

        fun start() {
            if (thread != null) return
            thread = Thread({ readLoop() }, "RootInput-${eventPath.substringAfterLast('/')}")
                .also {
                    it.isDaemon = true
                    it.start()
                }
        }

        fun stop() {
            stopped.set(true)
            // 先杀子进程：read 是阻塞的，不打断进程线程醒不过来
            try {
                process?.destroy()
            } catch (_: Exception) {
            }
            process = null
            thread?.interrupt()
            thread = null
        }

        private fun readLoop() {
            try {
                val command = arrayOf("su", "-c", "cat ${shellQuote(eventPath)}")
                val proc = ProcessBuilder(*command)
                    .redirectErrorStream(true)
                    .start()
                process = proc

                val input = BufferedInputStream(proc.inputStream)
                val buffer = ByteArray(EVENT_SIZE)

                while (!stopped.get() && isRunning()) {
                    var offset = 0
                    while (offset < EVENT_SIZE) {
                        if (stopped.get()) return
                        val read = input.read(buffer, offset, EVENT_SIZE - offset)
                        if (read < 0) {
                            // 读到头：设备断开或 su/cat 退出
                            throw IllegalStateException("读取结束（设备断开或 su 退出）")
                        }
                        offset += read
                    }

                    parse(buffer)?.let(onEvent)
                }
            } catch (e: Exception) {
                // 主动停止时抛出的异常是正常的，不要报给用户
                if (!stopped.get() && isRunning()) {
                    AppLog.w(TAG, "$eventPath 读取中断：${e.javaClass.simpleName}")
                    onError("$eventPath 读取失败：${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                stopped.set(true)
                try {
                    process?.destroy()
                } catch (_: Exception) {
                }
                process = null
                AppLog.d(TAG, "$eventPath 读取线程结束")
            }
        }

        /**
         * 解析一条 input_event。
         *
         * 结构（64 位设备）：timeval(16) + type(2) + code(2) + value(4) = 24 字节，
         * 全部小端。旧项目按固定 24 字节读，实测可用。
         *
         * 只向上转发三类事件，其余（EV_MSC 等）直接丢弃 —— 少一次回调少一次开销。
         */
        private fun parse(buffer: ByteArray): InputEvent? {
            val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            bb.long // tv_sec
            bb.long // tv_usec
            val type = bb.short.toInt() and 0xFFFF
            val code = bb.short.toInt() and 0xFFFF
            val value = bb.int

            return when (type) {
                InputEvent.EV_KEY -> {
                    // 只接受合法的按键值：0 抬起 / 1 按下 / 2 重复
                    if (value != InputEvent.VALUE_UP &&
                        value != InputEvent.VALUE_DOWN &&
                        value != InputEvent.VALUE_REPEAT
                    ) {
                        null
                    } else {
                        InputEvent(type, code, value)
                    }
                }

                InputEvent.EV_REL -> {
                    // 鼠标移动：只关心 X/Y，且忽略 0 位移
                    if ((code == InputEvent.REL_X || code == InputEvent.REL_Y) && value != 0) {
                        InputEvent(type, code, value)
                    } else {
                        null
                    }
                }

                InputEvent.EV_SYN -> InputEvent(type, code, value)

                else -> null
            }
        }

        /** 防止路径里的特殊字符破坏 shell 命令 */
        private fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"
    }

    private companion object {
        const val TAG = "RootCapture"

        /** struct input_event 在 64 位 Android 上的长度 */
        const val EVENT_SIZE = 24
    }
}
