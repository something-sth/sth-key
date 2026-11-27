package com.something.sthkey.capture.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** 探测本机 5555（或用户指定端口）是否有 adbd 在监听。 */
internal object AdbPortProbe {

    suspend fun probe(port: Int = AdbAuthorization.DEFAULT_PORT): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 350)
                    true
                }
            }.getOrDefault(false)
        }
}
