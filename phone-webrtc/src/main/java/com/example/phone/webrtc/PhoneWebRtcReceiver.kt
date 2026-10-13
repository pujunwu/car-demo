package com.example.phone.webrtc

import android.content.Context
import android.util.Log
import com.example.protocol.SignalingMessage
import com.example.webrtc.core.SimplePeerConnectionObserver
import com.example.webrtc.core.SimpleSdpObserver
import com.example.webrtc.core.WebRtcFactoryHelper
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * High-level Phone Unit WebRTC Screen Receiver Coordinator.
 */
class PhoneWebRtcReceiver(
    private val context: Context,
    private val carIp: String,
    private val carPort: Int = 22456,
    private val onStatusChanged: (String) -> Unit = {},
    private val onVideoTrackReceived: (VideoTrack) -> Unit = {},
    private val onStatsUpdated: ((fps: Int, bitrateKbps: Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "PhoneWebRtcReceiver"
    }

    private var factory: PeerConnectionFactory? = null
    private var signalingClient: PhoneSignalingClient? = null
    private var peerConnection: PeerConnection? = null
    private var remoteVideoTrack: VideoTrack? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    fun start() {
        onStatusChanged("初始化 WebRTC 接收端...")
        try {
            System.loadLibrary("jingle_peerconnection_so")
            Log.i(TAG, "已显式加载 jingle_peerconnection_so 动态库")
        } catch (t: Throwable) {
            Log.w(TAG, "显式加载 jingle_peerconnection_so 告警: ${t.message}")
        }
        
        com.example.webrtc.core.WebRtcContext.initialize(context)
        val eglContext = com.example.webrtc.core.WebRtcContext.rootEglBase.eglBaseContext

        val codecList = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos
        val avcDecoders = codecList.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals("video/avc", ignoreCase = true) } }
        val avcNames = avcDecoders.map { it.name }
        Log.i(TAG, "[手机解码诊断] 系统所有可用 H264 解码器: $avcNames")

        val hasLowLatency = avcNames.any { it.contains("lowlatency", ignoreCase = true) || it.contains("low_latency", ignoreCase = true) }
        Log.i(TAG, "[手机解码诊断] 是否检测到低延迟专属解码器: $hasLowLatency")

        val codecAllowed = org.webrtc.Predicate<android.media.MediaCodecInfo> { info ->
            val name = info.name.lowercase()
            if (hasLowLatency) {
                // 如果系统支持 lowlatency 专属低延迟解码器，优先屏蔽存在 8 帧 B 帧重排缓冲的标准解码器
                if (name == "c2.mtk.avc.decoder" || name == "omx.mtk.video.decoder.avc") {
                    Log.i(TAG, "[手机解码诊断] 发现系统原生低延迟解码器，自动跳过高缓冲标准驱动: ${info.name}")
                    return@Predicate false
                }
            }
            true
        }

        val rawEncoderFactory = org.webrtc.DefaultVideoEncoderFactory(eglContext, false, true)
        // =========================================================================
        // 【核心技术突破：sharedContext 设为 null 突破 Android Codec2 缓冲池死锁】
        // 1. 为什么设为 null？
        //    若传入 eglContext，MediaCodec 将运行在 Surface 输出模式。在 Android 12+ (MTK/高通/紫光) 驱动下，
        //    底层 bufferpool2 的 GraphicBuffer 有硬性上限 (仅 8 个 buffer)。一旦渲染管线或系统合图稍有延迟，
        //    缓冲池立即耗尽，导致大量 "bufferpool2 8 used buffers" 阻塞与 "78/340 fetch/transfer" 严重丢帧卡顿！
        //    传入 null 强制 MediaCodec 工作在 YUV420 字节直出模式，无限制直接投递至 SurfaceViewRenderer 着色器上屏，
        //    彻底消灭缓冲池死锁，实现 0 丢帧与超低延迟。
        // 2. 为什么能反向保护车机端 H.264 编码不掉帧？
        //    WebRTC 具有 RTCP 反馈机制。一旦手机端解码卡死或丢帧，手机会向车机高频狂发 PLI (关键帧请求)！
        //    车机被迫频繁编码大体积 I 帧，瞬间拖垮车机硬件编码器导致车机严重掉帧。
        //    手机端 0 丢帧彻底切断了这一恶性反馈链，使车机能够以平滑的 P 帧极速推流。
        // =========================================================================
        val rawHardwareDecoderFactory = org.webrtc.HardwareVideoDecoderFactory(null, codecAllowed)
        val rawSoftwareDecoderFactory = org.webrtc.SoftwareVideoDecoderFactory()

        val rawDecoderFactory = object : org.webrtc.VideoDecoderFactory {
            override fun createDecoder(info: org.webrtc.VideoCodecInfo?): org.webrtc.VideoDecoder? {
                return rawHardwareDecoderFactory.createDecoder(info) ?: rawSoftwareDecoderFactory.createDecoder(info)
            }

            override fun getSupportedCodecs(): Array<org.webrtc.VideoCodecInfo> {
                val list = mutableListOf<org.webrtc.VideoCodecInfo>()
                list.addAll(rawHardwareDecoderFactory.supportedCodecs)
                list.addAll(rawSoftwareDecoderFactory.supportedCodecs)
                return list.toTypedArray()
            }
        }
        val encoderFactory = PhoneProxyVideoEncoderFactory(rawEncoderFactory)
        val decoderFactory = PhoneProxyVideoDecoderFactory(rawDecoderFactory)

        val audioDeviceModule = org.webrtc.audio.JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableNetworkMonitor = true
            })
            .createPeerConnectionFactory()

        // 关键修复：在建立信令 TCP 连接之前，预先创建好 PeerConnection！
        // 彻底避免由于 TCP 收到 Offer 的速度快于 PeerConnection 创建速度而导致 Offer 被吞掉的竞态问题
        createPeerConnection()

        signalingClient = PhoneSignalingClient(
            carIp = carIp,
            carPort = carPort,
            onMessageReceived = ::handleSignalingMessage,
            onConnected = {
                onStatusChanged("已建立信令连接，等待车机发送视频 Offer...")
            },
            onDisconnected = {
                onStatusChanged("信令连接已断开")
                closePeerConnection()
            },
            onError = { error ->
                onStatusChanged("连接失败: $error")
            }
        )
        signalingClient?.connect()
    }

    private fun createPeerConnection() {
        val factory = factory ?: return
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // 【参数：禁用接收侧 CPU 过载检测】避免手机端因轻微渲染抖动而向车机发送负向拥塞降质建议
            enableCpuOveruseDetection = false
            // 【关键优化：纯 UDP 传输】彻底禁用 TCP 候选，消除局域网热点下 TCP 队头阻塞导致的几百毫秒延迟与严重顿挫
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        closePeerConnection()

        val pc = factory.createPeerConnection(rtcConfig, object : SimplePeerConnectionObserver() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                Log.d(TAG, "[手机连接诊断] 生成本地 ICE Candidate: ${candidate.sdp}")
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
                Log.i(TAG, "[手机连接诊断] ICE 连接状态变更: $state")
                onStatusChanged("WebRTC 画面链接状态: $state")
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                    Log.i(TAG, "[手机连接诊断] ICE 连接已建立，触发 Track 扫描并启动数据监控")
                    checkAndNotifyVideoTrack()
                    startStatsMonitoring()
                }
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track() as? VideoTrack
                Log.i(TAG, "[手机连接诊断] onAddTrack 回调: trackId=${track?.id()}, kind=${track?.kind()}, enabled=${track?.enabled()}")
                if (track != null) {
                    notifyTrackReceived(track, "onAddTrack")
                }
            }

            override fun onAddStream(stream: MediaStream?) {
                val videoTracks = stream?.videoTracks ?: return
                Log.i(TAG, "[手机连接诊断] onAddStream 回调: streamId=${stream.id}, tracksCount=${videoTracks.size}")
                if (videoTracks.isNotEmpty()) {
                    notifyTrackReceived(videoTracks[0], "onAddStream")
                }
            }
        })

        if (pc == null) {
            Log.e(TAG, "创建 PeerConnection 失败")
            return
        }

        // =========================================================================
        // 【关键配置：显式注册 RECV_ONLY 视频收发器 (Unified Plan)】
        // 作用原理：手机端作为纯接收端，在收到 Offer 之前预先注册 RECV_ONLY Transceiver。
        //           确保两端在协商 SDP Answer 时，底层自动生成正确的 m=video 接收端口与 payload mapping，
        //           并在连接建立后第一时间直接分发数据包至硬件解码器，消灭首帧出流延迟。
        // =========================================================================
        try {
            pc.addTransceiver(
                org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                org.webrtc.RtpTransceiver.RtpTransceiverInit(org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        } catch (e: Exception) {
            Log.w(TAG, "添加视频接收 Transceiver 异常: ${e.message}")
        }

        peerConnection = pc
    }

    private fun notifyTrackReceived(track: VideoTrack, source: String) {
        if (remoteVideoTrack != track) {
            Log.i(TAG, "成功捕获远端视频轨道 ($source): ${track.id()}")
            remoteVideoTrack = track
            onVideoTrackReceived(track)
        }
    }

    private fun checkAndNotifyVideoTrack() {
        val pc = peerConnection ?: return
        if (remoteVideoTrack != null) return
        for (receiver in pc.receivers) {
            val track = receiver.track() as? VideoTrack
            if (track != null) {
                notifyTrackReceived(track, "pc.receivers 主动扫描")
                break
            }
        }
    }

    private fun handleSignalingMessage(msg: SignalingMessage) {
        val pc = peerConnection ?: return
        when (msg.type) {
            SignalingMessage.TYPE_OFFER -> {
                val rawOfferSdp = msg.sdp ?: return
                Log.i(TAG, "[手机信令诊断] 收到车机端发送的原始 Offer SDP (长度: ${rawOfferSdp.length})")
                val offerDesc = SessionDescription(SessionDescription.Type.OFFER, rawOfferSdp)
                
                pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        Log.i(TAG, "[手机信令诊断] 设置远端描述 (Offer) 成功，开始创建 Answer...")
                        synchronized(pendingIceCandidates) {
                            if (pendingIceCandidates.isNotEmpty()) {
                                Log.i(TAG, "[手机信令诊断] 远端描述就绪，补充应用已暂存的 ${pendingIceCandidates.size} 个 ICE Candidate")
                                for (cand in pendingIceCandidates) {
                                    pc.addIceCandidate(cand)
                                }
                                pendingIceCandidates.clear()
                            }
                        }

                        val mediaConstraints = MediaConstraints().apply {
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                        }

                        pc.createAnswer(object : SimpleSdpObserver() {
                            override fun onCreateSuccess(desc: SessionDescription?) {
                                desc ?: return
                                val hasVideo = desc.description.contains("m=video")
                                Log.i(TAG, "[手机信令诊断] 创建 Answer SDP 成功 (是否包含有效视频轨道: $hasVideo)，准备设置本地描述并回传")
                                pc.setLocalDescription(object : SimpleSdpObserver() {
                                    override fun onSetSuccess() {
                                        Log.i(TAG, "[手机信令诊断] 设置本地描述成功，正在发送 Answer 给车机端")
                                        signalingClient?.sendMessage(
                                            SignalingMessage(
                                                type = SignalingMessage.TYPE_ANSWER,
                                                sdp = desc.description
                                            )
                                        )
                                    }

                                    override fun onSetFailure(error: String?) {
                                        Log.e(TAG, "[手机信令诊断] 设置本地描述 (Answer) 失败: $error")
                                        onStatusChanged("设置本地描述 (Answer) 失败: $error")
                                    }
                                }, desc)
                            }

                            override fun onCreateFailure(error: String?) {
                                Log.e(TAG, "[手机信令诊断] 创建 Answer 失败: $error")
                                onStatusChanged("创建 Answer 失败: $error")
                            }
                        }, mediaConstraints)
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "[手机信令诊断] 设置远端描述 (Offer) 失败: $error")
                        onStatusChanged("设置远端描述 (Offer) 失败: $error")
                    }
                }, offerDesc)
            }
            SignalingMessage.TYPE_ICE_CANDIDATE -> {
                val candidateSdp = msg.candidate ?: return
                Log.d(TAG, "[手机信令诊断] 收到车机 ICE Candidate: $candidateSdp")
                val candidate = IceCandidate(msg.sdpMid ?: "", msg.sdpMLineIndex ?: 0, candidateSdp)
                if (pc.remoteDescription != null) {
                    pc.addIceCandidate(candidate)
                } else {
                    Log.d(TAG, "[手机信令诊断] 远端描述尚未就绪，暂存 ICE Candidate")
                    synchronized(pendingIceCandidates) {
                        pendingIceCandidates.add(candidate)
                    }
                }
            }
        }
    }

    private val statsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastBytesReceived = 0L
    private var lastFramesReceived = 0L
    private var lastFramesDecoded = 0L
    private var lastStatsTimestamp = 0L

    private val statsRunnable = object : Runnable {
        override fun run() {
            val pc = peerConnection ?: return
            pc.getStats { report ->
                var bytes: Long? = null
                var framesReceived: Long? = null
                var framesDecoded: Long? = null
                var framesDropped: Long = 0L
                var packetsReceived: Long = 0L

                for (stat in report.statsMap.values) {
                    val isVideoInbound = stat.type == "inbound-rtp" && (
                        stat.members.containsKey("framesDecoded") ||
                        stat.members.containsKey("framesReceived") ||
                        stat.members.containsKey("frameWidth") ||
                        stat.members["kind"] == "video" ||
                        stat.members["mediaType"] == "video"
                    )
                    if (isVideoInbound) {
                        val bytesObj = stat.members["bytesReceived"]
                        bytes = when (bytesObj) {
                            is Number -> bytesObj.toLong()
                            is String -> bytesObj.toLongOrNull()
                            else -> null
                        }

                        val recvObj = stat.members["framesReceived"]
                        framesReceived = when (recvObj) {
                            is Number -> recvObj.toLong()
                            is String -> recvObj.toLongOrNull()
                            else -> null
                        }

                        val decodeObj = stat.members["framesDecoded"]
                        framesDecoded = when (decodeObj) {
                            is Number -> decodeObj.toLong()
                            is String -> decodeObj.toLongOrNull()
                            else -> null
                        }

                        val dropObj = stat.members["framesDropped"]
                        framesDropped = when (dropObj) {
                            is Number -> dropObj.toLong()
                            is String -> dropObj.toLongOrNull() ?: 0L
                            else -> 0L
                        }

                        val packetObj = stat.members["packetsReceived"]
                        packetsReceived = when (packetObj) {
                            is Number -> packetObj.toLong()
                            is String -> packetObj.toLongOrNull() ?: 0L
                            else -> 0L
                        }

                        // 某些 WebRTC 底层若未单独提供 framesReceived，则以 framesDecoded + framesDropped 兜底
                        if (framesReceived == null && framesDecoded != null) {
                            framesReceived = framesDecoded + framesDropped
                        }

                        if (bytes != null && bytes > 0) {
                            break
                        }
                    }
                }

                if (bytes != null && (framesDecoded != null || framesReceived != null)) {
                    val actualFramesReceived = framesReceived ?: 0L
                    val actualFramesDecoded = framesDecoded ?: 0L
                    val now = System.currentTimeMillis()
                    if (lastStatsTimestamp > 0) {
                        val timeDiffSec = (now - lastStatsTimestamp) / 1000.0
                        if (timeDiffSec > 0) {
                            val bytesDiff = (bytes - lastBytesReceived).coerceAtLeast(0)
                            val recvFramesDiff = (actualFramesReceived - lastFramesReceived).coerceAtLeast(0)
                            val decodedFramesDiff = (actualFramesDecoded - lastFramesDecoded).coerceAtLeast(0)
                            val bitrateKbps = ((bytesDiff * 8.0) / (1000.0 * timeDiffSec)).toInt()
                            val recvFps = (recvFramesDiff / timeDiffSec).toInt()
                            val decodeFps = (decodedFramesDiff / timeDiffSec).toInt()
                            val bytesReceivedMb = String.format(java.util.Locale.US, "%.2f", bytes / (1024.0 * 1024.0))

                            Log.i(
                                TAG,
                                "[手机接收诊断] 实时解码渲染: 收到帧数=$actualFramesReceived 帧 (区间新增 $recvFramesDiff 帧, 接收帧率 $recvFps FPS), 解码帧数=$actualFramesDecoded 帧 (区间新增 $decodedFramesDiff 帧, 解码帧率 $decodeFps FPS, 丢帧数=$framesDropped 帧), 收到数据=${bytesReceivedMb} MB (RTP包: $packetsReceived), 接收码率=${bitrateKbps} Kbps"
                            )
                            onStatsUpdated?.invoke(decodeFps, bitrateKbps)
                        }
                    }
                    lastBytesReceived = bytes
                    lastFramesReceived = actualFramesReceived
                    lastFramesDecoded = actualFramesDecoded
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
        lastFramesDecoded = 0L
        lastStatsTimestamp = 0L
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
            Log.e(TAG, "关闭 PeerConnection 异常", e)
        }
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
            Log.e(TAG, "销毁 PeerConnectionFactory 异常", e)
        }
    }
}
