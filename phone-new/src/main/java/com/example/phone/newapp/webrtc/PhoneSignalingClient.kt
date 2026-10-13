package com.example.phone.newapp.webrtc

import android.util.Log
import com.example.phone.newapp.protocol.SignalingMessage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机端 WebRTC TCP 信令客户端 (PhoneSignalingClient)
 *
 * 核心功能：
 * 1. 发起与车机端信令服务器 (默认端口 22456) 的 TCP 连接。
 * 2. 接收车机发送的 SDP Offer，并将手机端生成的 SDP Answer 与 ICE Candidate 回传至车机。
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
                Log.i(TAG, "[手机信令] 正在连接车机信令服务: $carIp:$carPort")
                val s = Socket()
                s.connect(InetSocketAddress(carIp, carPort), 5000)
                socket = s
                writer = PrintWriter(s.getOutputStream(), true)

                Log.i(TAG, "[手机信令] 成功建立与车机的 TCP 信令连接")
                onConnected()

                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                var line: String? = null
                while (isRunning.get() && reader.readLine().also { line = it } != null) {
                    val msgLine = line ?: continue
                    val msg = SignalingMessage.fromJson(msgLine)
                    if (msg != null) {
                        Log.i(TAG, "[手机信令] 收到车机下发信令: type=${msg.type}, 长度=${msgLine.length}")
                        onMessageReceived(msg)
                    } else {
                        Log.w(TAG, "[手机信令] 收到无法解析的信令行: $msgLine")
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    val errorMsg = when (e) {
                        is java.net.ConnectException -> "无法连接到车机 ($carIp:$carPort)，请检查车机是否开启热点或同一Wi-Fi (EHOSTUNREACH)"
                        is java.net.SocketTimeoutException -> "连接车机超时 ($carIp:$carPort)"
                        else -> "信令连接错误: ${e.localizedMessage ?: e.message}"
                    }
                    Log.e(TAG, "[手机信令] 连接异常: $errorMsg", e)
                    onError?.invoke(errorMsg)
                }
            } finally {
                disconnect()
            }
        }, "PhoneSignalingClientThread").apply { start() }
    }

    fun sendMessage(msg: SignalingMessage) {
        val json = msg.toJson()
        synchronized(this) {
            writer?.apply {
                println(json)
                flush()
                Log.i(TAG, "[手机信令] 已发送信令至车机: type=${msg.type}, 长度=${json.length}")
            } ?: Log.w(TAG, "[手机信令] 尝试发送信令但 Socket Writer 尚未就绪: type=${msg.type}")
        }
    }

    fun disconnect() {
        if (!isRunning.getAndSet(false)) return
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
        onDisconnected()
        Log.i(TAG, "[手机信令] 已断开信令连接")
    }
}
