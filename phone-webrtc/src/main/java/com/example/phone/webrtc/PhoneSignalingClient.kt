package com.example.phone.webrtc

import android.util.Log
import com.example.protocol.SignalingMessage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP Signaling Client running on Phone Unit to connect to Car Unit.
 */
class PhoneSignalingClient(
    private val carIp: String,
    private val carPort: Int = 22456,
    private val onMessageReceived: (SignalingMessage) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "PhoneSignalingClient"
    }

    private val isRunning = AtomicBoolean(false)
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var clientThread: Thread? = null

    fun connect() {
        if (isRunning.getAndSet(true)) return

        clientThread = Thread({
            try {
                Log.i(TAG, "正在连接车机信令服务器：$carIp:$carPort")
                val s = Socket()
                s.connect(InetSocketAddress(carIp, carPort), 5000)
                socket = s
                writer = PrintWriter(s.getOutputStream(), true)

                Log.i(TAG, "成功连接到车机信令服务器")
                onConnected()

                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                var line: String? = null
                while (isRunning.get() && reader.readLine().also { line = it } != null) {
                    val msgLine = line ?: continue
                    val msg = SignalingMessage.fromJson(msgLine)
                    if (msg != null) {
                        onMessageReceived(msg)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    val errorMsg = when (e) {
                        is java.net.ConnectException -> "无法连接到车机 IP ($carIp:$carPort)，目标主机不可达 (EHOSTUNREACH)"
                        is java.net.SocketTimeoutException -> "连接车机信令服务器超时 ($carIp:$carPort)"
                        else -> "信令连接错误：${e.localizedMessage ?: e.message}"
                    }
                    Log.e(TAG, "信令连接异常：$errorMsg", e)
                    onError?.invoke(errorMsg)
                }
            } finally {
                disconnect()
            }
        }, "PhoneSignalingClientThread").apply { start() }
    }

    fun sendMessage(msg: SignalingMessage) {
        val json = msg.toJson()
        writer?.apply {
            println(json)
            flush()
        }
    }

    fun disconnect() {
        if (!isRunning.getAndSet(false)) return
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
        onDisconnected()
        Log.i(TAG, "已断开与车机信令服务器的连接")
    }
}
