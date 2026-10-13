package com.example.car

import android.util.Log
import com.example.protocol.NetUtils
import com.example.protocol.StreamPacket
import com.example.protocol.UdpDiscovery
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 车机端 Socket 服务端
 *
 * 核心功能：
 * 1. 在指定端口（默认 22345）启动 ServerSocket 监听，支持端口冲突时自动递增回退探测。
 * 2. 配合 UDP 局域网广播广播自身 IP 和端口，以便手机端自动发现。
 * 3. 维护客户端会话列表，实现 H.264 视频帧的多路并发分发。
 * 4. 关键帧缓存机制：始终缓存最新的 SPS/PPS 及 I 帧，新客户端连接瞬间即可补发，实现“首帧秒开”。
 *
 * @param port 期望监听的起始端口号，默认为 22345
 */
class CarSocketServer(private val port: Int = 22345) {

    private val tag = "CarSocketServer"
    // TCP 服务端套接字
    private var serverSocket: ServerSocket? = null
    // 服务运行状态原子标记，防止并发启动与关闭冲突
    private val isRunning = AtomicBoolean(false)
    // 已连接的手机客户端会话列表（线程安全，支持边遍历广播边移除失效客户端）
    private val clientStreams = CopyOnWriteArrayList<ClientSession>()

    // UDP 广播发射器：局域网内定时广播车机 IP 和服务端口，用于手机自动探测
    private var beaconBroadcaster: UdpDiscovery.BeaconBroadcaster? = null

    // 缓存最新的 SPS/PPS 编解码器配置信息（用于新客户端接入时立即下发）
    @Volatile
    private var cachedSpsPpsPacket: CachedPacket? = null

    // 缓存最新的 I 帧（关键帧），用于新客户端接入时立即下发以实现秒开画面
    @Volatile
    private var cachedKeyFramePacket: CachedPacket? = null

    // 实际绑定的端口号（若初始端口被占用，则可能为 port + offset）
    @Volatile
    var actualPort: Int = port
        private set

    // 客户端在线连接数量发生变动时的回调通知
    @Volatile
    var onClientCountChanged: ((Int) -> Unit)? = null
    // 请求底层硬件编码器立即生成一个关键帧（IDR 帧）的回调函数
    @Volatile
    var onRequestKeyFrame: (() -> Unit)? = null

    /**
     * 客户端会话信息
     * @param socket 客户端 TCP 套接字
     * @param outputStream 包装后的数据输出流
     */
    private data class ClientSession(
        val socket: Socket,
        val outputStream: DataOutputStream
    )

    /**
     * 缓存的视频数据包（用于 SPS/PPS 与 I 帧缓存）
     * @param type 数据包类型（StreamPacket.TYPE_SPS_PPS 或 StreamPacket.TYPE_KEY_FRAME）
     * @param timestamp 帧时间戳（毫秒）
     * @param width 视频宽度
     * @param height 视频高度
     * @param payload 帧二进制数据
     */
    private data class CachedPacket(
        val type: Byte,
        val timestamp: Long,
        val width: Int,
        val height: Int,
        val payload: ByteArray
    )

