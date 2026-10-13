package com.example.car.webrtc

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Custom WebRTC Screen Capturer supporting specified Display ID capture for Car IVI systems using MediaProjection.
 */
class CarDisplayCapturerAndroid(
    private val mediaProjectionPermissionResultData: Intent,
    private val mediaProjectionCallback: MediaProjection.Callback,
    private val targetDisplayId: Int? = null
) : VideoCapturer, VideoSink {

    companion object {
        private const val TAG = "CarDisplayCapturer"
    }

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var applicationContext: Context? = null
    private var capturerObserver: CapturerObserver? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var width: Int = 1280
    private var height: Int = 720
    private var framerate: Int = 60
    private var isDisposed = false

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        applicationContext: Context,
        capturerObserver: CapturerObserver
    ) {
        checkNotDisposed()
        this.surfaceTextureHelper = surfaceTextureHelper
        this.applicationContext = applicationContext
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        checkNotDisposed()
        this.width = width
        this.height = height
        this.framerate = framerate

        val context = applicationContext ?: return
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val targetDisplay = targetDisplayId?.let { displayManager?.getDisplay(it) }

        val displayContext = if (targetDisplay != null) {
            context.createDisplayContext(targetDisplay)
        } else {
            context
        }

        val projectionManager = displayContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(
            android.app.Activity.RESULT_OK,
            mediaProjectionPermissionResultData
        )

        val mp = mediaProjection ?: run {
            Log.e(TAG, "无法获取 MediaProjection 录屏凭证")
            capturerObserver?.onCapturerStarted(false)
            return
        }

        mp.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))

        // 针对部分 Android Automotive 定制车机系统，利用反射尝试绑定指定目标屏幕 ID
        if (targetDisplayId != null && targetDisplayId != Display.DEFAULT_DISPLAY) {
            try {
                val method = mp.javaClass.getMethod("setLaunchDisplayId", Int::class.javaPrimitiveType)
                method.invoke(mp, targetDisplayId)
                Log.i(TAG, "[指定屏 MediaProjection] 反射成功调用 setLaunchDisplayId($targetDisplayId)")
            } catch (_: Throwable) {}

            try {
                val method = mp.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                method.invoke(mp, targetDisplayId)
                Log.i(TAG, "[指定屏 MediaProjection] 反射成功调用 setDisplayId($targetDisplayId)")
            } catch (_: Throwable) {}
        }

        surfaceTextureHelper?.startListening(this)
        createVirtualDisplay(displayManager, targetDisplay)
        capturerObserver?.onCapturerStarted(true)
    }

    private fun createVirtualDisplay(displayManager: DisplayManager?, targetDisplay: Display?) {
        val helper = surfaceTextureHelper ?: return
        val mediaProjection = mediaProjection ?: return

        helper.setTextureSize(width, height)

        val dpi = if (targetDisplay != null) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            targetDisplay.getMetrics(metrics)
            if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEFAULT
        } else {
            DisplayMetrics.DENSITY_DEFAULT
        }

        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        val surface = Surface(helper.surfaceTexture)

        Log.i(
            TAG,
            "[MediaProjection 采集] 正在创建 VirtualDisplay: 目标DisplayID=${targetDisplayId ?: "默认屏"}, 名称=${targetDisplay?.name ?: "Main"}, 分辨率=${width}x${height}, DPI=$dpi"
        )

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "CarWebRtc_Display_${targetDisplayId ?: 0}",
            width,
            height,
            dpi,
            flags,
            surface,
            null,
            null
        )

        val vd = virtualDisplay
        if (vd != null && targetDisplay != null && targetDisplay.displayId != Display.DEFAULT_DISPLAY) {
            applyLayerStackToVirtualDisplay(vd, targetDisplay)
        }
    }

    override fun stopCapture() {
        checkNotDisposed()
        ThreadUtils.runOnMainThread {
            surfaceTextureHelper?.stopListening()
            capturerObserver?.onCapturerStopped()

            virtualDisplay?.release()
            virtualDisplay = null

            try {
                mediaProjection?.unregisterCallback(mediaProjectionCallback)
            } catch (_: Exception) {}

            mediaProjection?.stop()
            mediaProjection = null
        }
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        checkNotDisposed()
        ThreadUtils.runOnMainThread {
            this.width = width
            this.height = height
            this.framerate = framerate

            stopCapture()
            startCapture(width, height, framerate)
        }
    }

    override fun dispose() {
        isDisposed = true
    }

    override fun isScreencast(): Boolean = true

    override fun onFrame(frame: VideoFrame) {
        capturerObserver?.onFrameCaptured(frame)
    }

    private fun checkNotDisposed() {
        if (isDisposed) {
            throw IllegalStateException("CarDisplayCapturerAndroid has been disposed.")
        }
    }

    private fun applyLayerStackToVirtualDisplay(virtualDisplay: VirtualDisplay, targetDisplay: Display) {
        val targetLayerStack = getDisplayLayerStack(targetDisplay)
        val token = getVirtualDisplayToken(virtualDisplay)

        if (token == null) {
            Log.e(TAG, "[SurfaceFlinger 绑定] 无法获取 VirtualDisplay 的 IBinder DisplayToken")
            return
        }

        try {
            val scClass = Class.forName("android.view.SurfaceControl")
            var setSuccess = false
            try {
                val setDisplayLayerStackMethod = scClass.getMethod(
                    "setDisplayLayerStack",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType
                )
                setDisplayLayerStackMethod.invoke(null, token, targetLayerStack)
                setSuccess = true
                Log.i(TAG, "[SurfaceFlinger 绑定] 成功调用 SurfaceControl.setDisplayLayerStack(token, $targetLayerStack)")
            } catch (e: Throwable) {
                Log.w(TAG, "[SurfaceFlinger 绑定] 静态 SurfaceControl.setDisplayLayerStack 失败: ${e.message}")
            }

            if (!setSuccess) {
                try {
                    val transactionClass = Class.forName("android.view.SurfaceControl\$Transaction")
                    val transaction = transactionClass.getDeclaredConstructor().newInstance()
                    val setDisplayLayerStackMethod = transactionClass.getMethod(
                        "setDisplayLayerStack",
                        IBinder::class.java,
                        Int::class.javaPrimitiveType
                    )
                    setDisplayLayerStackMethod.invoke(transaction, token, targetLayerStack)
                    val applyMethod = transactionClass.getMethod("apply")
                    applyMethod.invoke(transaction)
                    Log.i(TAG, "[SurfaceFlinger 绑定] 成功通过 Transaction.setDisplayLayerStack(token, $targetLayerStack) 生效")
                } catch (e: Throwable) {
                    Log.e(TAG, "[SurfaceFlinger 绑定] Transaction API 失败: ${e.message}", e)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[SurfaceFlinger 绑定] 反射绑定 LayerStack 异常", e)
        }
    }

    private fun getDisplayLayerStack(display: Display): Int {
        try {
            val getLayerStackMethod = Display::class.java.getMethod("getLayerStack")
            val layerStack = getLayerStackMethod.invoke(display) as? Int
            if (layerStack != null && layerStack != -1) {
                Log.i(TAG, "[Display LayerStack] 获取到 Display ID ${display.displayId} 的 LayerStack = $layerStack")
                return layerStack
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[Display LayerStack] Display.getLayerStack 反射失败: ${e.message}")
        }

        try {
            val displayInfoClass = Class.forName("android.view.DisplayInfo")
            val displayInfo = displayInfoClass.getDeclaredConstructor().newInstance()
            val getDisplayInfoMethod = Display::class.java.getMethod("getDisplayInfo", displayInfoClass)
            if (getDisplayInfoMethod.invoke(display, displayInfo) == true) {
                val layerStackField = displayInfoClass.getDeclaredField("layerStack")
                layerStackField.isAccessible = true
                val layerStack = layerStackField.getInt(displayInfo)
                Log.i(TAG, "[Display LayerStack] 通过 DisplayInfo 获取到 LayerStack = $layerStack")
                return layerStack
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[Display LayerStack] DisplayInfo 反射失败: ${e.message}")
        }

        Log.i(TAG, "[Display LayerStack] 使用 DisplayID ${display.displayId} 作为 LayerStack 降级方案")
        return display.displayId
    }

    private fun getVirtualDisplayToken(virtualDisplay: VirtualDisplay): IBinder? {
        val vdDisplay = virtualDisplay.display ?: return null
        val displayId = vdDisplay.displayId

        try {
            val method = Display::class.java.getMethod("getDisplayToken")
            val token = method.invoke(vdDisplay) as? IBinder
            if (token != null) return token
        } catch (_: Throwable) {}

        try {
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val getInstanceMethod = dmgClass.getMethod("getInstance")
            val dmg = getInstanceMethod.invoke(null)
            val getDisplayTokenMethod = dmgClass.getMethod("getDisplayToken", Int::class.javaPrimitiveType)
            val token = getDisplayTokenMethod.invoke(dmg, displayId) as? IBinder
            if (token != null) return token
        } catch (_: Throwable) {}

        try {
            val scClass = Class.forName("android.view.SurfaceControl")
            val getInternalTokenMethod = scClass.getMethod("getInternalDisplayToken", Int::class.javaPrimitiveType)
            val token = getInternalTokenMethod.invoke(null, displayId) as? IBinder
            if (token != null) return token
        } catch (_: Throwable) {}

        return null
    }

    private object ThreadUtils {
        fun runOnMainThread(runnable: Runnable) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                runnable.run()
            } else {
                Handler(Looper.getMainLooper()).post(runnable)
            }
        }
    }
}
