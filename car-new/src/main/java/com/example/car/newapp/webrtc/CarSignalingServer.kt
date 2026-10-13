package com.example.car.newapp.webrtc

import android.util.Log
import com.example.car.newapp.protocol.SignalingMessage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 车机端 WebRTC TCP 信令服务器 (CarSignalingServer)
 *
 * 核心功能：
 * 1. 监听指定端口（默认 22456），接收手机端的 TCP 长连接。
 * 2. 负责车机端与手机端之间的 WebRTC SDP (Offer / Answer) 及 ICE Candidate 消息协商与交换。
 * 3. 手机端连接或断开时触发事件回调，联动 PeerConnection 创建与销毁。
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
    @Volatile
    private var clientSocket: Socket? = null
    private var writer: PrintWriter? = null
    private val writeLock = Any()
    private var serverThread: Thread? = null

    /**
     * 启动 TCP 信令监听服务
     */
    fun start() {
        if (isRunning.getAndSet(true)) return

        serverThread = Thread({
            try {
                serverSocket = ServerSocket(port).apply { reuseAddress = true }
                Log.i(TAG, "[信令服务] WebRTC TCP 信令服务器已在端口 $port 启动监听 (非阻塞接受新连接)")

                while (isRunning.get()) {
                    val socket = serverSocket?.accept() ?: break
                    val clientIp = socket.inetAddress.hostAddress ?: "Unknown"
                    Log.i(TAG, "[信令服务] 手机客户端已接入，IP: $clientIp")

                    // 若已有旧客户端连接，主动断开旧连接，确保信令通道指向最新会话
                    synchronized(writeLock) {
                        try {
                            clientSocket?.close()
                        } catch (_: Exception) {}
                        clientSocket = socket
                        writer = PrintWriter(socket.getOutputStream(), true)
                    }

                    onClientConnected(clientIp)

                    // 启动独立的后台读取线程处理该客户端的输入流，防止阻塞 accept() 接收后续重连
                    Thread({
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        try {
                            var line: String? = null
                            while (isRunning.get() && clientSocket === socket && reader.readLine().also { line = it } != null) {
                                val msgLine = line ?: continue
                                val msg = SignalingMessage.fromJson(msgLine)
                                if (msg != null) {
                                    Log.i(TAG, "[信令服务] 收到手机端信令: type=${msg.type}, 长度=${msgLine.length}")
                                    onMessageReceived(msg)
                                } else {
                                    Log.w(TAG, "[信令服务] 收到无法解析的信令行: $msgLine")
                                }
                            }
                        } catch (e: Exception) {
                            if (isRunning.get() && clientSocket === socket) {
                                Log.w(TAG, "[信令服务] 客户端 Socket 读取断开: ${e.message}")
                            }
                        } finally {
                            synchronized(writeLock) {
                                if (clientSocket === socket) {
                                    closeClientConnection(socket)
                                } else {
                                    try { socket.close() } catch (_: Exception) {}
                                }
                            }
                        }
                    }, "CarSignalingClientThread-$clientIp").apply { isDaemon = true; start() }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "[信令服务] 信令服务器异常", e)
                }
            } finally {
                stop()
            }
        }, "CarSignalingServerThread").apply { start() }
    }

    /**
     * 发送信令消息（以单行 JSON 格式下发，线程安全）
     */
    fun sendMessage(msg: SignalingMessage) {
        val json = msg.toJson()
        synchronized(writeLock) {
            writer?.apply {
                println(json)
                flush()
                Log.i(TAG, "[信令服务] 已发送信令至手机端: type=${msg.type}, 长度=${json.length}")
            } ?: Log.w(TAG, "[信令服务] 尝试发送信令但当前客户端 Socket 尚未就绪: type=${msg.type}")
        }
    }

    private fun closeClientConnection(targetSocket: Socket? = null) {
        synchronized(writeLock) {
            if (targetSocket == null || clientSocket === targetSocket) {
                try { clientSocket?.close() } catch (_: Exception) {}
                clientSocket = null
                writer = null
                onClientDisconnected()
            }
        }
    }

    /**
     * 停止信令服务并释放端口
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        synchronized(writeLock) {
            try { clientSocket?.close() } catch (_: Exception) {}
            clientSocket = null
            writer = null
        }
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "[信令服务] WebRTC 信令服务器已停止")
    }
}
