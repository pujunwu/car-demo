package com.example.car.newapp.protocol

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网 UDP 自动发现工具类 (自包含独立实现)
 */
object UdpDiscovery {
    private const val TAG = "CarUdpDiscovery"
    const val DISCOVERY_PORT = 22346
    private const val BEACON_PREFIX = "CAR_SCREEN_BEACON:"

    /**
     * 车机端广播器：定期向局域网广播自己的 IP 与 WebRTC 信令端口
     */
    class BeaconBroadcaster(
        private val carIp: String,
        private val carPort: Int,
        private val intervalMs: Long = 1000L
    ) {
        private val isRunning = AtomicBoolean(false)
        private var socket: DatagramSocket? = null
        private var workerThread: Thread? = null

        fun start() {
            if (isRunning.getAndSet(true)) return

            workerThread = Thread({
                try {
                    val s = DatagramSocket()
                    s.broadcast = true
                    socket = s

                    val message = "$BEACON_PREFIX$carIp:$carPort"
                    val bytes = message.toByteArray(Charsets.UTF_8)
                    val broadcastAddress = InetAddress.getByName("255.255.255.255")
                    val packet = DatagramPacket(bytes, bytes.size, broadcastAddress, DISCOVERY_PORT)

                    Log.i(TAG, "[UDP广播] 启动局域网自动发现广播: $message")

                    while (isRunning.get()) {
                        try {
                            s.send(packet)
                        } catch (e: Exception) {
                            if (isRunning.get()) {
                                Log.w(TAG, "[UDP广播] 发送心跳包异常: ${e.message}")
                            }
                        }
                        Thread.sleep(intervalMs)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "[UDP广播] 广播服务异常退出", e)
                    }
                } finally {
                    stop()
                }
            }, "CarBeaconBroadcasterThread").apply { start() }
        }

        fun stop() {
            if (!isRunning.getAndSet(false)) return
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            workerThread = null
            Log.i(TAG, "[UDP广播] 广播服务已停止")
        }
    }
}
