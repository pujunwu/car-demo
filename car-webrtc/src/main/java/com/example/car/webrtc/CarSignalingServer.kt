package com.example.car.webrtc

import android.util.Log
import com.example.protocol.SignalingMessage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP Signaling Server running on Car Unit to exchange WebRTC SDP & ICE Candidates.
 */
class CarSignalingServer(
    val port: Int = 22456,
    private val onMessageReceived: (SignalingMessage) -> Unit,
    private val onClientConnected: (clientIp: String) -> Unit,
    private val onClientDisconnected: () -> Unit
) {
    companion object {
        private const val TAG = "CarSignalingServer"
    }

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var writer: PrintWriter? = null
    private var serverThread: Thread? = null

    fun start() {
        if (isRunning.getAndSet(true)) return

        serverThread = Thread({
            try {
                serverSocket = ServerSocket(port).apply { reuseAddress = true }
                Log.i(TAG, "WebRTC 信令服务器已在端口 $port 启动监听")

                while (isRunning.get()) {
                    val socket = serverSocket?.accept() ?: break
                    clientSocket = socket
                    val clientIp = socket.inetAddress.hostAddress ?: "Unknown"
                    Log.i(TAG, "手机端客户端已连接，IP: $clientIp")

                    writer = PrintWriter(socket.getOutputStream(), true)
                    onClientConnected(clientIp)

                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    try {
                        var line: String? = null
                        while (reader.readLine().also { line = it } != null) {
                            val msgLine = line ?: continue
                            val msg = SignalingMessage.fromJson(msgLine)
                            if (msg != null) {
                                onMessageReceived(msg)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "客户端 Socket 读取错误或连接已关闭", e)
                    } finally {
                        closeClientConnection()
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "信令服务器异常", e)
                }
            } finally {
                stop()
            }
        }, "CarSignalingServerThread").apply { start() }
    }

    fun sendMessage(msg: SignalingMessage) {
        val json = msg.toJson()
        writer?.apply {
            println(json)
            flush()
        }
    }

    private fun closeClientConnection() {
        try { clientSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        writer = null
        onClientDisconnected()
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        serverSocket = null
        writer = null
        Log.i(TAG, "WebRTC 信令服务器已停止")
    }
}
