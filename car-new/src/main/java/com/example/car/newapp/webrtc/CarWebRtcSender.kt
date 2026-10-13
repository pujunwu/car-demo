package com.example.car.newapp.webrtc

import android.content.Context
import android.util.Log
import com.example.car.newapp.protocol.SignalingMessage
import com.example.car.newapp.webrtc.core.SimplePeerConnectionObserver
import com.example.car.newapp.webrtc.core.SimpleSdpObserver
import com.example.car.newapp.webrtc.core.WebRtcContext
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * 车机端 WebRTC 媒体传输协调器 (CarWebRtcSender)
 *
 * 核心架构与职责：
 * 1. 【WebRTC 传输核心】：负责建立 PeerConnection、SDP Offer/Answer 协商、ICE 打洞与网络传输。
 * 2. 【与自定义硬编桥接】：使用 [CustomEncoderFactory]，将车机 [H264Encoder] 输出的 H.264 编码帧通过 [CustomPassthroughEncoder] 喂入 WebRTC RTP 传输通道。
 * 3. 【时钟与管线激活】：启动 [DummyFramePumper] 提供低频心跳帧，驱动 WebRTC C++ VideoStreamEncoder 完成 initEncode 并激活 GCC 拥塞控制。
 * 4. 【网络自适应联动】：捕获 WebRTC 网络带宽估计建议，通过回调通知 [H264Encoder] 动态升降编码码率。
 */
