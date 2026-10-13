package com.example.car.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import com.example.webrtc.core.WebRtcContext
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

data class DisplayInfo(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val isDefault: Boolean
)

/**
 * Screen capture manager for Car unit using WebRTC ScreenCapturerAndroid.
 */
class CarScreenCapturer(
    private val context: Context,
    private val factory: PeerConnectionFactory
) {
    companion object {
        private const val TAG = "CarScreenCapturer"
        private const val TRACK_ID = "car_screen_video_track"

        fun getAvailableDisplays(context: Context): List<DisplayInfo> {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            val displays = displayManager?.displays ?: emptyArray()
            val result = mutableListOf<DisplayInfo>()
            for (display in displays) {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                result.add(
                    DisplayInfo(
                        id = display.displayId,
                        name = display.name ?: "Display #${display.displayId}",
                        width = metrics.widthPixels,
                        height = metrics.heightPixels,
                        isDefault = (display.displayId == android.view.Display.DEFAULT_DISPLAY)
                    )
                )
            }
            return result
        }
    }

    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    var videoTrack: VideoTrack? = null
        private set

    fun startCapture(
        resultCode: Int,
        data: Intent,
        targetDisplayId: Int? = null,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 20
    ) {
        if (videoCapturer != null) return

        try {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            val targetDisplay = if (targetDisplayId != null) displayManager?.getDisplay(targetDisplayId) else null
            
            val realMetrics = android.util.DisplayMetrics()
            if (targetDisplay != null) {
                @Suppress("DEPRECATION")
                targetDisplay.getRealMetrics(realMetrics)
                Log.i(TAG, "[车机采集诊断] 获取指定屏幕 Display(ID=$targetDisplayId, Name=${targetDisplay.name}) 物理分辨率: ${realMetrics.widthPixels}x${realMetrics.heightPixels}")
            } else {
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    val windowMetrics = windowManager?.maximumWindowMetrics
                    if (windowMetrics != null) {
                        realMetrics.widthPixels = windowMetrics.bounds.width()
                        realMetrics.heightPixels = windowMetrics.bounds.height()
                    }
                }
                if (realMetrics.widthPixels <= 0 || realMetrics.heightPixels <= 0) {
                    if (windowManager != null) {
                        @Suppress("DEPRECATION")
                        windowManager.defaultDisplay.getRealMetrics(realMetrics)
                    } else {
                        realMetrics.widthPixels = context.resources.displayMetrics.widthPixels
                        realMetrics.heightPixels = context.resources.displayMetrics.heightPixels
                    }
                }
            }

            val rawW = if (realMetrics.widthPixels > 0) realMetrics.widthPixels else width
            val rawH = if (realMetrics.heightPixels > 0) realMetrics.heightPixels else height

            val maxDim = 1280
            val scale = if (Math.max(rawW, rawH) > maxDim) maxDim.toFloat() / Math.max(rawW, rawH) else 1.0f
            // 【关键参数：H.264 尺寸偶数对齐】
            // H.264 编码器采用 YUV420 采样，要求宏块尺寸必须是 2 的倍数（部分芯片甚至要求 16 的倍数）。
            // 此处强制做 ((val / 2) * 2) 偶数对齐，彻底防止底层 MediaCodec 抛出非法分辨率 CodecException 崩溃。
            val captureWidth = ((rawW * scale).toInt() / 2) * 2
            val captureHeight = ((rawH * scale).toInt() / 2) * 2

            Log.i(TAG, "[车机采集诊断] 屏幕原始尺寸探测: 目标屏ID=${targetDisplayId ?: "默认屏"}, 物理宽度=${rawW}px, 物理高度=${rawH}px, 录屏推流尺寸=${captureWidth}x${captureHeight}, 缩放比率=$scale")

            val capturer = CarDisplayCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "系统停止了 MediaProjection 屏幕录制")
                    stopCapture()
                }
            }, targetDisplayId)

            // 【关键组件：SurfaceTextureHelper 零拷贝纹理中转器】
            // 绑定 WebRTC 全局 EGL 上下文，在 GPU 显存内将 VirtualDisplay 的 Surface 渲染直接封装为 OES 纹理 VideoFrame，
            // 绝不经过 CPU 内存拷贝 (避免了 ByteBuffer 读取 YUV 单帧 15~25ms 的巨大延迟与掉帧瓶颈)。
            surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcScreenCapturerThread", WebRtcContext.rootEglBase.eglBaseContext)

            // =========================================================================
            // 【防掉帧核心：createVideoSource(isScreencast = false)】
            // 作用原理：如果传入 true，WebRTC 会启用专门针对静态 PPT/幻灯片的 ScreencastAdaptor 算法。
            //           该算法会检测静态画面并大幅降低帧率，或者在动态画面时主动抽帧丢弃！
            //           显式设为 false (实时视频模式)，告知 WebRTC 按照高速摄像头视频流无条件全量送编，
            //           杜绝 WebRTC 自作主张的主动抽帧丢帧行为，同时保证文字、UI 高频细节锐利无损。
            // =========================================================================
            videoSource = factory.createVideoSource(false)

            // =========================================================================
            // 【时机一：源头录屏采集帧率设置 (Capture Source Framerate)】
            // 调用位置：MediaProjection 录屏采集器启动阶段 (capturer.startCapture)
            // 作用原理：决定 Android VirtualDisplay / SurfaceFlinger 捕获屏幕画面的物理采样上限。
            //           如果源头只采 30 FPS，则无论后续网络多好、编码器设置多高，最大也只能达到 30 FPS；
            //           设置为 60 FPS 能使帧间隔从 33.3ms 缩减到 16.6ms，直接减少一半的触控跟手延迟。
            // =========================================================================
            val targetFps = 60
            val originalObserver = videoSource?.capturerObserver
            val diagnosticObserver = object : org.webrtc.CapturerObserver {
                private var intervalFrameCount = 0
                private var totalCapturedFrames = 0L
                private var lastLogTimeMs = System.currentTimeMillis()

                override fun onCapturerStarted(success: Boolean) {
                    originalObserver?.onCapturerStarted(success)
                    Log.i(TAG, "[车机采集诊断] ScreenCapturer 启动状态: success=$success")
                }

                override fun onCapturerStopped() {
                    originalObserver?.onCapturerStopped()
                    Log.i(TAG, "[车机采集诊断] ScreenCapturer 已停止")
                }

                override fun onFrameCaptured(frame: org.webrtc.VideoFrame?) {
                    intervalFrameCount++
                    totalCapturedFrames++
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastLogTimeMs >= 1000) {
                        val currentFps = (intervalFrameCount * 1000.0f / (nowMs - lastLogTimeMs)).toInt()
                        Log.i(TAG, "[车机采集诊断] 录屏捕获: 采集帧数=$totalCapturedFrames 帧 (区间新增 $intervalFrameCount 帧, 采集帧率 $currentFps FPS, 分辨率 ${frame?.buffer?.width}x${frame?.buffer?.height})")
                        intervalFrameCount = 0
                        lastLogTimeMs = nowMs
                    }
                    // 全量无损提交，不截留任何合法屏幕画面
                    originalObserver?.onFrameCaptured(frame)
                }
            }

            val helper = surfaceTextureHelper ?: return
            capturer.initialize(helper, context.applicationContext, diagnosticObserver)
            // 真正向底层 MediaProjection 注入期望采集分辨率与帧率
            capturer.startCapture(captureWidth, captureHeight, targetFps)

            videoTrack = factory.createVideoTrack(TRACK_ID, videoSource)
            videoTrack?.setEnabled(true)
            videoCapturer = capturer

            Log.i(TAG, "车机端屏幕 WebRTC 采集已启动: ${captureWidth}x${captureHeight} @ ${targetFps}fps (极速低延迟推流)")
        } catch (e: Exception) {
            Log.e(TAG, "启动 WebRTC 屏幕采集器失败", e)
            stopCapture()
        }
    }

    fun stopCapture() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            videoSource?.dispose()
            videoSource = null

            videoTrack?.dispose()
            videoTrack = null

            Log.i(TAG, "车机端屏幕 WebRTC 采集器已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止采集器时发生异常", e)
        }
    }
}
