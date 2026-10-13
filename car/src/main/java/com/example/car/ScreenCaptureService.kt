package com.example.car

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

/**
 * 车机端屏幕录制与 Socket 推流后台服务（Foreground Service）
 *
 * 核心功能：
 * 1. 提升为前台服务（适配 Android Q+ 的 FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION 规范）。
 * 2. 持有 [MediaProjection] 凭证，创建 [VirtualDisplay] 捕获车机屏幕。
 * 3. 关联 [H264Encoder] 输入 Surface，将录屏数据直接输入硬编。
 * 4. 驱动 [CarSocketServer] 将编码出的 H.264 数据包通过 TCP 广播至已连接的手机端。
 * 5. 全生命周期管理：对外提供与 Activity 绑定的 Binder 接口，集中控制启动与停止。
 */
class ScreenCaptureService : Service() {

    private val tag = "ScreenCaptureService"
    // 与外部 Activity 通信的本地 Binder
    private val binder = LocalBinder()

    // 车机 Socket TCP 服务端实例
    private var socketServer: CarSocketServer? = null
    // H.264 硬件编码器实例
    private var encoder: H264Encoder? = null
    // Android 系统屏幕投屏凭证
    private var mediaProjection: MediaProjection? = null
    // 虚拟屏幕实例，将系统渲染管道输出导向编码器
    private var virtualDisplay: VirtualDisplay? = null

    // 当前推流工作状态标记
    private var isRecording = false

    // 当前已连接的手机客户端总数
    @Volatile
    var currentClientCount = 0
        private set

    // 客户端在线连接数量发生变化时的观察者回调
    var onClientCountChanged: ((Int) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(currentClientCount)
        }

    // 实时编码状态（静态省电模式/运动模式、当前码率Kbps）更新回调
    var onEncodingStateChanged: ((isStatic: Boolean, currentBitrateKbps: Int) -> Unit)? = null

    // 录屏凭证系统回调监听器：当系统强制收回凭证时自动安全退出推流
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(tag, "[屏幕录制服务] 录屏凭证 (MediaProjection) 已被系统或用户主动停止")
            stopStreaming()
        }
    }

    /**
     * 用于向 CarMainActivity 暴露 Service 实例的 Binder
     */
    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * 启动前台服务通知（适配 Android 8.0+ 通知渠道以及 Android 10+ 录屏前台服务类型）
     */
    private fun startForegroundNotification() {
        val channelId = "CarScreenCastChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "录屏投屏服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("屏幕推流服务运行中")
            .setContentText("正在通过 Socket 动态帧率/码率实时推流...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        // Android 10 (Q) 及以上强制要求指定 FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION 类型
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1001, notification)
        }
        Log.i(tag, "[屏幕录制服务] 前台服务 (MediaProjection FGS) 已成功升级启动")
    }

    /**
     * 开始屏幕录制推流核心流程
     *
     * @param resultCode MediaProjection 权限请求结果代码
     * @param data MediaProjection 权限请求返回的 Intent 数据包
     * @param width 目标录屏画面宽度（像素），默认 1280
     * @param height 目标录屏画面高度（像素），默认 720
     * @param port Socket 服务端监听端口，默认 22345
     * @param enableDynamicAdaptive 是否开启静态画面的动态降码率/降帧率模式
     */
    fun startStreaming(
        resultCode: Int,
        data: Intent?,
        width: Int = 1280,
        height: Int = 720,
        port: Int = 22345,
        enableDynamicAdaptive: Boolean = true
    ) {
        if (isRecording) return

        Log.i(tag, "[屏幕录制服务] 正在启动屏幕推流流程，分辨率: ${width}x${height}，端口: $port，动态码率控制: $enableDynamicAdaptive")

        try {
            startForegroundNotification()
        } catch (e: Exception) {
            Log.e(tag, "[屏幕录制服务] 启动前台服务 (Foreground Service) 失败", e)
        }

        // 1. 获取 MediaProjection 录屏凭证
        if (resultCode == Activity.RESULT_OK && data != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        }

        if (mediaProjection == null) {
            Log.e(tag, "[屏幕录制服务] 获取 MediaProjection 录屏凭证失败，推流终止")
            stopForegroundInternal()
            return
        }
        Log.i(tag, "[屏幕录制服务] MediaProjection 录屏凭证获取成功")

        mediaProjection?.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))

        // 2. 初始化并启动 Socket 服务端
        socketServer?.stop()
        socketServer = CarSocketServer(port).apply {
            onClientCountChanged = { count ->
                currentClientCount = count
                Log.i(tag, "[屏幕录制服务] 已连接手机客户端数量变更: $count")
                this@ScreenCaptureService.onClientCountChanged?.invoke(count)
            }
            onRequestKeyFrame = {
                encoder?.requestKeyFrame()
            }
            start()
        }

        // 3. 初始化 H.264 硬件编码器，并将编码数据直通给 SocketServer 进行网络广播
        encoder = H264Encoder(
            width = width,
            height = height,
            maxBitrate = 6_000_000,
            minBitrate = 3_500_000,
            maxFrameRate = 30,
            minFrameRate = 10,
            enableDynamicAdaptive = enableDynamicAdaptive
        ) { type, timestamp, w, h, payload ->
            socketServer?.broadcastPacket(type, timestamp, w, h, payload)
        }.apply {
            onEncodingStateChanged = { isStatic, kbps ->
                this@ScreenCaptureService.onEncodingStateChanged?.invoke(isStatic, kbps)
            }
            start()
        }

        // 4. 获取车机屏幕 DPI 并创建虚拟显示屏 VirtualDisplay
        val densityDpi = getDisplayDensity()

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "CarScreenCapture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoder?.getInputSurface(),
            null,
            null
        )
        Log.i(tag, "[屏幕录制服务] 虚拟显示屏 (VirtualDisplay) 创建成功 (${width}x${height} @ ${densityDpi}dpi)，已连接编码器 Surface")

        isRecording = true
    }

    /**
     * 获取车机当前物理屏幕的像素密度 DPI
     */
    private fun getDisplayDensity(): Int {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        return metrics.densityDpi
    }

    /**
     * 退出前台服务状态
     */
    private fun stopForegroundInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    /**
     * 停止屏幕推流，释放 VirtualDisplay、MediaProjection、编码器与 Socket 服务端资源
     */
    fun stopStreaming() {
        if (!isRecording) return
        Log.i(tag, "[屏幕录制服务] 正在停止屏幕录制与推流服务...")

        // 释放虚拟屏幕
        virtualDisplay?.release()
        virtualDisplay = null

        // 释放录屏凭证
        try {
            mediaProjection?.unregisterCallback(mediaProjectionCallback)
        } catch (_: Exception) {}

        mediaProjection?.stop()
        mediaProjection = null

        // 停止编码器
        encoder?.stop()
        encoder = null

        // 停止 Socket 服务端
        socketServer?.stop()
        socketServer = null

        currentClientCount = 0
        onClientCountChanged?.invoke(0)

        // 降级退出前台通知
        stopForegroundInternal()

        isRecording = false
    }

    /**
     * 获取当前是否正在推流中
     */
    fun isRecording(): Boolean = isRecording

    /**
     * 获取底层的 SocketServer 实例
     */
    fun getSocketServer(): CarSocketServer? = socketServer

    /**
     * 获取底层的 H264Encoder 实例
     */
    fun getEncoder(): H264Encoder? = encoder

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
}
