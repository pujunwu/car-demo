package com.example.phone

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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.phone.ui.PhoneHomeScreen
import com.example.protocol.NetUtils
import com.example.protocol.UdpDiscovery

/**
 * 手机端主界面 Activity
 *
 * 核心功能：
 * 1. 启动 UDP [BeaconListener]，自动侦听并捕获同一局域网内的车机 IP 与端口，支持一键填入。
 * 2. 调度 [PhoneSocketClient] 建立 TCP 长连接接收 H.264 码流。
 * 3. 关联 [H264Decoder]，将码流实时送显至 [AspectRatioSurfaceView]。
 * 4. 支持横竖屏动态切换、沉浸式全屏隐藏系统栏、画面等比适应与裁切模式切换。
 */
class PhoneMainActivity : ComponentActivity() {

    private val tag = "PhoneMainActivity"
    // TCP Socket 客户端实例
    private var socketClient: PhoneSocketClient? = null
    // H.264 硬件解码器实例
    private var decoder: H264Decoder? = null
    // 当前可用的 SurfaceHolder 句柄
    private var currentSurfaceHolder: SurfaceHolder? = null
    // UDP 广播监听器（自动探测局域网内的车机端）
    private var beaconListener: UdpDiscovery.BeaconListener? = null
    // 标记在 Surface 就绪前是否已经有挂起的连接请求
    private var shouldConnectPending = false

    // 车机端 IP 地址（默认尝试自动发现）
    private var serverIp by mutableStateOf("192.168.1.100")
    // 车机端 Socket 端口（默认为 22345）
    private var serverPort by mutableStateOf("22345")
    // 局域网组播自动捕获到的车机 IP
    private var discoveredCarIp by mutableStateOf<String?>(null)
    // 当前 Socket 客户端连接状态
    private var status by mutableStateOf(PhoneSocketClient.Status.DISCONNECTED)
    // 连接异常时的错误详情
    private var errorMessage by mutableStateOf<String?>(null)

    // 当前接收到的视频流分辨率与性能指标
    private var videoWidth by mutableIntStateOf(1280)
    private var videoHeight by mutableIntStateOf(720)
    private var fps by mutableIntStateOf(0)
    private var bitrateKbps by mutableLongStateOf(0L)

    // 画面拉伸缩放模式（默认 FIT 全显保比例）
    private var scaleType by mutableStateOf(AspectRatioSurfaceView.ScaleType.FIT)
    // 横竖屏状态（默认竖屏）
    private var isLandscape by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 适配异形屏/刘海屏延伸到耳朵区，保证全屏投屏视觉沉浸
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // 投屏过程中保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 获取本机局域网 IP
        val localIp = NetUtils.getLocalIpAddress()
        if (localIp != "127.0.0.1") {
            serverIp = localIp
        }

        // 启动 UDP 监听器，接收车机端定时广播的心跳信标（Beacon），实现免输入自动捕获车机 IP
        beaconListener = UdpDiscovery.BeaconListener { ip, port ->
            runOnUiThread {
                val validPort = port.takeIf { it in 1..65535 } ?: 22345
                Log.i(tag, "[手机界面] UDP 自动广播捕获到车机设备 -> IP: $ip, 端口: $validPort")
                discoveredCarIp = ip
                // 仅在未连接或连接出错状态下自动填入发现的车机信息
                if (status == PhoneSocketClient.Status.DISCONNECTED || status == PhoneSocketClient.Status.ERROR) {
                    serverIp = ip
                    serverPort = validPort.toString()
                }
            }
        }.apply { start() }

