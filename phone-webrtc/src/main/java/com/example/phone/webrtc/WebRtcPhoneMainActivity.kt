package com.example.phone.webrtc

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.phone.webrtc.ui.WebRtcPhoneHomeScreen
import com.example.protocol.NetUtils
import com.example.protocol.UdpDiscovery
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

class WebRtcPhoneMainActivity : ComponentActivity() {

    private val tag = "WebRtcPhoneMainActivity"
    private var receiver: PhoneWebRtcReceiver? = null
    private var beaconListener: UdpDiscovery.BeaconListener? = null

    private var serverIp by mutableStateOf("192.168.1.100")
    private var serverPort by mutableStateOf("22456")
    private var discoveredCarIp by mutableStateOf<String?>(null)
    private var isConnected by mutableStateOf(false)
    private var statusText by mutableStateOf("未连接")
    private var remoteVideoTrack by mutableStateOf<VideoTrack?>(null)
    private var fpsText by mutableStateOf("0 FPS")
    private var bitrateText by mutableStateOf("0.0 Mbps")

    private var scalingType by mutableStateOf(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
    private var isLandscape by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 默认设置为横屏与沉浸式全屏显示
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        toggleSystemBars(false)

        com.example.webrtc.core.WebRtcContext.initialize(this)

        val localIp = NetUtils.getLocalIpAddress()
        if (localIp != "127.0.0.1") {
            serverIp = localIp
        }

        beaconListener = UdpDiscovery.BeaconListener { ip, port ->
            runOnUiThread {
                val validPort = port.takeIf { it in 1..65535 } ?: 22456
                Log.i(tag, "UDP 自动搜索到车机设备: $ip:$validPort")
                discoveredCarIp = ip
                if (!isConnected) {
                    serverIp = ip
                    serverPort = validPort.toString()
                }
            }
        }.apply { start() }

        setContent {
            WebRtcPhoneHomeScreen(
                serverIp = serverIp,
                serverPort = serverPort,
                isConnected = isConnected,
                statusText = statusText,
                fpsText = fpsText,
                bitrateText = bitrateText,
                discoveredCarIp = discoveredCarIp,
                remoteVideoTrack = remoteVideoTrack,
                scalingType = scalingType,
                isLandscape = isLandscape,
                onIpChanged = { ip -> serverIp = ip },
                onPortChanged = { port -> serverPort = port },
                onScaleTypeChanged = { type ->
                    scalingType = type
                },
                onToggleOrientation = {
                    isLandscape = !isLandscape
                    requestedOrientation = if (isLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                    toggleSystemBars(!isLandscape)
                },
                onToggleConnection = { toggleConnection() }
            )
        }
    }

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

    private fun toggleConnection() {
        if (isConnected) {
            disconnect()
        } else {
            connect()
        }
    }

    private fun connect() {
        val port = serverPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22456
        Log.i(tag, "正在通过 WebRTC 连接车机: ${serverIp.trim()}:$port")

        receiver = PhoneWebRtcReceiver(
            context = this,
            carIp = serverIp.trim(),
            carPort = port,
            onStatusChanged = { status ->
                runOnUiThread { 
                    statusText = status
                    if (status.startsWith("连接失败") || status.startsWith("信令连接已断开")) {
                        isConnected = false
                    }
                }
            },
            onVideoTrackReceived = { track ->
                runOnUiThread {
                    remoteVideoTrack = track
                    isConnected = true
                }
            },
            onStatsUpdated = { fps, bitrateKbps ->
                runOnUiThread {
                    fpsText = "$fps FPS"
                    bitrateText = if (bitrateKbps >= 1000) {
                        String.format(java.util.Locale.US, "%.2f Mbps", bitrateKbps / 1000.0)
                    } else {
                        "$bitrateKbps Kbps"
                    }
                }
            }
        ).apply {
            start()
        }
    }

    private fun disconnect() {
        Log.i(tag, "正在断开 WebRTC 接收端...")
        receiver?.stop()
        receiver = null
        remoteVideoTrack = null
        fpsText = "0 FPS"
        bitrateText = "0.0 Mbps"
        isConnected = false
        statusText = "已断开"
    }

    override fun onDestroy() {
        beaconListener?.stop()
        beaconListener = null
        disconnect()
        super.onDestroy()
    }
}
