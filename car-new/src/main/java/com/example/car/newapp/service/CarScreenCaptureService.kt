package com.example.car.newapp.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.car.newapp.capture.H264Encoder
import com.example.car.newapp.webrtc.CarWebRtcSender
import com.example.car.newapp.protocol.NetUtils
import com.example.car.newapp.protocol.UdpDiscovery

/**
 * 车机端前台屏幕捕获与 WebRTC 推流服务 (CarScreenCaptureService)
 *
 * 方案结合核心实现：
 * 1. 【自定义采集与编码】：
 *    - 利用 [MediaProjection] 凭证创建 [VirtualDisplay]。
 *    - 将底层渲染通道直连自定义 [H264Encoder] (Android MediaCodec 硬件加速) 的 Input Surface。
 *    - 保持 0 拷贝性能并输出标准的 H.264 Annex-B 关键帧与预测帧。
 * 2. 【WebRTC 网络传输】：
 *    - 初始化 [CarWebRtcSender]，将硬编完成的 H.264 视频帧无缝导入 WebRTC 管道进行网络传输。
 *    - 通过 [UdpDiscovery.BeaconBroadcaster] 在局域网内定时广播车机 IP 与信令端口，供手机端自动发现。
 *    - 接收 WebRTC 拥塞控制反馈动态调节 [H264Encoder] 码率。
 */
class CarScreenCaptureService : Service() {

    companion object {
        private const val TAG = "CarScreenCaptureService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "CarNewScreenCastChannel"
    }

    private val binder = LocalBinder()

    // 自定义 MediaCodec 硬件编码器
    private var encoder: H264Encoder? = null
    // WebRTC 传输协调器
    private var webrtcSender: CarWebRtcSender? = null
    // UDP 自动组播信标广播器
    private var beaconBroadcaster: UdpDiscovery.BeaconBroadcaster? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var isRecording = false

    var onStatusChanged: ((String) -> Unit)? = null

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "[服务状态] 录屏凭证 (MediaProjection) 已停止")
            stopStreaming()
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): CarScreenCaptureService = this@CarScreenCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "车机融合录屏推流服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("车机端投屏推流中 (融合方案)")
            .setContentText("自定义硬编采集 + WebRTC 网络低延迟传输运行中...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 启动屏幕采集与 WebRTC 推流
     *
     * @param resultCode MediaProjection 权限结果码
     * @param data MediaProjection 权限 Intent 数据
     * @param width 录制宽度 (默认 1280)
     * @param height 录制高度 (默认 720)
     * @param port WebRTC 信令端口 (默认 22456)
     */
    fun startStreaming(
        resultCode: Int,
        data: Intent,
        width: Int = 1280,
        height: Int = 720,
        port: Int = 22456
    ) {
        if (isRecording) return

        // 0. 获取车机物理屏幕真实尺寸与 DPI，优先采用原生分辨率消灭模糊
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val streamWidth = if (width > 0 && width != 1280) width else if (metrics.widthPixels > 0) metrics.widthPixels and 1.inv() else 1920
        val streamHeight = if (height > 0 && height != 720) height else if (metrics.heightPixels > 0) metrics.heightPixels and 1.inv() else 1080
        val densityDpi = if (metrics.densityDpi > 0) metrics.densityDpi else getDisplayDensity()

        val localIp = NetUtils.getLocalIpAddress()
        Log.i(TAG, "启动融合推流服务: $streamWidth x $streamHeight @ ${densityDpi}dpi, 码率 12Mbps, 端口 $port, 车机 IP: $localIp")
        startForegroundNotification()

        // 1. 获取 MediaProjection 凭证
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            Log.e(TAG, "获取 MediaProjection 失败，推流终止")
            stopForegroundInternal()
            return
        }
        mediaProjection?.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))

        // 2. 初始化 WebRTC 媒体传输端
        webrtcSender = CarWebRtcSender(
            context = this,
            signalingPort = port,
            videoWidth = streamWidth,
            videoHeight = streamHeight,
            onStatusChanged = { status ->
                onStatusChanged?.invoke(status)
            },
            onBitrateAdjustRequested = { newBitrateBps ->
                // WebRTC GCC 拥塞控制反馈动态同步至底层硬件编码器
                encoder?.updateBitrate(newBitrateBps)
            }
        ).apply {
            onRequestKeyFrame = {
                encoder?.requestKeyFrame()
            }
            start()
        }

        // 3. 初始化自定义 H.264 硬件编码器，并将输出直通给 webrtcSender (12 Mbps 高清码率)
        encoder = H264Encoder(
            width = streamWidth,
            height = streamHeight,
            initialBitrate = 12_000_000,
            frameRate = 30,
            iFrameInterval = 1
        ) { isKeyFrame, timestampNs, w, h, encodedBytes ->
            // 将硬件编码输出的 H.264 帧递交给 WebRTC 发送
            webrtcSender?.sendEncodedFrame(isKeyFrame, timestampNs, w, h, encodedBytes)
        }.apply { start() }

        // 4. 创建 VirtualDisplay，将屏幕直接投射到硬件编码器的 Input Surface
        // 关键配置：仅使用 AUTO_MIRROR，杜绝 PUBLIC 导致生成独立空黑屏
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "CarNewScreenCapture",
            streamWidth,
            streamHeight,
            densityDpi,
            flags,
            encoder?.getInputSurface(),
            null,
            null
        )
        Log.i(TAG, "VirtualDisplay 镜像创建成功 (${streamWidth}x${streamHeight} @ ${densityDpi}dpi)，直连 MediaCodec Input Surface")

        // 5. 启动 UDP 广播，通知局域网手机端车机 IP 与信令端口
        beaconBroadcaster = UdpDiscovery.BeaconBroadcaster(carIp = localIp, carPort = port)
        beaconBroadcaster?.start()

        isRecording = true
        onStatusChanged?.invoke("正在推流中 (WebRTC 端口 $port)")
    }

    private fun getDisplayDensity(): Int {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        return metrics.densityDpi
    }

    private fun stopForegroundInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    /**
     * 停止推流并释放资源
     */
    fun stopStreaming() {
        if (!isRecording) return
        Log.i(TAG, "停止车机融合推流服务...")

        beaconBroadcaster?.stop()
        beaconBroadcaster = null

        virtualDisplay?.release()
        virtualDisplay = null

        try {
            mediaProjection?.unregisterCallback(mediaProjectionCallback)
        } catch (_: Exception) {}
        mediaProjection?.stop()
        mediaProjection = null

        encoder?.stop()
        encoder = null

        webrtcSender?.stop()
        webrtcSender = null

        stopForegroundInternal()
        isRecording = false
        onStatusChanged?.invoke("推流已停止")
    }

    fun isRecording(): Boolean = isRecording

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
}
