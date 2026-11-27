package com.something.sthkey.capture.shell

import com.something.sthkey.capture.adb.AdbAuthorization
import com.something.sthkey.capture.shizuku.ShizukuShell
import java.io.Closeable
import java.io.InputStream

/**
 * 一条"以 shell 身份运行"的远端进程。
 *
 * 存在的意义：Shizuku 与 ADB 两条通道拿到的都是 `uid=2000` 的 shell，
 * 采集层（getevent / 手柄 helper）只关心 stdout，不该知道是谁起的进程。
 */
internal interface RemoteShellHandle : Closeable {
    val command: String
    val inputStream: InputStream
}

/** 起 shell 进程的抽象：Shizuku 走 binder，ADB 走 shell 流。 */
internal interface ShellLauncher {
    val label: String

    suspend fun start(command: String): RemoteShellHandle
}

/** Shizuku 直连（现有行为不变）。 */
internal class ShizukuShellLauncher : ShellLauncher {
    override val label: String = "Shizuku"

    override suspend fun start(command: String): RemoteShellHandle {
        val remote = ShizukuShell.start(command)
        return object : RemoteShellHandle {
            override val command: String = remote.command
            override val inputStream: InputStream = remote.inputStream
            override fun close() = remote.close()
        }
    }
}

/** ADB shell（配对或 5555 直连之后）。 */
internal class AdbShellLauncher : ShellLauncher {
    override val label: String = "ADB"

    override suspend fun start(command: String): RemoteShellHandle {
        val stream = AdbAuthorization.openShell(command)
            ?: throw IllegalStateException("ADB 未连接")
        return object : RemoteShellHandle {
            override val command: String = command
            override val inputStream: InputStream = stream.openInputStream()
            override fun close() {
                runCatching { stream.close() }
            }
        }
    }
}
