package com.example.phone

import android.os.SystemClock
import android.util.Log
import com.example.protocol.StreamPacket
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机端 TCP Socket 客户端
 *
 * 核心功能：
 * 1. 负责与车机端建立 TCP Socket 长连接（默认端口 22345）。
 * 2. 具备 3 次自动重试机制与针对 EHOSTUNREACH/局域网隔离的友好中文诊断提示。
 * 3. 实时从输入流中反序列化提取 [StreamPacket] 视频数据包（SPS/PPS、I 帧、P 帧）。
 * 4. 延迟对抗与抗积压丢帧策略：当底层网络缓冲区积压超过 128KB 时，主动丢弃后续 P 帧，并等待下一个关键帧，杜绝延迟雪崩。
 * 5. 实时统计网络接收 FPS 与比特率（Kbps）。
 *
 * @param host 车机端 IP 地址
 * @param port 车机端监听端口号（默认 22345）
 * @param onPacketReceived 成功接收到视频数据包时的回调
 * @param onStatusChanged 连接状态发生变动时的回调
 */
class PhoneSocketClient(
    val host: String,
    val port: Int = 22345,
    private val onPacketReceived: (StreamPacket) -> Unit,
    private val onStatusChanged: (Status, String?) -> Unit
) {
    private val tag = "PhoneSocketClient"

    /**
     * 连接生命周期状态枚举
     */
    enum class Status {
        DISCONNECTED, // 已断开
        CONNECTING,   // 正在连接
        CONNECTED,    // 已成功连接
        ERROR         // 连接发生异常
    }

    // 运行状态原子标记
    private val isRunning = AtomicBoolean(false)
    // 客户端 TCP 套接字
    private var socket: Socket? = null
    // 数据接收后台工作线程
    private var clientThread: Thread? = null

    // 实时网络与渲染性能统计指标跟踪
    private var frameCounter = 0
    private var byteCounter = 0L
    private var lastStatsResetTimeMs = SystemClock.elapsedRealtime()

    // 性能数据更新回调（fps: 实时帧率, bitrateKbps: 实时接收带宽）
    var onStatsUpdated: ((fps: Int, bitrateKbps: Long) -> Unit)? = null

    /**
     * 发起与车机端的 Socket 连接
     */
    fun connect() {
        if (isRunning.getAndSet(true)) return

        onStatusChanged(Status.CONNECTING, null)

        clientThread = Thread({
            try {
                val validPort = if (port in 1..65535) port else 22345
                var connectedSocket: Socket? = null
                var lastException: Exception? = null

                // 重试机制：最多尝试连接 3 次，防止因网络短时抖动或服务端启动延迟导致的 EHOSTUNREACH
                for (attempt in 1..3) {
                    if (!isRunning.get()) break
                    try {
                        val s = Socket()
                        Log.i(tag, "[Socket客户端] 正在尝试发起连接到 $host:$validPort (第 $attempt 次尝试)...")
                        s.connect(InetSocketAddress(host, validPort), 4000)
                        s.tcpNoDelay = true
                        s.receiveBufferSize = 512 * 1024
                        connectedSocket = s
                        break
                    } catch (e: Exception) {
                        lastException = e
                        Log.w(tag, "[Socket客户端] 第 $attempt 次连接到 $host:$validPort 失败: ${e.localizedMessage}")
                        if (attempt < 3 && isRunning.get()) {
                            try { Thread.sleep(1000) } catch (_: Exception) {}
                        }
                    }
                }

                if (connectedSocket == null) {
                    val ex = lastException ?: Exception("连接超时或无法建立 Socket 连接")
                    throw ex
                }

                val s = connectedSocket
                socket = s

                Log.i(tag, "[Socket客户端] 已成功连接到车机端 Socket: $host:$validPort")
                onStatusChanged(Status.CONNECTED, null)

                val dataInput = DataInputStream(BufferedInputStream(s.getInputStream(), 512 * 1024))
                lastStatsResetTimeMs = SystemClock.elapsedRealtime()
                frameCounter = 0
                byteCounter = 0L

                var isWaitingForKeyFrame = false

                // 循环读取车机端发送的 StreamPacket 视频数据帧
                while (isRunning.get()) {
                    val packet = StreamPacket.readFromStream(dataInput) ?: break

                    // 若当前处于丢帧追赶状态，必须等待下一个 IDR 关键帧或 SPS/PPS 才能恢复渲染
                    if (isWaitingForKeyFrame) {
                        if (packet.type == StreamPacket.TYPE_KEY_FRAME || packet.type == StreamPacket.TYPE_SPS_PPS) {
                            isWaitingForKeyFrame = false
                            Log.i(tag, "[Socket客户端] 收到 IDR 关键帧/SPS_PPS，恢复正常解码")
                        } else {
                            continue
                        }
                    }

                    // 关键技术点：防延迟雪崩策略
                    // 检查输入流中的积压字节数，若缓冲区积压超过 128KB 且当前为 P 帧，直接舍弃 P 帧，
                    // 进入等待关键帧状态，防止渲染端持续滞后导致画面严重延时
                    val pendingBytes = try { socket?.getInputStream()?.available() ?: 0 } catch (_: Exception) { 0 }
                    if (pendingBytes > 128 * 1024 && packet.type == StreamPacket.TYPE_INTER_FRAME) {
                        Log.w(tag, "[Socket客户端] 缓冲区积压 ($pendingBytes 字节)，跳过 P 帧等待下一个 IDR 关键帧")
                        isWaitingForKeyFrame = true
                        continue
                    }

                    // 累加已接收帧数与字节大小
                    frameCounter++
                    byteCounter += packet.payload.size + StreamPacket.HEADER_SIZE
                    calculateStats()

                    // 分发已解析的数据包供解码器消费
                    onPacketReceived(packet)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(tag, "[Socket客户端] 连接或数据接收过程发生异常", e)
                    val stackTraceStr = Log.getStackTraceString(e)
                    // 针对各种常见网络通信错误提供详尽的中文诊断与排查指引
                    val friendlyMsg = when {
                        stackTraceStr.contains("EHOSTUNREACH") || e is java.net.ConnectException ->
                            "[网络不可达] 无法连接到车机 ($host:$port)。请确认：1.车机已开启推流；2.手机与车机处于同一局域网；3.若处于公司/公共 Wi-Fi，建议手机开热点供车机连接（规避 AP 隔离限制）。"
                        e is java.net.SocketTimeoutException ->
                            "[连接超时] 连接车机 ($host:$port) 超时，请检查局域网连接及 IP 地址。"
                        else -> e.localizedMessage ?: e.message ?: "Socket 连接发生未知异常"
                    }
                    onStatusChanged(Status.ERROR, friendlyMsg)
                }
            } finally {
                disconnectInternal()
            }
        }, "PhoneSocketClientThread").apply { start() }
    }

    /**
     * 周期性（每 1000ms）计算实时网络接收帧率（FPS）和码率（Kbps）
     */
    private fun calculateStats() {
        val now = SystemClock.elapsedRealtime()
        val deltaMs = now - lastStatsResetTimeMs
        if (deltaMs >= 1000) {
            val fps = ((frameCounter * 1000.0) / deltaMs).toInt()
            val kbps = (((byteCounter * 8) * 1000.0) / (deltaMs * 1024)).toLong()

            onStatsUpdated?.invoke(fps, kbps)

            // 重置计数器开启下一个周期统计
            frameCounter = 0
            byteCounter = 0L
            lastStatsResetTimeMs = now
        }
    }

    /**
     * 内部断开连接并清理套接字资源
     */
    private fun disconnectInternal() {
        if (!isRunning.getAndSet(false)) return

        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(tag, "[Socket客户端] 关闭 Socket 异常", e)
        }
        socket = null
        onStatusChanged(Status.DISCONNECTED, null)
        Log.i(tag, "[Socket客户端] Socket 客户端已断开")
    }

    /**
     * 主动断开 Socket 连接
     */
    fun disconnect() {
        disconnectInternal()
    }
}
