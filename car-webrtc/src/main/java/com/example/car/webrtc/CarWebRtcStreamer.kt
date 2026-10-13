package com.example.car.webrtc

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.protocol.SignalingMessage
import com.example.webrtc.core.SimplePeerConnectionObserver
import com.example.webrtc.core.SimpleSdpObserver
import com.example.webrtc.core.WebRtcFactoryHelper
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription

/**
 * High-level Car Unit WebRTC Screen Streamer Coordinator.
 */
class CarWebRtcStreamer(
    private val context: Context,
    private val signalingPort: Int = 22456,
    private val onStatusChanged: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "CarWebRtcStreamer"
    }

    private var factory: PeerConnectionFactory? = null
    private var screenCapturer: CarScreenCapturer? = null
    private var signalingServer: CarSignalingServer? = null
    private var peerConnection: PeerConnection? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    fun start(resultCode: Int, data: Intent, targetDisplayId: Int? = null) {
        onStatusChanged("初始化 WebRTC 服务...")
        try {
            System.loadLibrary("jingle_peerconnection_so")
            Log.i(TAG, "已显式加载 jingle_peerconnection_so 动态库")
        } catch (t: Throwable) {
            Log.w(TAG, "显式加载 jingle_peerconnection_so 告警: ${t.message}")
        }

        com.example.webrtc.core.WebRtcContext.initialize(context)
        val eglContext = com.example.webrtc.core.WebRtcContext.rootEglBase.eglBaseContext

        // =========================================================================
        // 【关键配置：H.264 硬件编码工厂构建】
        // 1. sharedContext (eglContext): 共享 EGL 上下文，确保 MediaProjection 捕获的 OES 纹理
        //    能以 GPU 显存零拷贝方式直通 MediaCodec 编码器 Input Surface，消灭 CPU 内存拷贝开销。
        // 2. enableIntelVp8Encoder (false): 针对 ARM 架构车机芯片，禁用 Intel x86 VP8 硬件加速。
        // 3. enableH264HighProfile (true): 【防掉帧与高清核心】开启 H.264 High Profile，
        //    激活 CABAC 算术熵编码与 8x8 频域空间变换矩阵。相比 Baseline 压缩率提升 30%，
        //    大幅减小关键帧（I帧）与动态运动帧（P帧）的数据体积，防止编码器缓冲区溢出导致丢帧。
        // =========================================================================
        val rawEncoderFactory = org.webrtc.DefaultVideoEncoderFactory(eglContext, false, true)
        val rawDecoderFactory = org.webrtc.DefaultVideoDecoderFactory(eglContext)
        val encoderFactory = CarProxyVideoEncoderFactory(rawEncoderFactory)
        val decoderFactory = CarProxyVideoDecoderFactory(rawDecoderFactory)

        val audioDeviceModule = org.webrtc.audio.JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        val factoryInstance = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options().apply {
                // 【关键参数：禁用系统网络监听器】
                // 车机与手机在 Wi-Fi 热点局域网下 IP 和路由固定，禁用 NetworkMonitor
                // 彻底避免 Android 系统网络切换广播（如断开移动蜂窝）导致 WebRTC 内部重置或推流暂停
                disableNetworkMonitor = true
            })
            .createPeerConnectionFactory()
        factory = factoryInstance

        screenCapturer = CarScreenCapturer(context, factoryInstance)
        screenCapturer?.startCapture(resultCode, data, targetDisplayId)

        signalingServer = CarSignalingServer(
            port = signalingPort,
            onMessageReceived = ::handleSignalingMessage,
            onClientConnected = { clientIp ->
                onStatusChanged("手机已连接 ($clientIp)，创建 WebRTC 链接...")
                createPeerConnectionAndOffer()
            },
            onClientDisconnected = {
                onStatusChanged("手机已断开，等待重新连接...")
                closePeerConnection()
            }
        )
        signalingServer?.start()
        onStatusChanged("车机推流已就绪，等待手机端连接 (端口 $signalingPort)...")
    }

    private fun createPeerConnectionAndOffer() {
        val factory = factory ?: return
        val videoTrack = screenCapturer?.videoTrack
        if (videoTrack == null) {
            Log.w(TAG, "车机端视频轨道 (VideoTrack) 尚未就绪，200ms 后自动重试创建 Offer...")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                createPeerConnectionAndOffer()
            }, 200)
            return
        }

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            // 【参数：SDP 语义规范】采用现代 UNIFIED_PLAN 标准，严格按照媒体轨道 (Track) 独立协商 Transceiver
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

            // 【关键参数：彻底禁用 CPU 过载丢帧检测 (enableCpuOveruseDetection = false)】
            // WebRTC 内部默认开启 OveruseFrameDetector。60fps 场景下单帧预算为 16.6ms。
            // 当 MediaCodec 偶发性单帧编码耗时超过 15ms 时，WebRTC 会误判为系统 CPU 过载，
            // 进而主动调用 DropFrame 强行丢弃屏幕捕获帧或降低分辨率。
            // 设为 false 彻底关闭该弃帧机制，确保屏幕采集画面 100% 全量送编。
            enableCpuOveruseDetection = false

            // 【关键参数：禁用 TCP 候选，走纯 UDP (SRTP) 直连传输】
            // 局域网热点下彻底消灭 TCP 重传机制导致的队头阻塞 (Head-of-Line Blocking) 与几百毫秒累积延迟
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED

            // 【参数：BUNDLE 策略】将所有音视频轨道复用到单一底层传输通道，最大化减少端口消耗与握手延迟
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE

            // 【参数：RTCP 复用】强制 RTP 音视频数据包与 RTCP 控制包复用同一网络通道与端口
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE

            // 【参数：ICE 收集策略】局域网直连环境无需公网多次打洞，单次收集 (GATHER_ONCE) 即可加速建联
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        closePeerConnection()

        val pc = factory.createPeerConnection(rtcConfig, object : SimplePeerConnectionObserver() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                Log.d(TAG, "生成本地 ICE Candidate: ${candidate.sdp}")
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
                Log.i(TAG, "[车机信令诊断] ICE 连接状态变更: $state")
                onStatusChanged("WebRTC 连接状态: $state")
            }
        })

        if (pc == null) {
            Log.e(TAG, "创建 PeerConnection 失败")
            return
        }

        peerConnection = pc
        val rtpSender = pc.addTrack(videoTrack, listOf("car_screen_stream"))

        // =========================================================================
        // 【时机二：PeerConnection 轨道初始绑定时的帧率与码率设置】
        // 调用位置：addTrack 将视频轨道绑定到 PeerConnection 之后
        // 作用原理：向此条视频轨道的底层 RtpSender 注入初始编码约束（包括最大帧率 60fps、
        //           保底码率 4Mbps、最大码率 10Mbps、以及禁止降级策略 DISABLED）。
        // =========================================================================
        applyRtpSenderParameters(rtpSender)

        val mediaConstraints = MediaConstraints().apply {
            // 车机为单向屏幕推流端，显式声明不接收远端音视频，精简 SDP 与资源分配
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc ?: return
                Log.i(TAG, "创建 SDP Offer 成功，已优化低延迟与起播码率")
                // =========================================================================
                // 【时机三：SDP Offer 协商时机 (SDP Negotiation - Bandwidth & Codec FMTP)】
                // 调用位置：生成本地 Offer 后、设置 LocalDescription 及发给手机端之前
                // 作用原理：在 SDP 文本的 m=video 媒体描述段中显式注入带宽协议参数：
                //           1. b=AS:10000 与 b=TIAS:10000000 告知传输层最高带宽为 10Mbps；
                //           2. a=fmtp 中注入 x-google-min-bitrate / start-bitrate / max-bitrate，
                //              告知 WebRTC GCC (拥塞控制引擎) 起播直接从 7Mbps 开始，不要经过漫长的低码率试探爬坡。
                // =========================================================================
                val modifiedSdp = optimizeSdpForLowLatency(desc.description)
                val offerDesc = SessionDescription(SessionDescription.Type.OFFER, modifiedSdp)

                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        Log.i(TAG, "设置本地描述成功，正在发送 Offer 给手机端")
                        applyRtpSenderParameters(rtpSender)
                        signalingServer?.sendMessage(
                            SignalingMessage(
                                type = SignalingMessage.TYPE_OFFER,
                                sdp = modifiedSdp
                            )
                        )
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "设置本地描述失败: $error")
                        onStatusChanged("设置本地描述 (Offer) 失败: $error")
                    }
                }, offerDesc)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "创建 Offer 失败: $error")
                onStatusChanged("创建 Offer 失败: $error")
            }
        }, mediaConstraints)
    }

    /**
     * 应用推流端 RtpSender 编码与推流参数
     * 控制帧率 (maxFramerate)、码率范围 (minBitrateBps / maxBitrateBps) 及画质策略 (degradationPreference)
     */
    private fun applyRtpSenderParameters(rtpSender: org.webrtc.RtpSender?) {
        rtpSender ?: return
        try {
            val params = rtpSender.parameters
            // 1. 禁用降级策略 (DISABLED):
            //    WebRTC 默认会在 CPU 或网络波动时自动下调分辨率 (MAINTAIN_FRAMERATE) 或降低帧率 (MAINTAIN_RESOLUTION)。
            //    而在车载热点私网这种高带宽环境下，我们锁死 DISABLED，强制不降级，保证原生 1280x800 分辨率与 60 帧率。
            params.degradationPreference = org.webrtc.RtpParameters.DegradationPreference.DISABLED
            if (params.encodings.isNotEmpty()) {
                val encoding = params.encodings[0]
                // 2. 最小码率 (minBitrateBps): 4.0 Mbps，起播保底码率，绝不允许编码器为了省带宽把文字图标压缩模糊
                encoding.minBitrateBps = 4_000_000
                // 3. 最大码率 (maxBitrateBps): 10.0 Mbps，局域网满画质上限，彻底消灭高速滑动时的马赛克现象
                encoding.maxBitrateBps = 10_000_000
                // 4. 最大帧率 (maxFramerate): 60 FPS，放开 WebRTC 编码器的帧率截断，配合源头的 60fps 采集实现超流畅跟手体验
                encoding.maxFramerate = 60
                encoding.active = true
            }
            rtpSender.parameters = params
            Log.i(TAG, "已成功应用车机热点低延迟超清 RtpParameters: DISABLED 降级, 4.0~10.0Mbps, 60fps")
        } catch (e: Exception) {
            Log.w(TAG, "应用 RtpParameters 告警: ${e.message}")
        }
    }

    /**
     * 修改 SDP 描述，注入 WebRTC GCC 拥塞控制和带宽限制参数 (时机三的具体实现)
     */
    private fun optimizeSdpForLowLatency(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val result = mutableListOf<String>()
        var inVideoSection = false

        for (line in lines) {
            if (line.startsWith("m=video")) {
                inVideoSection = true
                result.add(line)
                // 车机热点局域网专属高画质低延迟带宽 (10Mbps)，彻底消灭高运动下的马赛克与编码排队顿挫
                // b=AS: (Application Specific) 针对应用层的带宽限制，单位 kbps (10000 = 10Mbps)
                // b=TIAS: (Transport Independent Application Specific) 针对传输层的最大带宽限制，单位 bps
                result.add("b=AS:10000")
                result.add("b=TIAS:10000000")
                continue
            } else if (line.startsWith("m=")) {
                inVideoSection = false
            }

            if (inVideoSection && line.startsWith("a=fmtp:")) {
                var fmtp = line
                // x-google-min-bitrate: 4000 (保底 4Mbps)
                // x-google-start-bitrate: 7000 (起播直接以 7Mbps 开始，省去数秒的从几百k慢慢往上试探的时间)
                // x-google-max-bitrate: 10000 (最高 10Mbps)
                if (!fmtp.contains("x-google-min-bitrate")) {
                    fmtp = "$fmtp;x-google-min-bitrate=4000;x-google-start-bitrate=7000;x-google-max-bitrate=10000"
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
                Log.i(TAG, "收到手机端发送的 Answer SDP")
                val sdpDesc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        Log.i(TAG, "设置远端描述 (Answer) 成功")
                        synchronized(pendingIceCandidates) {
                            if (pendingIceCandidates.isNotEmpty()) {
                                Log.i(TAG, "远端描述就绪，补充应用已暂存的 ${pendingIceCandidates.size} 个来自手机端的 ICE Candidate")
                                for (cand in pendingIceCandidates) {
                                    pc.addIceCandidate(cand)
                                }
                                pendingIceCandidates.clear()
                            }
                        }
                        // =========================================================================
                        // 【时机四：远端 Answer 协商生效后的参数二次固化】
                        // 调用位置：pc.setRemoteDescription(Answer) 成功回调之后
                        // 作用原理：WebRTC 底层 C++ 协议栈在完成双向 SDP 握手结算时，内部 RtpSender 参数可能被
                        //           重置为默认值。因此必须在此处再次调用 applyRtpSenderParameters 重新下发
                        //           minBitrateBps/maxBitrateBps/maxFramerate/DISABLED 策略，确保参数持续生效。
                        // =========================================================================
                        val senders = pc.senders
                        if (senders.isNotEmpty()) {
                            applyRtpSenderParameters(senders[0])
                        }
                        startStatsMonitoring()
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "设置远端描述失败: $error")
                    }
                }, sdpDesc)
            }
            SignalingMessage.TYPE_ICE_CANDIDATE -> {
                val candidateSdp = msg.candidate ?: return
                val candidate = IceCandidate(msg.sdpMid ?: "", msg.sdpMLineIndex ?: 0, candidateSdp)
                if (pc.remoteDescription != null) {
                    Log.d(TAG, "添加来自手机端的远端 ICE Candidate")
                    pc.addIceCandidate(candidate)
                } else {
                    Log.d(TAG, "远端描述尚未就绪，暂存来自手机端的 ICE Candidate")
                    synchronized(pendingIceCandidates) {
                        pendingIceCandidates.add(candidate)
                    }
                }
            }
            SignalingMessage.TYPE_DISCONNECT -> {
                Log.i(TAG, "手机客户端发送了断开连接信号")
                closePeerConnection()
            }
        }
    }

    private val statsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastSentFrames = 0L
    private var lastEncodedFrames = 0L
    private var lastStatsTimeMs = 0L

    private val statsRunnable = object : Runnable {
        override fun run() {
            val pc = peerConnection ?: return
            pc.getStats { report ->
                for (stat in report.statsMap.values) {
                    if (stat.type == "outbound-rtp" && (stat.members["kind"] == "video" || stat.members.containsKey("framesEncoded"))) {
                        val framesEncoded = (stat.members["framesEncoded"] as? Number)?.toLong()
                            ?: (stat.members["framesEncoded"] as? String)?.toLongOrNull() ?: 0L
                        val framesSentObj = stat.members["framesSent"]
                        val framesSent = when (framesSentObj) {
                            is Number -> framesSentObj.toLong()
                            is String -> framesSentObj.toLongOrNull() ?: framesEncoded
                            else -> framesEncoded
                        }
                        val packetsSent = (stat.members["packetsSent"] as? Number)?.toLong()
                            ?: (stat.members["packetsSent"] as? String)?.toLongOrNull() ?: 0L
                        val bytesSent = (stat.members["bytesSent"] as? Number)?.toLong()
                            ?: (stat.members["bytesSent"] as? String)?.toLongOrNull() ?: 0L
                        val targetBitrate = (stat.members["targetBitrate"] as? Number)?.toDouble() ?: 0.0
                        val qualityLimitation = stat.members["qualityLimitationReason"] as? String ?: "none"
                        val now = System.currentTimeMillis()
                        if (lastStatsTimeMs > 0) {
                            val timeDiffSec = (now - lastStatsTimeMs) / 1000.0
                            if (timeDiffSec > 0) {
                                val encodeDiff = (framesEncoded - lastEncodedFrames).coerceAtLeast(0)
                                val sentDiff = (framesSent - lastSentFrames).coerceAtLeast(0)
                                val encodeFps = (encodeDiff / timeDiffSec).toInt()
                                val sendFps = (sentDiff / timeDiffSec).toInt()
                                val bitrateKbps = (targetBitrate / 1000.0).toInt()
                                val bytesSentMb = String.format(java.util.Locale.US, "%.2f", bytesSent / (1024.0 * 1024.0))

                                Log.i(
                                    TAG,
                                    "[车机推流诊断] 实时编码推流: 编码帧数=$framesEncoded 帧 (区间新增 $encodeDiff 帧, 编码帧率 $encodeFps FPS), 发送帧数=$framesSent 帧 (区间新增 $sentDiff 帧, 发送帧率 $sendFps FPS), 发送数据=${bytesSentMb} MB (RTP包: $packetsSent), 目标码率=${bitrateKbps} Kbps, 瓶颈限制原因=$qualityLimitation"
                                )
                            }
                        }
                        lastSentFrames = framesSent
                        lastEncodedFrames = framesEncoded
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
        lastEncodedFrames = 0L
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
            Log.e(TAG, "关闭 PeerConnection 异常", e)
        }
    }

    fun stop() {
        onStatusChanged("停止推流...")
        closePeerConnection()
        signalingServer?.stop()
        signalingServer = null

        screenCapturer?.stopCapture()
        screenCapturer = null

        try {
            factory?.dispose()
            factory = null
        } catch (e: Exception) {
            Log.e(TAG, "销毁 PeerConnectionFactory 异常", e)
        }
    }
}