    /**
     * 启动车机 Socket 服务端监听线程
     */
    fun start() {
        if (isRunning.getAndSet(true)) return

        Thread({
            var boundSocket: ServerSocket? = null
            var boundPort = port

            // 端口绑定重试与回退机制：若指定端口被占用，尝试向后偏移（最多尝试 6 个端口，每个端口重试 3 次）
            for (offset in 0..5) {
                val tryPort = port + offset
                for (attempt in 1..3) {
                    try {
                        boundSocket = ServerSocket().apply {
                            reuseAddress = true
                            bind(InetSocketAddress(tryPort))
                        }
                        boundPort = tryPort
                        break
                    } catch (e: java.net.BindException) {
                        Log.w(tag, "[车机Socket服务端] 端口 $tryPort 被占用 (EADDRINUSE)，等待 300ms 重试 (第 $attempt 次)...")
                        try { Thread.sleep(300) } catch (_: Exception) {}
                    } catch (e: Exception) {
                        Log.e(tag, "[车机Socket服务端] 绑定端口 $tryPort 出现异常", e)
                        break
                    }
                }
                if (boundSocket != null) break
            }

            if (boundSocket == null) {
                Log.e(tag, "[车机Socket服务端] 无法绑定 Socket 监听端口，尝试结束")
                isRunning.set(false)
                return@Thread
            }

            serverSocket = boundSocket
            actualPort = boundPort

            Log.i(tag, "[车机Socket服务端] Socket 服务端成功启动并监听端口: $actualPort")

            // 启动 UDP 局域网广播服务，广播实际监听端口，支持手机端无感自动搜索发现
            val localIp = NetUtils.getLocalIpAddress()
            beaconBroadcaster = UdpDiscovery.BeaconBroadcaster(localIp, actualPort).apply {
                start()
            }

            try {
                while (isRunning.get()) {
                    val clientSocket = serverSocket?.accept() ?: break
                    // 禁用 Nagle 算法，极大降低单帧传输延迟
                    clientSocket.tcpNoDelay = true
                    // 设置 512KB 发送缓冲区，保障关键帧（I帧）等大数据包顺畅发送
                    clientSocket.sendBufferSize = 512 * 1024
                    Log.i(tag, "[车机Socket服务端] 收到手机客户端连接请求，远端地址: ${clientSocket.remoteSocketAddress}")

                    try {
                        val outputStream = DataOutputStream(clientSocket.getOutputStream())
                        val session = ClientSession(clientSocket, outputStream)
                        clientStreams.add(session)

                        val currentCount = clientStreams.size
                        Log.i(tag, "[车机Socket服务端] 手机客户端建立连接成功！当前在线客户端数: $currentCount")
                        notifyClientCountChanged(currentCount)

                        // 开启子线程监测该客户端是否断开连接
                        monitorClientDisconnect(session)

                        // 关键点1: 若已缓存 SPS/PPS 编码配置帧，立即补发给新连入客户端，确保其能即刻初始化解码器
                        cachedSpsPpsPacket?.let { spsPps ->
                            synchronized(outputStream) {
                                StreamPacket.write(
                                    outputStream,
                                    spsPps.type,
                                    spsPps.timestamp,
                                    spsPps.width,
                                    spsPps.height,
                                    spsPps.payload
                                )
                            }
                            Log.i(tag, "[车机Socket服务端] 已向新连入手机端补发缓存的 SPS/PPS 参数集数据包 (${spsPps.payload.size} 字节)")
                        }

                        // 关键点2: 若已缓存最新 I 帧（关键帧），立即补发，无需等待下一个关键帧周期，实现首帧秒开
                        cachedKeyFramePacket?.let { keyFrame ->
                            synchronized(outputStream) {
                                StreamPacket.write(
                                    outputStream,
                                    keyFrame.type,
                                    keyFrame.timestamp,
                                    keyFrame.width,
                                    keyFrame.height,
                                    keyFrame.payload
                                )
                            }
                            Log.i(tag, "[车机Socket服务端] 已向新连入手机端补发缓存的最新 I 帧 (关键帧) 数据包 (${keyFrame.payload.size} 字节)")
                        }

                        // 关键点3: 主动通知硬件编码器立即请求一个全新的 IDR 关键帧，加速图像同步更新
                        onRequestKeyFrame?.invoke()

                    } catch (e: Exception) {
                        Log.e(tag, "[车机Socket服务端] 设置客户端数据流异常", e)
                        try { clientSocket.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(tag, "[车机Socket服务端] Socket 监听服务发生异常", e)
                }
            } finally {
                stop()
            }
        }, "CarSocketServerThread").start()
    }

    /**
     * 通知客户端连接数发生变化
     * @param count 当前在线连接的客户端数量
     */
    private fun notifyClientCountChanged(count: Int) {
        onClientCountChanged?.invoke(count)
    }

    /**
     * 监控指定客户端连接的存活状态
     * 通过低频读取输入流探测远端 Socket 是否断开（read 返回 -1 表示 EOF 断开）
     * @param session 待监控的客户端会话
     */
    private fun monitorClientDisconnect(session: ClientSession) {
        Thread({
            try {
                // 设置读取超时时间为 3000ms，避免长期阻塞
                session.socket.soTimeout = 3000
                val inputStream = session.socket.getInputStream()
                val buffer = ByteArray(16)
                while (isRunning.get() && !session.socket.isClosed) {
                    val read = try {
                        inputStream.read(buffer)
                    } catch (e: java.net.SocketTimeoutException) {
                        0
                    } catch (e: Exception) {
                        -1
                    }
                    if (read == -1) {
                        Log.i(tag, "[车机Socket服务端] 客户端断开连接: ${session.socket.remoteSocketAddress}")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "[车机Socket服务端] 客户端断开连接检测异常: ${session.socket.remoteSocketAddress}", e)
            } finally {
                removeClientSession(session)
            }
        }, "ClientMonitor-${session.socket.remoteSocketAddress}").start()
    }

    /**
     * 从活动列表中移除断开的客户端，并释放其 Socket 资源
     * @param session 待移除的客户端会话
     */
    private fun removeClientSession(session: ClientSession) {
        if (clientStreams.remove(session)) {
            val count = clientStreams.size
            Log.i(tag, "[车机Socket服务端] 手机客户端已断开连接: ${session.socket.remoteSocketAddress}，剩余在线客户端数: $count")
            try { session.socket.close() } catch (_: Exception) {}
            notifyClientCountChanged(count)
        }
    }

    /**
     * 广播发送 H.264 视频数据包到所有已连接的客户端
     *
     * @param type 数据包类型（TYPE_SPS_PPS, TYPE_KEY_FRAME, TYPE_INTER_FRAME）
     * @param timestamp 帧呈现时间戳（毫秒）
     * @param width 视频宽（像素）
     * @param height 视频高（像素）
     * @param payload H.264 编码数据字节数组
     */
    fun broadcastPacket(type: Byte, timestamp: Long, width: Int, height: Int, payload: ByteArray) {
        if (!isRunning.get()) return

        // 关键点：无论当前是否有客户端在线，始终缓存最新的 SPS/PPS 及关键帧，以备后续连入的客户端秒开
        if (type == StreamPacket.TYPE_SPS_PPS) {
            cachedSpsPpsPacket = CachedPacket(type, timestamp, width, height, payload)
        } else if (type == StreamPacket.TYPE_KEY_FRAME) {
            cachedKeyFramePacket = CachedPacket(type, timestamp, width, height, payload)
        }

        if (clientStreams.isEmpty()) return

        val deadSessions = mutableListOf<ClientSession>()

        for (session in clientStreams) {
            try {
                synchronized(session.outputStream) {
                    StreamPacket.write(session.outputStream, type, timestamp, width, height, payload)
                }
            } catch (e: IOException) {
                Log.w(tag, "[车机Socket服务端] 向客户端 ${session.socket.remoteSocketAddress} 发送帧数据失败")
                deadSessions.add(session)
            }
        }

        // 清理发送异常的失效客户端
        if (deadSessions.isNotEmpty()) {
            for (session in deadSessions) {
                removeClientSession(session)
            }
        }
    }

    /**
     * 停止 Socket 服务端，关闭所有连接并释放相关网络资源
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(tag, "[车机Socket服务端] 正在停止 Socket 服务端...")

        // 停止 UDP 广播
        beaconBroadcaster?.stop()
        beaconBroadcaster = null

        // 关闭所有已连接的客户端
        for (session in clientStreams) {
            try { session.socket.close() } catch (_: Exception) {}
        }
        clientStreams.clear()
        notifyClientCountChanged(0)

        // 关闭 ServerSocket 监听套接字
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(tag, "[车机Socket服务端] 关闭服务 Socket 异常", e)
        }
        serverSocket = null
        cachedSpsPpsPacket = null
        cachedKeyFramePacket = null
    }

    /**
     * 获取当前在线连接的客户端数量
     * @return 当前在线客户端数
     */
    fun getClientCount(): Int = clientStreams.size
}