class CarWebRtcSender(
    private val context: Context,
    private val signalingPort: Int = 22456,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val onStatusChanged: (String) -> Unit = {},
    private val onBitrateAdjustRequested: ((newBitrateBps: Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "CarWebRtcSender"
        private const val VIDEO_TRACK_ID = "car_new_video_track"
    }

    private var factory: PeerConnectionFactory? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var signalingServer: CarSignalingServer? = null
    private var peerConnection: PeerConnection? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    // 自定义直通编码器工厂
    private val customEncoderFactory = CustomEncoderFactory()

    // 同步载体帧静态缓冲，用于驱动 WebRTC C++ VideoStreamEncoder 调用 encode()
    private var dummyBuffer: JavaI420Buffer? = null

    // 关键帧请求回调，用于通知外部硬件编码器生成 IDR 帧
    var onRequestKeyFrame: (() -> Unit)? = null

    /**
     * 启动 WebRTC 推流服务与信令服务器
     */
    fun start() {
        onStatusChanged("初始化 WebRTC 传输环境...")
        try {
            System.loadLibrary("jingle_peerconnection_so")
            Log.i(TAG, "显式加载 jingle_peerconnection_so 动态库成功")
        } catch (t: Throwable) {
            Log.w(TAG, "加载 jingle_peerconnection_so 告警: ${t.message}")
        }

        WebRtcContext.initialize(context)
        val eglContext = WebRtcContext.rootEglBase.eglBaseContext

        // 监听动态码率调节回调，将网络拥塞建议转发至外部硬件编码器
        customEncoderFactory.passthroughEncoder.onEncoderInitialized = {
            Log.i(TAG, "[WebRTC] 直通编码管线 initEncode 已完成，立即向管道注入首帧关键帧！")
            onRequestKeyFrame?.invoke()
        }
        customEncoderFactory.onEncoderCreated = { encoder ->
            encoder.onBitrateAllocationChanged = { bps ->
                onBitrateAdjustRequested?.invoke(bps)
            }
            encoder.onEncoderInitialized = {
                Log.i(TAG, "[WebRTC] 直通编码管线 initEncode 已完成，立即向管道注入首帧关键帧！")
                onRequestKeyFrame?.invoke()
            }
            onRequestKeyFrame?.invoke()
        }

        // 构建 PeerConnectionFactory，将 VideoEncoderFactory 替换为自定义直通工厂
        val audioDeviceModule = org.webrtc.audio.JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(customEncoderFactory)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .setOptions(PeerConnectionFactory.Options().apply {
                // 热点/局域网下固定 IP，禁用系统网络监听避免网络切换重置推流
                disableNetworkMonitor = true
            })
            .createPeerConnectionFactory()

        // 创建 WebRTC VideoSource 与 VideoTrack
        val vs = factory?.createVideoSource(false)
        videoSource = vs
        videoTrack = factory?.createVideoTrack(VIDEO_TRACK_ID, vs)?.apply {
            setEnabled(true)
        }

        // 宣告 Capturer 已启动并分配单例静态占位缓冲
        vs?.capturerObserver?.onCapturerStarted(true)
        dummyBuffer = try {
            JavaI420Buffer.allocate(videoWidth, videoHeight)
        } catch (e: Exception) {
            Log.e(TAG, "[WebRTC] 创建 dummyBuffer 占位缓冲失败: ${e.message}")
            null
        }

        // 启动 TCP 信令服务器
        signalingServer = CarSignalingServer(
            port = signalingPort,
            onMessageReceived = ::handleSignalingMessage,
            onClientConnected = { clientIp ->
                onStatusChanged("手机已接入 ($clientIp)，正在建立 WebRTC 直连...")
                createPeerConnectionAndOffer()
                onRequestKeyFrame?.invoke()
            },
            onClientDisconnected = {
                onStatusChanged("手机已断开，等待重新连接...")
                closePeerConnection()
            }
        ).apply { start() }

        onStatusChanged("WebRTC 传输服务已就绪，监听端口 $signalingPort")
        Log.i(TAG, "WebRTC 传输服务初始化完成，等待客户端连接")
    }

    private var lastMonotonicTimestampNs = 0L
    private var totalPushedFrames = 0L
    private var lastPushedStatsMs = 0L
    private var intervalPushedFrames = 0

    @Synchronized
    private fun getMonotonicTimestampNs(rawTimestampNs: Long): Long {
        val ts = if (rawTimestampNs > lastMonotonicTimestampNs) rawTimestampNs else lastMonotonicTimestampNs + 1_000_000L
        lastMonotonicTimestampNs = ts
        return ts
    }

    /**
     * 核心接口：供外部自定义硬件编码器注入已编码的 H.264 视频帧数据
     *
     * 实现原理：
     * 1. 将自定义硬编码数据放入 [CustomPassthroughEncoder] 待发队列。
     * 2. 注入时间戳严格相同的同步载体帧，驱动 WebRTC VideoStreamEncoder 调用 encode()。
     * 3. [CustomPassthroughEncoder.encode] 取出该数据并以完全相同的 timestampNs 喂入 RTP 封包器。
     */
    fun sendEncodedFrame(
        isKeyFrame: Boolean,
        timestampNs: Long,
        width: Int,
        height: Int,
        data: ByteArray
    ) {
        val encoder = customEncoderFactory.activeEncoder
        val vs = videoSource ?: return
        val dummy = dummyBuffer ?: return

        val safeTimestampNs = getMonotonicTimestampNs(timestampNs)

        // 1. 将外部硬编码数据入队待发
        encoder.enqueueEncodedFrame(
            CustomPassthroughEncoder.PendingEncodedFrame(
                data = data,
                isKeyFrame = isKeyFrame,
                width = width,
                height = height,
                timestampNs = safeTimestampNs
            )
        )

        // 2. 注入时间戳严格一致的同步驱动帧
        try {
            dummy.retain()
            val frame = VideoFrame(dummy, 0, safeTimestampNs)
            vs.capturerObserver.onFrameCaptured(frame)
            frame.release()

            totalPushedFrames++
            intervalPushedFrames++
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastPushedStatsMs >= 2000) {
                if (lastPushedStatsMs > 0) {
                    val fps = (intervalPushedFrames * 1000.0f / (nowMs - lastPushedStatsMs)).toInt()
                    Log.i(TAG, "[WebRTC推流诊断] 同步载体帧推进正常: 累计已推=$totalPushedFrames 帧, 当前帧率=$fps FPS, encoderReady=${encoder.isReady()}")
                }
                intervalPushedFrames = 0
                lastPushedStatsMs = nowMs
            }
        } catch (e: Exception) {
            Log.e(TAG, "[WebRTC] 注入同步载体帧异常: ${e.message}")
        }
    }

    private fun createPeerConnectionAndOffer() {
        val factory = factory ?: return
        val track = videoTrack ?: return

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // 禁用 CPU 过载丢帧检测，由外部编码器掌控帧率
            enableCpuOveruseDetection = false
            // 纯 UDP 直连，消除 TCP 队头阻塞
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        closePeerConnection()

        val pc = factory.createPeerConnection(rtcConfig, object : SimplePeerConnectionObserver() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                Log.d(TAG, "[WebRTC] 本地生成 ICE Candidate: ${candidate.sdp}")
                signalingServer?.sendMessage(
                    SignalingMessage(
                        type = SignalingMessage.TYPE_ICE_CANDIDATE,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                        candidate = candidate.sdp
                    )
                )
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "[WebRTC] ICE 传输连接状态变更: $state")
                onStatusChanged("WebRTC 状态: $state")
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                    onRequestKeyFrame?.invoke()
                }
            }
        }) ?: return

        peerConnection = pc
        val rtpSender = pc.addTrack(track, listOf("car_screen_stream"))
        applyRtpSenderParameters(rtpSender)

        val mediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc ?: return
                val optimizedSdp = optimizeSdpForLowLatency(desc.description)
                val offerDesc = SessionDescription(SessionDescription.Type.OFFER, optimizedSdp)

                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        val videoSdpLines = optimizedSdp.lines().filter { it.startsWith("m=video") || it.startsWith("a=rtpmap") || it.startsWith("a=fmtp") }.joinToString(" | ")
                        Log.i(TAG, "[WebRTC] 设置本地 Offer 成功，向手机发送 Offer. 协商行: $videoSdpLines")
                        applyRtpSenderParameters(rtpSender)
                        signalingServer?.sendMessage(
                            SignalingMessage(
                                type = SignalingMessage.TYPE_OFFER,
                                sdp = optimizedSdp
                            )
                        )
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "[WebRTC] 设置本地 Offer 失败: $error")
                    }
                }, offerDesc)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "[WebRTC] 创建 Offer 失败: $error")
            }
        }, mediaConstraints)
    }

    private fun applyRtpSenderParameters(rtpSender: org.webrtc.RtpSender?) {
        rtpSender ?: return
        try {
            val params = rtpSender.parameters
            params.degradationPreference = org.webrtc.RtpParameters.DegradationPreference.DISABLED
            if (params.encodings.isNotEmpty()) {
                val enc = params.encodings[0]
                enc.minBitrateBps = 6_000_000
                enc.maxBitrateBps = 15_000_000
                enc.maxFramerate = 60
                enc.active = true
            }
            rtpSender.parameters = params
            Log.i(TAG, "[WebRTC] 成功设置 RtpParameters (DISABLED 降级, 6~15Mbps, 60fps)")
        } catch (e: Exception) {
            Log.w(TAG, "[WebRTC] 设置 RtpParameters 告警: ${e.message}")
        }
    }

    private fun optimizeSdpForLowLatency(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val result = mutableListOf<String>()
        var inVideo = false

        for (line in lines) {
            if (line.startsWith("m=video")) {
                inVideo = true
                result.add(line)
                result.add("b=AS:15000")
                result.add("b=TIAS:15000000")
                continue
            } else if (line.startsWith("m=")) {
                inVideo = false
            }

            if (inVideo && line.startsWith("a=fmtp:")) {
                var fmtp = line
                if (!fmtp.contains("x-google-min-bitrate")) {
                    fmtp = "$fmtp;x-google-min-bitrate=6000;x-google-start-bitrate=10000;x-google-max-bitrate=15000"
                }
                result.add(fmtp)
            } else {
                result.add(line)
            }
        }
        return result.joinToString("\r\n")
    }

    private fun handleSignalingMessage(msg: SignalingMessage) {
        val pc = peerConnection ?: return
        when (msg.type) {
            SignalingMessage.TYPE_ANSWER -> {
                val sdp = msg.sdp ?: return
                val videoSdpLines = sdp.lines().filter { it.startsWith("m=video") || it.startsWith("a=rtpmap") || it.startsWith("a=fmtp") }.joinToString(" | ")
                Log.i(TAG, "[WebRTC] 收到手机端 Answer SDP，应用远端描述. 协商行: $videoSdpLines")
                val answerDesc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        Log.i(TAG, "[WebRTC] 设置远端 Answer 成功")
                        synchronized(pendingIceCandidates) {
                            for (cand in pendingIceCandidates) {
                                pc.addIceCandidate(cand)
                            }
                            pendingIceCandidates.clear()
                        }
                        val senders = pc.senders
                        if (senders.isNotEmpty()) {
                            applyRtpSenderParameters(senders[0])
                        }
                        startStatsMonitoring()
                        onRequestKeyFrame?.invoke()
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "[WebRTC] 设置远端 Answer 失败: $error")
                    }
                }, answerDesc)
            }
            SignalingMessage.TYPE_ICE_CANDIDATE -> {
                val candSdp = msg.candidate ?: return
                val candidate = IceCandidate(msg.sdpMid ?: "", msg.sdpMLineIndex ?: 0, candSdp)
                if (pc.remoteDescription != null) {
                    pc.addIceCandidate(candidate)
                } else {
                    synchronized(pendingIceCandidates) {
                        pendingIceCandidates.add(candidate)
                    }
                }
            }
            SignalingMessage.TYPE_REQUEST_KEY_FRAME -> {
                Log.i(TAG, "[WebRTC] 收到手机端主动请求关键帧信令")
                onRequestKeyFrame?.invoke()
            }
            SignalingMessage.TYPE_DISCONNECT -> {
                Log.i(TAG, "[WebRTC] 收到手机端主动断开信号")
                closePeerConnection()
            }
        }
    }

    private val statsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastSentFrames = 0L
    private var lastStatsTimeMs = 0L

    private val statsRunnable = object : Runnable {
        override fun run() {
            val pc = peerConnection ?: return
            pc.getStats { report ->
                for (stat in report.statsMap.values) {
                    if (stat.type == "outbound-rtp" && (stat.members["kind"] == "video" || stat.members.containsKey("framesEncoded"))) {
                        val framesSentObj = stat.members["framesSent"]
                        val framesSent = when (framesSentObj) {
                            is Number -> framesSentObj.toLong()
                            is String -> framesSentObj.toLongOrNull() ?: 0L
                            else -> 0L
                        }
                        val bytesSent = (stat.members["bytesSent"] as? Number)?.toLong() ?: 0L
                        val now = System.currentTimeMillis()
                        if (lastStatsTimeMs > 0) {
                            val diffSec = (now - lastStatsTimeMs) / 1000.0
                            if (diffSec > 0) {
                                val sentDiff = (framesSent - lastSentFrames).coerceAtLeast(0)
                                val sendFps = (sentDiff / diffSec).toInt()
                                val mb = String.format(java.util.Locale.US, "%.2f", bytesSent / (1024.0 * 1024.0))
                                Log.i(TAG, "[WebRTC传输统计] 发送帧率: $sendFps FPS, 累计发送数据: $mb MB (累计帧: $framesSent)")
                            }
                        }
                        lastSentFrames = framesSent
                        lastStatsTimeMs = now
                        break
                    }
                }
            }
            if (peerConnection != null) {
                statsHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun startStatsMonitoring() {
        stopStatsMonitoring()
        statsHandler.postDelayed(statsRunnable, 1000)
    }

    private fun stopStatsMonitoring() {
        statsHandler.removeCallbacks(statsRunnable)
        lastSentFrames = 0L
        lastStatsTimeMs = 0L
    }

    private fun closePeerConnection() {
        stopStatsMonitoring()
        synchronized(pendingIceCandidates) {
            pendingIceCandidates.clear()
        }
        try {
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
        } catch (e: Exception) {
            Log.e(TAG, "[WebRTC] 关闭 PeerConnection 异常", e)
        }
    }

    /**
     * 停止 WebRTC 传输并释放全部组件
     */
    fun stop() {
        onStatusChanged("停止 WebRTC 推流...")
        closePeerConnection()

        signalingServer?.stop()
        signalingServer = null

        try {
            videoSource?.capturerObserver?.onCapturerStopped()
        } catch (_: Exception) {}
        dummyBuffer?.release()
        dummyBuffer = null

        try {
            videoTrack?.dispose()
            videoTrack = null
            videoSource?.dispose()
            videoSource = null
            factory?.dispose()
            factory = null
        } catch (e: Exception) {
            Log.e(TAG, "[WebRTC] 释放 PeerConnectionFactory 异常", e)
        }
        Log.i(TAG, "[WebRTC] WebRTC 传输管理器已完全停止")
    }
}
