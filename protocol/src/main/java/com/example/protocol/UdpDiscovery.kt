package com.example.protocol

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

object UdpDiscovery {

    private const val TAG = "UdpDiscovery"
    const val DISCOVERY_PORT = 8889
    private const val BEACON_PREFIX = "CARS_BEACON"

    /**
     * Starts UDP Broadcast Beacon on Car/Sender side.
     */
    class BeaconBroadcaster(
        private val carIp: String,
        private val carPort: Int = 22456
    ) {
        private val isRunning = AtomicBoolean(false)
        private var socket: DatagramSocket? = null
        private var thread: Thread? = null

        fun start() {
            if (isRunning.getAndSet(true)) return

            val validPort = carPort.takeIf { it in 1..65535 } ?: 22456

            thread = Thread({
                try {
                    socket = DatagramSocket()
                    socket?.broadcast = true

                    val message = "$BEACON_PREFIX|$carIp|$validPort"
                    val bytes = message.toByteArray(Charsets.UTF_8)

                    val broadcastAddr = InetAddress.getByName("255.255.255.255")
                    val packet = DatagramPacket(bytes, bytes.size, broadcastAddr, DISCOVERY_PORT)

                    Log.i(TAG, "[UDP广播] 车机端 UDP 广播服务已启动，广播地址 255.255.255.255:$DISCOVERY_PORT，广播目标 IP: $carIp:$validPort")

                    while (isRunning.get()) {
                        try {
                            socket?.send(packet)
                        } catch (e: Exception) {
                            Log.w(TAG, "[UDP广播] 发送 UDP 广播数据包失败", e)
                        }
                        Thread.sleep(1500)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[UDP广播] 车机端 UDP 广播服务发生异常", e)
                } finally {
                    stop()
                }
            }, "UdpBeaconBroadcasterThread").apply { start() }
        }

        fun stop() {
            if (!isRunning.getAndSet(false)) return
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            Log.i(TAG, "[UDP广播] 车机端 UDP 广播服务已停止")
        }
    }

    /**
     * Listens for UDP Broadcast Beacons on Phone/Receiver side for Auto-Discovery.
     */
    class BeaconListener(
        private val onCarDiscovered: (ip: String, port: Int) -> Unit
    ) {
        private val isRunning = AtomicBoolean(false)
        private var socket: DatagramSocket? = null
        private var thread: Thread? = null

        fun start() {
            if (isRunning.getAndSet(true)) return

            thread = Thread({
                try {
                    socket = DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(DISCOVERY_PORT))
                    }
                    socket?.broadcast = true

                    val buffer = ByteArray(512)
                    val packet = DatagramPacket(buffer, buffer.size)

                    Log.i(TAG, "[UDP监听] 手机端 UDP 自动搜索已启动，监听端口 $DISCOVERY_PORT")

                    while (isRunning.get()) {
                        socket?.receive(packet)
                        val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        if (msg.startsWith(BEACON_PREFIX)) {
                            val parts = msg.split("|")
                            if (parts.size >= 3) {
                                val ip = parts[1]
                                val port = parts[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: 22456
                                Log.i(TAG, "[UDP监听] 成功自动搜索到车机端设备 -> IP: $ip, 端口: $port")
                                onCarDiscovered(ip, port)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "[UDP监听] 手机端 UDP 监听服务发生异常", e)
                    }
                } finally {
                    stop()
                }
            }, "UdpBeaconListenerThread").apply { start() }
        }

        fun stop() {
            if (!isRunning.getAndSet(false)) return
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            Log.i(TAG, "[UDP监听] 手机端 UDP 自动搜索已停止")
        }
    }
}
