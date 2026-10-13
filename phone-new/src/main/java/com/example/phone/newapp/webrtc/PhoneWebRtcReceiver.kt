package com.example.phone.newapp.webrtc

import android.content.Context
import android.util.Log
import com.example.phone.newapp.protocol.SignalingMessage
import com.example.phone.newapp.render.RenderMode
import com.example.phone.newapp.webrtc.core.SimplePeerConnectionObserver
import com.example.phone.newapp.webrtc.core.SimpleSdpObserver
import com.example.phone.newapp.webrtc.core.WebRtcContext
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoTrack

/**
 * 手机端 WebRTC 媒体接收协调器 (PhoneWebRtcReceiver)
 *
 * 核心架构与职责：
 * 1. 【WebRTC 传输接管】：负责与车机建立纯 UDP 直连链路，处理 SDP 协商与 ICE 打洞。
 * 2. 【双渲染模式独立支撑】：
 *    - 自定义硬解模式 (RenderMode.CUSTOM)：
 *      利用 [CustomDecoderFactory] 拦截 WebRTC 重组出的 H.264 原始视频帧，
 *      直接回调至 [onFrameReceived]，由外部 MediaCodec 解码并送显至 AspectRatioSurfaceView。
 *    - WebRTC 原生渲染模式 (RenderMode.WEBRTC_BUILTIN)：
 *      利用 WebRTC 官方 [DefaultVideoDecoderFactory] (经由 [PhoneProxyVideoDecoderFactory] JNI 防御加固)，
 *      将解码管线交由 WebRTC 原生处理，并通过 [onVideoTrackReceived] 交付至 SurfaceViewRenderer 控件渲染。
 * 3. 【网络传输健康监控】：实时周期性轮询 getStats，统计接收帧率、码率及丢包情况。
 */