        setContent {
            PhoneHomeScreen(
                serverIp = serverIp,
                serverPort = serverPort,
                status = status,
                errorMessage = errorMessage,
                discoveredCarIp = discoveredCarIp,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                fps = fps,
                bitrateKbps = bitrateKbps,
                scaleType = scaleType,
                isLandscape = isLandscape,
                onIpChanged = { ip -> serverIp = ip },
                onPortChanged = { port -> serverPort = port },
                onScaleTypeChanged = { type ->
                    scaleType = type
                    Log.i(tag, "[手机界面] 用户切换画面缩放模式: $type")
                },
                onToggleOrientation = {
                    isLandscape = !isLandscape
                    Log.i(tag, "[手机界面] 用户切换横竖屏模式: 横屏=$isLandscape")
                    requestedOrientation = if (isLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                    toggleSystemBars(!isLandscape)
                },
                onToggleConnection = { toggleConnection() },
                onSurfaceReady = { holder ->
                    Log.i(tag, "[手机界面] 渲染 Surface 准备就绪")
                    currentSurfaceHolder = holder
                    decoder?.updateSurface(holder.surface)
                    // 若此前用户已点击连接但 Surface 尚未创建，在此自动激活连接
                    if (shouldConnectPending) {
                        shouldConnectPending = false
                        connectInternal(holder)
                    }
                },
                onSurfaceDestroyed = {
                    Log.i(tag, "[手机界面] 渲染 Surface 已销毁")
                    currentSurfaceHolder = null
                }
            )
        }
    }

    /**
     * 隐藏或显示系统状态栏和虚拟导航栏（横屏全屏时隐藏）
     * @param show true 显示，false 隐藏
     */
    private fun toggleSystemBars(show: Boolean) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (show) {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * 切换连接状态：已连接则断开，已断开则发起连接
     */
    private fun toggleConnection() {
        if (status == PhoneSocketClient.Status.CONNECTED || status == PhoneSocketClient.Status.CONNECTING) {
            Log.i(tag, "[手机界面] 用户点击 [断开连接]")
            disconnect()
        } else {
            Log.i(tag, "[手机界面] 用户点击 [连接车机]")
            connect()
        }
    }

    /**
     * 发起连接校验：若 Surface 尚未就绪则挂起，待 Surface 就绪后自动触发
     */
    private fun connect() {
        val holder = currentSurfaceHolder
        if (holder == null) {
            Log.w(tag, "[手机界面] 渲染 Surface 尚未准备就绪，挂起连接请求直至 SurfaceReady")
            shouldConnectPending = true
            return
        }
        connectInternal(holder)
    }

    /**
     * 初始化解码器并连接车机端 Socket 服务的核心流程
     * @param holder 渲染视窗句柄
     */
    private fun connectInternal(holder: SurfaceHolder) {
        val port = serverPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22345
        Log.i(tag, "[手机界面] 正在发起连接到车机: ${serverIp.trim()}:$port")

        // 1. 创建硬件解码器，将解码输出直接渲染到当前 Surface
        decoder = H264Decoder(holder.surface) { w, h ->
            runOnUiThread {
                videoWidth = w
                videoHeight = h
            }
        }

        // 2. 创建并启动 Socket 客户端长连接
        socketClient = PhoneSocketClient(
            host = serverIp.trim(),
            port = port,
            onPacketReceived = { packet ->
                // 将接收到的网络数据包直通交由解码器解码
                decoder?.decodeFrame(packet)
            },
            onStatusChanged = { newStatus, err ->
                Log.i(tag, "[手机界面] 收到连接状态更新: $newStatus${if (err != null) " (原因: $err)" else ""}")
                runOnUiThread {
                    status = newStatus
                    errorMessage = err
                    if (newStatus == PhoneSocketClient.Status.DISCONNECTED) {
                        fps = 0
                        bitrateKbps = 0L
                    }
                }
            }
        ).apply {
            onStatsUpdated = { f, b ->
                runOnUiThread {
                    fps = f
                    bitrateKbps = b
                }
            }
            connect()
        }
    }

    /**
     * 断开连接并释放解码器与客户端资源
     */
    private fun disconnect() {
        Log.i(tag, "[手机界面] 正在断开与车机端的 Socket 连接并释放解码器资源")
        shouldConnectPending = false
        socketClient?.disconnect()
        socketClient = null
        decoder?.stop()
        decoder = null
        status = PhoneSocketClient.Status.DISCONNECTED
    }

    override fun onDestroy() {
        // 停止 UDP 监听器
        beaconListener?.stop()
        beaconListener = null
        // 断开 Socket 连接
        disconnect()
        super.onDestroy()
    }
}
