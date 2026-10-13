package com.example.car

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.car.ui.CarHomeScreen
import com.example.protocol.NetUtils

/**
 * 车机端主界面 Activity
 *
 * 核心职责：
 * 1. 启动并绑定 [ScreenCaptureService] 前台服务。
 * 2. 处理 Android 系统屏幕录制权限申请（[MediaProjectionManager]）。
 * 3. 获取屏幕物理分辨率并自动对齐（对齐到 16 的倍数，防止硬件编码器绿边/花屏）。
 * 4. 实时响应与展示车机 IP、监听端口、已连接手机数、当前编解码动态帧率与码率状态。
 */
class CarMainActivity : ComponentActivity() {

    private val tag = "CarMainActivity"
    // 屏幕录制推流服务引用
    private var captureService: ScreenCaptureService? = null
    // 与 Service 的绑定状态
    private var isBound = false

    // 车机本地局域网 IP 地址（通过 NetUtils 动态解析）
    private var serverIp by mutableStateOf("127.0.0.1")
    // 是否正在推流
    private var isStreaming by mutableStateOf(false)
    // 当前已连接的手机端数量
    private var clientCount by mutableIntStateOf(0)
    // 用户选择的推流分辨率选项（"720p" 或 "1080p"）
    private var selectedResolution by mutableStateOf("1080p")

    // 智能动态帧率与码率开关（画面静止时自动降帧降码率）
    private var enableDynamicAdaptive by mutableStateOf(true)
    // 画面是否处于静止状态
    private var isStaticMode by mutableStateOf(false)
    // 当前动态编码码率（Kbps）
    private var currentBitrateKbps by mutableIntStateOf(3500)

    // 前台服务连接回调：建立 Binder 连接并注册状态监听
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScreenCaptureService.LocalBinder
            val instance = binder.getService()
            captureService = instance
            isBound = true

            isStreaming = instance.isRecording()
            clientCount = instance.currentClientCount

            // 监听客户端连接数量变化
            instance.onClientCountChanged = { count ->
                Log.i(tag, "[车机界面] 收到客户端连接数更新: $count")
                runOnUiThread { clientCount = count }
            }

            // 监听编码器动态状态（静止/运动模式、当前码率）变化
            instance.onEncodingStateChanged = { isStatic, kbps ->
                runOnUiThread {
                    isStaticMode = isStatic
                    currentBitrateKbps = kbps
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(tag, "[车机界面] 屏幕录制推流服务已断开绑定 (onServiceDisconnected)")
            captureService = null
            isBound = false
            isStreaming = false
            clientCount = 0
        }
    }

    /**
     * 计算并获取规整化后的录屏分辨率
     * 关键点：H.264 硬件编码器（如高通/联发科/展锐车载芯片）大多严格要求视频宽和高必须按 16 的倍数对齐，
     * 若未按 16 字节对齐，容易引发画面右侧或底部出现绿色条纹（绿边）或硬件解码花屏。
     * @return 宽与高（Pair<Int, Int>）
     */
    private fun getNativeCaptureResolution(): Pair<Int, Int> {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = android.util.DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }

        var w = metrics.widthPixels
        var h = metrics.heightPixels

        // 默认兜底分辨率
        if (w <= 0 || h <= 0) {
            return if (selectedResolution == "1080p") 1920 to 1080 else 1280 to 720
        }

        // 按照用户所选清晰度进行等比缩放并按 16 字节对其
        if (selectedResolution == "720p" && h > 720) {
            val scale = 720.0f / h.toFloat()
            w = ((w * scale).toInt() / 16) * 16
            h = 720
        } else {
            w = (w / 16) * 16
            h = (h / 16) * 16
        }

        return w to h
    }

    // 系统屏幕录制权限申请启动器（通过 Activity Result 契约调用）
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intentData = result.data ?: return@registerForActivityResult
            val (width, height) = getNativeCaptureResolution()
            Log.i(tag, "[车机界面] 屏幕录制授权成功！目标分辨率: ${width}x${height}，准备启动推流服务...")

            // 启动录屏前台服务
            val serviceIntent = Intent(this, ScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            // 监听客户端连接数量变化并刷新 UI
            captureService?.onClientCountChanged = { count ->
                Log.i(tag, "[车机界面] 收到客户端连接数更新: $count")
                runOnUiThread { clientCount = count }
            }

            // 监听编码器动态状态（静止/运动模式、当前码率）变化并刷新 UI
            captureService?.onEncodingStateChanged = { isStatic, kbps ->
                runOnUiThread {
                    isStaticMode = isStatic
                    currentBitrateKbps = kbps
                }
            }

            // 驱动底层 Service 启动录屏、开启编码器并启动端口为 22345 的 Socket 监听
            captureService?.startStreaming(
                resultCode = result.resultCode,
                data = intentData,
                width = width,
                height = height,
                port = 22345,
                enableDynamicAdaptive = enableDynamicAdaptive
            )

            isStreaming = true
        } else {
            Log.w(tag, "[车机界面] 用户拒绝或取消了屏幕录制权限申请")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 解析车机当前所处的局域网有效 IP 地址
        serverIp = NetUtils.getLocalIpAddress()
        Log.i(tag, "[车机界面] 界面已启动，本机本地 IP 地址: $serverIp")

        // 绑定录屏前台服务
        val intent = Intent(this, ScreenCaptureService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            // 获取实际监听端口（若服务已启动则使用实际绑定端口，默认 22345）
            val displayPort = captureService?.getSocketServer()?.actualPort?.takeIf { it in 1..65535 } ?: 22345
            CarHomeScreen(
                serverIp = serverIp,
                serverPort = displayPort,
                isStreaming = isStreaming,
                clientCount = clientCount,
                selectedResolution = selectedResolution,
                enableDynamicAdaptive = enableDynamicAdaptive,
                isStaticMode = isStaticMode,
                currentBitrateKbps = currentBitrateKbps,
                onResolutionSelected = { res ->
                    selectedResolution = res
                    Log.i(tag, "[车机界面] 用户切换分辨率选项: $res")
                },
                onToggleDynamicAdaptive = { enabled ->
                    enableDynamicAdaptive = enabled
                    Log.i(tag, "[车机界面] 用户切换动态自适应码率开关: $enabled")
                },
                onToggleStreaming = {
                    if (isStreaming) {
                        Log.i(tag, "[车机界面] 用户点击 [停止推流]")
                        captureService?.stopStreaming()
                        isStreaming = false
                        isStaticMode = false
                        clientCount = 0
                    } else {
                        Log.i(tag, "[车机界面] 用户点击 [开始推流]，发起屏幕捕获授权申请...")
                        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        // Android 14+ 要求指定 MediaProjectionConfig 配置
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            projectionManager.createScreenCaptureIntent(
                                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                            )
                        } else {
                            projectionManager.createScreenCaptureIntent()
                        }
                        screenCaptureLauncher.launch(intent)
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 界面恢复可见时重新检测并刷新 IP
        serverIp = NetUtils.getLocalIpAddress()
    }

    override fun onDestroy() {
        // 解绑推流服务
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