class PhoneWebRtcReceiver(
    private val context: Context,
    private val carIp: String,
    private val carPort: Int = 22456,
    private val renderMode: RenderMode = RenderMode.CUSTOM,
    private val onStatusChanged: (String) -> Unit = {},
    private val onFrameReceived: ((data: ByteArray, isKeyFrame: Boolean, width: Int, height: Int, timestampNs: Long) -> Unit)? = null,
    private val onVideoTrackReceived: ((VideoTrack) -> Unit)? = null,
    private val onStatsUpdated: ((fps: Int, bitrateKbps: Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "PhoneWebRtcReceiver"
    }

    private var factory: PeerConnectionFactory? = null
    private var signalingClient: PhoneSignalingClient? = null
    private var peerConnection: PeerConnection? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var remoteVideoTrack: VideoTrack? = null

    fun start() {
        onStatusChanged("初始化 WebRTC 接收环境 (${renderMode.title}模式)...")
        try {
            System.loadLibrary("jingle_peerconnection_so")
            Log.i(TAG, "已显式加载 jingle_peerconnection_so 动态库")
        } catch (t: Throwable) {
            Log.w(TAG, "加载 jingle_peerconnection_so 告警: ${t.message}")
        }

        WebRtcContext.initialize(context)
        val eglContext = WebRtcContext.rootEglBase.eglBaseContext

        // 根据所选模式构建独立的解码工厂
        val decoderFactory: VideoDecoderFactory = if (renderMode == RenderMode.CUSTOM) {
            Log.i(TAG, "[手机WebRTC] 模式: 自定义硬解拦截模式，使用 CustomDecoderFactory")
            CustomDecoderFactory { data, isKeyFrame, width, height, timestampNs ->
                onFrameReceived?.invoke(data, isKeyFrame, width, height, timestampNs)
            }
        } else {
            Log.i(TAG, "[手机WebRTC] 模式: WebRTC 官方原生解码模式，使用 DefaultVideoDecoderFactory + 安全代理")
            val defaultFactory = DefaultVideoDecoderFactory(eglContext)
            PhoneProxyVideoDecoderFactory(defaultFactory)
        }

        val audioDeviceModule = org.webrtc.audio.JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, false, true))
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableNetworkMonitor = true
            })
            .createPeerConnectionFactory()

        // 预先建立 PeerConnection，避免信令比 PC 先到的竞态
        createPeerConnection()

        // 发起 TCP 信令连接
        signalingClient = PhoneSignalingClient(
            carIp = carIp,
            carPort = carPort,
            onMessageReceived = ::handleSignalingMessage,
            onConnected = {
                onStatusChanged("已连接信令，等待车机端下发媒体 Offer...")
            },
            onDisconnected = {
                onStatusChanged("信令连接已断开")
                closePeerConnection()
            },
            onError = { err ->
                onStatusChanged("连接出错: $err")
            }
        ).apply { connect() }
    }

    private fun createPeerConnection() {
        val factory = factory ?: return
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            enableCpuOveruseDetection = false
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
                signalingClient?.sendMessage(
                    SignalingMessage(
                        type = SignalingMessage.TYPE_ICE_CANDIDATE,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                        candidate = candidate.sdp
                    )
                )
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "[WebRTC] ICE 连接状态: $state")
                onStatusChanged("WebRTC 链路: $state (${renderMode.title})")
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                    startStatsMonitoring()
                    requestKeyFrame()
                    if (renderMode == RenderMode.WEBRTC_BUILTIN) {
                        checkAndNotifyVideoTrack()
                    }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                Log.i(TAG, "[WebRTC] 远端视频 Track 已挂载 (onTrack): mid=${transceiver?.mid}, direction=${transceiver?.direction}, mediaType=${transceiver?.mediaType}")
                if (renderMode == RenderMode.WEBRTC_BUILTIN) {
                    val track = transceiver?.receiver?.track() as? VideoTrack
                    if (track != null) {
                        notifyTrackReceived(track, "onTrack")
                    }
                }
            }

            override fun onAddStream(stream: MediaStream?) {
                Log.i(TAG, "[WebRTC] 收到远端流 (onAddStream): ${stream?.id}")
                if (renderMode == RenderMode.WEBRTC_BUILTIN) {
                    stream?.videoTracks?.firstOrNull()?.let { track ->
                        notifyTrackReceived(track, "onAddStream")
                    }
                }
            }
        }) ?: return

        // 显式声明仅接收视频 (RECV_ONLY)，精准映射 Unified Plan 媒体描述
        try {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        } catch (e: Exception) {
            Log.w(TAG, "添加 RECV_ONLY 视频收发器告警: ${e.message}")
        }

        peerConnection = pc
    }

    private fun notifyTrackReceived(track: VideoTrack, source: String) {
        if (remoteVideoTrack != track) {
            Log.i(TAG, "[WebRTC原生渲染] 成功捕获远端视频轨道 ($source): ${track.id()}")
            remoteVideoTrack = track
            onVideoTrackReceived?.invoke(track)
        }
    }

    private fun checkAndNotifyVideoTrack() {
        val pc = peerConnection ?: return
        if (remoteVideoTrack != null) return
        for (receiver in pc.receivers) {
            val track = receiver.track() as? VideoTrack
            if (track != null) {
                notifyTrackReceived(track, "pc.receivers 扫描")
                break
            }
        }
    }

    private fun handleSignalingMessage(msg: SignalingMessage) {
        val pc = peerConnection ?: return
        when (msg.type) {
            SignalingMessage.TYPE_OFFER -> {
                val offerSdp = msg.sdp ?: return
                val videoSdpLines = offerSdp.lines().filter { it.startsWith("m=video") || it.startsWith("a=rtpmap") || it.startsWith("a=fmtp") }.joinToString(" | ")
                Log.i(TAG, "[WebRTC] 收到车机 Offer SDP. 协商行: $videoSdpLines")
                val offerDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)

                pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        Log.i(TAG, "[WebRTC] 设置远端 Offer 成功，创建 Answer")
                        synchronized(pendingIceCandidates) {
                            for (cand in pendingIceCandidates) {
                                pc.addIceCandidate(cand)
                            }
                            pendingIceCandidates.clear()
                        }

                        val constraints = MediaConstraints().apply {
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                        }

                        pc.createAnswer(object : SimpleSdpObserver() {
                            override fun onCreateSuccess(desc: SessionDescription?) {
                                desc ?: return
                                val answerLines = desc.description.lines().filter { it.startsWith("m=video") || it.startsWith("a=rtpmap") || it.startsWith("a=fmtp") }.joinToString(" | ")
                                Log.i(TAG, "[WebRTC] 创建 Answer 成功. 协商行: $answerLines")
                                pc.setLocalDescription(object : SimpleSdpObserver() {
                                    override fun onSetSuccess() {
                                        Log.i(TAG, "[WebRTC] 设置本地 Answer 成功，回传 Answer 至车机")
                                        signalingClient?.sendMessage(
                                            SignalingMessage(
                                                type = SignalingMessage.TYPE_ANSWER,
                                                sdp = desc.description
                                            )
                                        )
                                        // 提前主动向车机请求关键帧，实现首帧零等待
                                        requestKeyFrame()
                                        if (renderMode == RenderMode.WEBRTC_BUILTIN) {
                                            checkAndNotifyVideoTrack()
                                        }
                                    }

                                    override fun onSetFailure(error: String?) {
                                        Log.e(TAG, "[WebRTC] 设置本地 Answer 失败: $error")
                                    }
                                }, desc)
                            }

                            override fun onCreateFailure(error: String?) {
                                Log.e(TAG, "[WebRTC] 创建 Answer 失败: $error")
                            }
                        }, constraints)
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "[WebRTC] 设置远端 Offer 失败: $error")
                    }
                }, offerDesc)
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
            SignalingMessage.TYPE_DISCONNECT -> {
                Log.i(TAG, "[WebRTC] 车机端断开连接")
                closePeerConnection()
            }
        }
    }

    private val statsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastBytesReceived = 0L
    private var lastFramesReceived = 0L
    private var lastStatsTimestamp = 0L

    private val statsRunnable = object : Runnable {
        override fun run() {
            val pc = peerConnection ?: return
            pc.getStats { report ->
                var bytes: Long? = null
                var frames: Long? = null

                for (stat in report.statsMap.values) {
                    if (stat.type == "inbound-rtp" && (stat.members["kind"] == "video" || stat.members.containsKey("framesReceived"))) {
                        val bObj = stat.members["bytesReceived"]
                        bytes = when (bObj) {
                            is Number -> bObj.toLong()
                            is String -> bObj.toLongOrNull()
                            else -> null
                        }
                        val fObj = stat.members["framesReceived"] ?: stat.members["framesDecoded"]
                        frames = when (fObj) {
                            is Number -> fObj.toLong()
                            is String -> fObj.toLongOrNull()
                            else -> null
                        }
                        if (bytes != null && bytes > 0) break
                    }
                }

                if (bytes != null && frames != null) {
                    val now = System.currentTimeMillis()
                    if (lastStatsTimestamp > 0) {
                        val diffSec = (now - lastStatsTimestamp) / 1000.0
                        if (diffSec > 0) {
                            val bDiff = (bytes - lastBytesReceived).coerceAtLeast(0)
                            val fDiff = (frames - lastFramesReceived).coerceAtLeast(0)
                            val bitrateKbps = ((bDiff * 8.0) / (1000.0 * diffSec)).toInt()
                            val fps = (fDiff / diffSec).toInt()
                            onStatsUpdated?.invoke(fps, bitrateKbps)
                        }
                    }
                    lastBytesReceived = bytes
                    lastFramesReceived = frames
                    lastStatsTimestamp = now
                }
            }
            if (peerConnection != null) {
                statsHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun startStatsMonitoring() {
        stopStatsMonitoring()
        statsHandler.post(statsRunnable)
    }

    private fun stopStatsMonitoring() {
        statsHandler.removeCallbacks(statsRunnable)
        lastBytesReceived = 0L
        lastFramesReceived = 0L
        lastStatsTimestamp = 0L
    }

    private fun closePeerConnection() {
        stopStatsMonitoring()
        remoteVideoTrack = null
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
     * 主动向车机端发送信令请求一个 IDR 关键帧（包含完整 SPS/PPS）
     */
    fun requestKeyFrame() {
        Log.i(TAG, "[手机端WebRTC] 向车机信令端请求即时关键帧 (TYPE_REQUEST_KEY_FRAME)")
        signalingClient?.sendMessage(
            SignalingMessage(type = SignalingMessage.TYPE_REQUEST_KEY_FRAME)
        )
    }

    fun stop() {
        onStatusChanged("停止接收...")
        closePeerConnection()
        signalingClient?.disconnect()
        signalingClient = null

        try {
            factory?.dispose()
            factory = null
        } catch (e: Exception) {
            Log.e(TAG, "[WebRTC] 释放 PeerConnectionFactory 异常", e)
        }
        Log.i(TAG, "[WebRTC] 手机接收端已完全停止")
    }
}
