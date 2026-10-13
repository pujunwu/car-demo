package com.example.phone.newapp.protocol

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机端局域网 UDP 自动探测监听工具 (自包含独立实现)
 */
object UdpDiscovery {
    private const val TAG = "PhoneUdpDiscovery"
    const val DISCOVERY_PORT = 22346
    private const val BEACON_PREFIX = "CAR_SCREEN_BEACON:"

    /**
     * 手机端监听器：接收车机端定时广播的心跳信标 (Beacon)，免手动输入 IP
     */
    class BeaconListener(
        private val onCarDiscovered: (carIp: String, carPort: Int) -> Unit
    ) {
        private val isRunning = AtomicBoolean(false)
        private var socket: DatagramSocket? = null
        private var workerThread: Thread? = null

        fun start() {
            if (isRunning.getAndSet(true)) return

            workerThread = Thread({
                try {
                    val s = DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(DISCOVERY_PORT))
                    }
                    socket = s
                    Log.i(TAG, "[UDP监听] 启动车机局域网组播探测监听 (端口 $DISCOVERY_PORT)...")

                    val buffer = ByteArray(512)
                    val packet = DatagramPacket(buffer, buffer.size)

                    while (isRunning.get()) {
                        try {
                            s.receive(packet)
                            val message = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                            if (message.startsWith(BEACON_PREFIX)) {
                                val content = message.removePrefix(BEACON_PREFIX)
                                val parts = content.split(":")
                                if (parts.size == 2) {
                                    val ip = parts[0]
                                    val port = parts[1].toIntOrNull() ?: 22456
                                    onCarDiscovered(ip, port)
                                }
                            }
                        } catch (e: Exception) {
                            if (isRunning.get()) {
                                Log.w(TAG, "[UDP监听] 接收心跳异常: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "[UDP监听] 监听服务退出", e)
                    }
                } finally {
                    stop()
                }
            }, "PhoneBeaconListenerThread").apply { start() }
        }

        fun stop() {
            if (!isRunning.getAndSet(false)) return
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            workerThread = null
            Log.i(TAG, "[UDP监听] 监听服务已停止")
        }
    }
}
