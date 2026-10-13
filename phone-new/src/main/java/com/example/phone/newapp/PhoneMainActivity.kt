package com.example.phone.newapp

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.phone.newapp.protocol.NetUtils
import com.example.phone.newapp.protocol.UdpDiscovery
import com.example.phone.newapp.render.AspectRatioSurfaceView
import com.example.phone.newapp.render.H264Decoder
import com.example.phone.newapp.render.RenderMode
import com.example.phone.newapp.ui.PhoneHomeScreen
import com.example.phone.newapp.webrtc.PhoneWebRtcReceiver
import com.example.phone.newapp.webrtc.core.WebRtcContext
import org.webrtc.VideoTrack

/**
 * 手机端主界面 Activity (phone-new 独立双渲染融合方案)
 *
 * 核心架构设计：
 * 1. 【两套渲染管线完全解耦独立】：
 *    - 自定义硬解模式 (RenderMode.CUSTOM)：
 *      WebRTC 码流拦截 -> H264Decoder (MediaCodec) -> AspectRatioSurfaceView。
 *    - WebRTC 原生渲染模式 (RenderMode.WEBRTC_BUILTIN)：
 *      WebRTC 官方 DefaultVideoDecoderFactory 解码 -> VideoTrack -> 官方 SurfaceViewRenderer 控件。
 * 2. 【一键平滑热切换】：
 *    通过界面切换按钮，优雅断开并重建对应管线，释放前一个模式的所有资源，杜绝内存与驱动泄露。
 * 3. 【多端协同】：支持 UDP 自动发现车机、横竖屏自适应、比例维持与裁切切换。
 */
class PhoneMainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PhoneMainActivity"
        private const val DEFAULT_PORT = "22456"
    }

    private var receiver: PhoneWebRtcReceiver? = null
    private var decoder: H264Decoder? = null
    private var currentSurfaceHolder: SurfaceHolder? = null
    private var beaconListener: UdpDiscovery.BeaconListener? = null
    private var shouldConnectPending = false

    private var serverIp by mutableStateOf("192.168.1.100")
    private var serverPort by mutableStateOf(DEFAULT_PORT)
    private var discoveredCarIp by mutableStateOf<String?>(null)
    private var isConnected by mutableStateOf(false)
    private var statusText by mutableStateOf("未连接")
    private var errorMessage by mutableStateOf<String?>(null)

    // 当前渲染模式 (默认使用自定义硬解，可随时通过界面按钮切换)
    private var renderMode by mutableStateOf(RenderMode.CUSTOM)

    // WebRTC 原生渲染时接收到的 VideoTrack
    private var remoteVideoTrack by mutableStateOf<VideoTrack?>(null)

    private var videoWidth by mutableIntStateOf(1280)
    private var videoHeight by mutableIntStateOf(720)
    private var fps by mutableIntStateOf(0)
    private var bitrateKbps by mutableIntStateOf(0)

    private var scaleType by mutableStateOf(AspectRatioSurfaceView.ScaleType.FIT)
    private var isLandscape by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 异形屏全屏适配
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 预先初始化 WebRTC EGL 全局环境
        WebRtcContext.initialize(this)

        val localIp = NetUtils.getLocalIpAddress()
        if (localIp != "127.0.0.1") {
            serverIp = localIp
        }

        // 启动 UDP 监听器，自动捕获车机广播
        beaconListener = UdpDiscovery.BeaconListener { ip, port ->
            runOnUiThread {
                val validPort = port.takeIf { it in 1..65535 } ?: 22456
                Log.i(TAG, "[手机端] UDP 发现车机设备: $ip:$validPort")
                discoveredCarIp = ip
                if (!isConnected) {
                    serverIp = ip
                    serverPort = validPort.toString()
                }
            }
        }.apply { start() }

        setContent {
            PhoneHomeScreen(
                serverIp = serverIp,
                serverPort = serverPort,
                renderMode = renderMode,
                isConnected = isConnected,
                statusText = statusText,
                errorMessage = errorMessage,
                discoveredCarIp = discoveredCarIp,
                remoteVideoTrack = remoteVideoTrack,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                fps = fps,
                bitrateKbps = bitrateKbps,
                scaleType = scaleType,
                isLandscape = isLandscape,
                onIpChanged = { ip -> serverIp = ip },
                onPortChanged = { port -> serverPort = port },
                onRenderModeChanged = { newMode -> switchRenderMode(newMode) },
                onScaleTypeChanged = { type -> scaleType = type },
                onToggleOrientation = {
                    isLandscape = !isLandscape
                    requestedOrientation = if (isLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                    toggleSystemBars(!isLandscape)
                },
                onToggleConnection = { toggleConnection() },
                onSurfaceReady = { holder ->
                    Log.i(TAG, "[手机端] 自定义渲染 Surface 已就绪")
                    currentSurfaceHolder = holder
                    decoder?.updateSurface(holder.surface)
                    if (shouldConnectPending && renderMode == RenderMode.CUSTOM) {
                        shouldConnectPending = false
                        connectInternalCustom(holder)
                    }
                },
                onSurfaceDestroyed = {
                    Log.i(TAG, "[手机端] 自定义渲染 Surface 已销毁")
                    currentSurfaceHolder = null
                    if (renderMode == RenderMode.CUSTOM) {
                        decoder?.stop()
                        decoder = null
                    }
                },
                onVideoResolutionChanged = { w, h ->
                    runOnUiThread {
                        videoWidth = w
                        videoHeight = h
                    }
                }
            )
        }
    }

    private fun toggleSystemBars(show: Boolean) {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (show) {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun toggleConnection() {
        if (isConnected) {
            disconnect()
        } else {
            connect()
        }
    }

    /**
     * 渲染模式切换处理逻辑：
     * 1. 若在连接状态下切换，先彻底断开旧模式管线并释放底层资源。
     * 2. 更新选中的 renderMode。
     * 3. 自动以新模式发起重新连接，实现两套渲染无缝独立切换。
     */
    private fun switchRenderMode(newMode: RenderMode) {
        if (renderMode == newMode) return
        Log.i(TAG, "[手机端] 切换渲染模式: ${renderMode.title} -> ${newMode.title}")
        val wasConnected = isConnected
        disconnect()
        renderMode = newMode
        if (wasConnected) {
            connect()
        }
    }

    private fun connect() {
        when (renderMode) {
            RenderMode.CUSTOM -> {
                val holder = currentSurfaceHolder
                if (holder == null) {
                    Log.w(TAG, "[手机端] 自定义 Surface 尚未就绪，挂起连接等待 SurfaceCreated 回调")
                    shouldConnectPending = true
                    return
                }
                connectInternalCustom(holder)
            }
            RenderMode.WEBRTC_BUILTIN -> {
                // WebRTC 原生模式由 SurfaceViewRenderer 自行管理内部 Surface，无需等待外部 SurfaceHolder
                connectInternalWebRtc()
            }
        }
    }

    /**
     * 方案一：自定义硬解连接流程
     * 1. 初始化自定义 [H264Decoder]，渲染直通 Surface。
     * 2. 初始化 [PhoneWebRtcReceiver(renderMode = CUSTOM)]，拦截原始 H.264 码流交给 [H264Decoder]。
     */
    private fun connectInternalCustom(holder: SurfaceHolder) {
        disconnect()

        val port = serverPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22456
        Log.i(TAG, "[手机端] 发起连接车机: ${serverIp.trim()}:$port (模式: 自定义硬件解码直出)")

        // 1. 初始化自定义硬件解码器
        val dec = H264Decoder(holder.surface) { w, h ->
            runOnUiThread {
                videoWidth = w
                videoHeight = h
            }
        }
        dec.onRequestKeyFrame = {
            receiver?.requestKeyFrame()
        }
        dec.onRenderFpsUpdated = { renderFps ->
            runOnUiThread {
                fps = renderFps
            }
        }
        decoder = dec

        var bytesReceivedThisSecond = 0L
        var lastBitrateTimeMs = System.currentTimeMillis()

        // 2. 初始化 WebRTC 接收器，拦截码流转交自定义硬解器
        receiver = PhoneWebRtcReceiver(
            context = this,
            carIp = serverIp.trim(),
            carPort = port,
            renderMode = RenderMode.CUSTOM,
            onStatusChanged = { status ->
                runOnUiThread {
                    statusText = status
                    if (status.contains("连接出错") || status.contains("断开")) {
                        isConnected = false
                    }
                }
            },
            onFrameReceived = { data, isKeyFrame, w, h, ts ->
                decoder?.decodeFrame(data, isKeyFrame, w, h, ts)

                bytesReceivedThisSecond += data.size
                val nowMs = System.currentTimeMillis()
                val diffMs = nowMs - lastBitrateTimeMs
                if (diffMs >= 1000) {
                    val kbps = ((bytesReceivedThisSecond * 8.0) / diffMs).toInt()
                    bytesReceivedThisSecond = 0L
                    lastBitrateTimeMs = nowMs
                    runOnUiThread {
                        bitrateKbps = kbps
                    }
                }

                if (!isConnected) {
                    runOnUiThread {
                        isConnected = true
                        statusText = "已连接并渲染画面 (自定义硬解)"
                    }
                }
            },
            onStatsUpdated = { f, b ->
                runOnUiThread {
                    if (f > 0) fps = f
                    if (b > 0) bitrateKbps = b
                }
            }
        ).apply {
            start()
        }
    }

    /**
     * 方案二：WebRTC 官方原生解码与 SurfaceViewRenderer 控件渲染流程
     * 1. 确保释放自定义 [H264Decoder]，两套管线完全隔离。
     * 2. 初始化 [PhoneWebRtcReceiver(renderMode = WEBRTC_BUILTIN)]，使用官方 DefaultVideoDecoderFactory。
     * 3. 远端捕获到 [VideoTrack] 后直接投递给 [WebRtcVideoView] 中的 SurfaceViewRenderer。
     */
    private fun connectInternalWebRtc() {
        disconnect()

        val port = serverPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22456
        Log.i(TAG, "[手机端] 发起连接车机: ${serverIp.trim()}:$port (模式: WebRTC 官方原生渲染)")

        receiver = PhoneWebRtcReceiver(
            context = this,
            carIp = serverIp.trim(),
            carPort = port,
            renderMode = RenderMode.WEBRTC_BUILTIN,
            onStatusChanged = { status ->
                runOnUiThread {
                    statusText = status
                    if (status.contains("连接出错") || status.contains("断开")) {
                        isConnected = false
                    }
                }
            },
            onVideoTrackReceived = { track ->
                runOnUiThread {
                    Log.i(TAG, "[手机端] 成功挂载 WebRTC 原生 VideoTrack: ${track.id()}")
                    remoteVideoTrack = track
                    isConnected = true
                    statusText = "已连接并渲染画面 (WebRTC原生)"
                }
            },
            onStatsUpdated = { f, b ->
                runOnUiThread {
                    fps = f
                    bitrateKbps = b
                }
            }
        ).apply {
            start()
        }
    }

    private fun disconnect() {
        Log.i(TAG, "[手机端] 断开与车机连接并释放当前解码器与 WebRTC 接收组件")
        shouldConnectPending = false
        receiver?.stop()
        receiver = null
        decoder?.stop()
        decoder = null
        remoteVideoTrack = null
        isConnected = false
        statusText = "未连接"
        fps = 0
        bitrateKbps = 0
    }

    override fun onDestroy() {
        beaconListener?.stop()
        beaconListener = null
        disconnect()
        super.onDestroy()
    }
}
