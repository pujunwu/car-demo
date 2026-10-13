package com.example.car.webrtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.protocol.NetUtils
import com.example.protocol.UdpDiscovery

/**
 * Foreground Service for Car Screen Capture WebRTC Streaming.
 */
class WebRtcScreenCaptureService : Service() {

    private val tag = "WebRtcScreenCaptureService"
    private val binder = LocalBinder()

    private var streamer: CarWebRtcStreamer? = null
    private var beaconBroadcaster: UdpDiscovery.BeaconBroadcaster? = null
    private var isRecording = false

    var onStatusChanged: ((String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): WebRtcScreenCaptureService = this@WebRtcScreenCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startForegroundNotification() {
        val channelId = "CarWebRtcCastChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "WebRTC 屏幕共享服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("WebRTC 屏幕共享运行中")
            .setContentText("车机端正在通过 WebRTC 实时推流...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1001, notification)
        }
    }

    fun startStreaming(
        resultCode: Int,
        data: Intent,
        port: Int = 22456,
        targetDisplayId: Int? = null
    ) {
        if (isRecording) return

        startForegroundNotification()

        val carIp = NetUtils.getLocalIpAddress()
        Log.i(tag, "正在启动 WebRTC 屏幕采集服务，车机 IP: $carIp, 端口: $port, 目标 Display ID: ${targetDisplayId ?: "默认屏"}")

        beaconBroadcaster = UdpDiscovery.BeaconBroadcaster(carIp = carIp, carPort = port)
        beaconBroadcaster?.start()

        streamer = CarWebRtcStreamer(
            context = this,
            signalingPort = port,
            onStatusChanged = { status ->
                onStatusChanged?.invoke(status)
            }
        ).apply {
            start(resultCode, data, targetDisplayId)
        }

        isRecording = true
    }

    private fun stopForegroundInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    fun stopStreaming() {
        if (!isRecording) return
        Log.i(tag, "正在停止 WebRTC 屏幕采集服务")

        beaconBroadcaster?.stop()
        beaconBroadcaster = null

        streamer?.stop()
        streamer = null

        stopForegroundInternal()
        isRecording = false
        onStatusChanged?.invoke("屏幕推流已停止")
    }

    fun isRecording(): Boolean = isRecording

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
}
