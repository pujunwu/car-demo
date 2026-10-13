package com.example.car.webrtc

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.car.webrtc.ui.WebRtcCarHomeScreen
import com.example.protocol.NetUtils

class WebRtcCarMainActivity : ComponentActivity() {

    private val tag = "WebRtcCarMainActivity"
    private var captureService: WebRtcScreenCaptureService? = null
    private var isBound = false

    private var serverIp by mutableStateOf("127.0.0.1")
    private var isStreaming by mutableStateOf(false)
    private var statusText by mutableStateOf("就绪")
    private var selectedDisplayId by mutableStateOf(android.view.Display.DEFAULT_DISPLAY)
    private var availableDisplays by mutableStateOf<List<DisplayInfo>>(emptyList())

    private var pendingResultCode: Int? = null
    private var pendingIntentData: Intent? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WebRtcScreenCaptureService.LocalBinder
            val instance = binder.getService()
            captureService = instance
            isBound = true

            instance.onStatusChanged = { status ->
                runOnUiThread { statusText = status }
            }

            // 关键修复：如果在 Service 连接绑定完成前申请了权限，在此处补偿启动推流
            val resultCode = pendingResultCode
            val intentData = pendingIntentData
            if (resultCode != null && intentData != null) {
                Log.i(tag, "Service 异步绑定成功，补偿启动 WebRTC 屏幕采集推流(Display ID: $selectedDisplayId)...")
                instance.startStreaming(resultCode, intentData, 22456, selectedDisplayId)
                isStreaming = true
                pendingResultCode = null
                pendingIntentData = null
            } else {
                isStreaming = instance.isRecording()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureService = null
            isBound = false
            isStreaming = false
            statusText = "服务已断开"
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intentData = result.data ?: return@registerForActivityResult
            Log.i(tag, "MediaProjection 屏幕录制权限已授予，准备启动 WebRTC 屏幕采集 (目标 Display ID: $selectedDisplayId)...")

            val serviceIntent = Intent(this, WebRtcScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            val service = captureService
            if (service != null) {
                service.onStatusChanged = { status ->
                    runOnUiThread { statusText = status }
                }
                service.startStreaming(
                    resultCode = result.resultCode,
                    data = intentData,
                    port = 22456,
                    targetDisplayId = selectedDisplayId
                )
                isStreaming = true
            } else {
                Log.i(tag, "Service 正在异步绑定中，暂存权限结果等待连接完成...")
                pendingResultCode = result.resultCode
                pendingIntentData = intentData
            }
        } else {
            Log.w(tag, "屏幕录制权限申请被拒绝")
            statusText = "权限申请被拒绝"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        com.example.webrtc.core.WebRtcContext.initialize(this)
        serverIp = NetUtils.getLocalIpAddress()
        availableDisplays = CarScreenCapturer.getAvailableDisplays(this)

        val intent = Intent(this, WebRtcScreenCaptureService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            WebRtcCarHomeScreen(
                serverIp = serverIp,
                serverPort = 22456,
                isStreaming = isStreaming,
                statusText = statusText,
                availableDisplays = availableDisplays,
                selectedDisplayId = selectedDisplayId,
                onSelectDisplay = { selectedDisplayId = it },
                onToggleStreaming = {
                    if (isStreaming) {
                        captureService?.stopStreaming()
                        isStreaming = false
                        statusText = "已停止"
                    } else {
                        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
                        val targetDisplay = displayManager?.getDisplay(selectedDisplayId)
                        val targetContext = if (targetDisplay != null) createDisplayContext(targetDisplay) else this@WebRtcCarMainActivity

                        val projectionManager = targetContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            val config = if (selectedDisplayId != android.view.Display.DEFAULT_DISPLAY) {
                                android.media.projection.MediaProjectionConfig.createConfigForUserChoice()
                            } else {
                                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                            }
                            projectionManager.createScreenCaptureIntent(config)
                        } else {
                            projectionManager.createScreenCaptureIntent()
                        }

                        val options = android.app.ActivityOptions.makeBasic()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && selectedDisplayId != android.view.Display.DEFAULT_DISPLAY) {
                            options.setLaunchDisplayId(selectedDisplayId)
                        }
                        val compatOptions = object : androidx.core.app.ActivityOptionsCompat() {
                            override fun toBundle(): Bundle? = options.toBundle()
                        }
                        screenCaptureLauncher.launch(intent, compatOptions)
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        serverIp = NetUtils.getLocalIpAddress()
        availableDisplays = CarScreenCapturer.getAvailableDisplays(this)
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
