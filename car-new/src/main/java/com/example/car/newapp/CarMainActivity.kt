package com.example.car.newapp

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
import com.example.car.newapp.service.CarScreenCaptureService
import com.example.car.newapp.ui.CarHomeScreen
import com.example.car.newapp.protocol.NetUtils

/**
 * 车机端主界面 Activity (car-new)
 *
 * 核心交互：
 * 1. 申请通知权限与 MediaProjection 屏幕捕获权限。
 * 2. 绑定并调度 [CarScreenCaptureService] 前台服务。
 * 3. 驱动自定义采集硬编 + WebRTC 网络传输一体化管线。
 */
class CarMainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CarMainActivity"
        private const val DEFAULT_PORT = 22456
    }

    private var captureService: CarScreenCaptureService? = null
    private var isBound = false

    private var serverIp by mutableStateOf("127.0.0.1")
    private var isStreaming by mutableStateOf(false)
    private var statusText by mutableStateOf("就绪")

    private var pendingResultCode: Int? = null
    private var pendingIntentData: Intent? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CarScreenCaptureService.LocalBinder
            val instance = binder.getService()
            captureService = instance
            isBound = true

            instance.onStatusChanged = { status ->
                runOnUiThread { statusText = status }
            }

            // 处理可能在 Service 异步绑定完成前产生的授权结果
            val resultCode = pendingResultCode
            val intentData = pendingIntentData
            if (resultCode != null && intentData != null) {
                Log.i(TAG, "Service 异步连接完成，补偿启动录屏推流...")
                instance.startStreaming(resultCode, intentData, port = DEFAULT_PORT)
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
            statusText = "推流服务已断开"
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intentData = result.data ?: return@registerForActivityResult
            Log.i(TAG, "用户已授予 MediaProjection 屏幕录制权限")

            val serviceIntent = Intent(this, CarScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            val service = captureService
            if (service != null) {
                service.startStreaming(
                    resultCode = result.resultCode,
                    data = intentData,
                    port = DEFAULT_PORT
                )
                isStreaming = true
            } else {
                Log.i(TAG, "Service 尚在绑定中，暂存权限结果")
                pendingResultCode = result.resultCode
                pendingIntentData = intentData
            }
        } else {
            Log.w(TAG, "屏幕录制权限申请被拒绝")
            statusText = "权限申请被拒绝"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 13+ 申请通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        serverIp = NetUtils.getLocalIpAddress()

        // 绑定后台推流服务
        val intent = Intent(this, CarScreenCaptureService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            CarHomeScreen(
                serverIp = serverIp,
                serverPort = DEFAULT_PORT,
                isStreaming = isStreaming,
                statusText = statusText,
                onToggleStreaming = {
                    if (isStreaming) {
                        captureService?.stopStreaming()
                        isStreaming = false
                        statusText = "已停止推流"
                    } else {
                        // 申请录屏权限
                        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        val captureIntent = projectionManager.createScreenCaptureIntent()
                        screenCaptureLauncher.launch(captureIntent)
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        serverIp = NetUtils.getLocalIpAddress()
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
