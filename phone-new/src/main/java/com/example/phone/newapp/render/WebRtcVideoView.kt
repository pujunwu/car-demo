package com.example.phone.newapp.render

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.phone.newapp.webrtc.core.WebRtcContext
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * WebRTC 原生 SurfaceViewRenderer 控件的 Compose 封装
 *
 * 核心设计：
 * 1. 独立运行于 WebRTC 原生渲染管线，与自定义硬件解码完全解耦隔离。
 * 2. 绑定 WebRTC 官方 [SurfaceViewRenderer]，直接消费远端 [VideoTrack]。
 * 3. 自动监听首帧渲染 [RendererCommon.RendererEvents.onFirstFrameRendered] 与分辨率变更。
 * 4. 支持 SCALE_ASPECT_FIT (全显保持比例) 与 SCALE_ASPECT_FILL (全屏裁切铺满)。
 */
@Composable
fun WebRtcVideoView(
    videoTrack: VideoTrack?,
    modifier: Modifier = Modifier,
    scalingType: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT,
    onResolutionChanged: ((width: Int, height: Int) -> Unit)? = null
) {
    val context = LocalContext.current
    var videoDimensions by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val currentScalingType by rememberUpdatedState(scalingType)
    val currentOnResolutionChanged by rememberUpdatedState(onResolutionChanged)

    val surfaceViewRenderer = remember(context) {
        SurfaceViewRenderer(context).apply {
            init(WebRtcContext.rootEglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {
                    Log.i("WebRtcVideoView", "[WebRTC原生渲染] 画面首帧渲染成功 (SurfaceViewRenderer 0延迟极速呈现)")
                }

                override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                    Log.i("WebRtcVideoView", "[WebRTC原生渲染] 画面分辨率变更: ${videoWidth}x${videoHeight}, 旋转: $rotation")
                    videoDimensions = Pair(videoWidth, videoHeight)
                    currentOnResolutionChanged?.invoke(videoWidth, videoHeight)
                    post {
                        setScalingType(currentScalingType)
                        requestLayout()
                    }
                }
            })
            // 解决 SurfaceView 在 Compose 宿主容器中被父容器背景或上层组件遮挡问题
            setZOrderMediaOverlay(true)
            // 关闭硬件缩放器，改由 OpenGL ES 着色器以 GPU 纹理采样缩放，杜绝芯片硬件冲突
            setEnableHardwareScaler(false)
            setMirror(false)
            setScalingType(scalingType)
        }
    }

    LaunchedEffect(scalingType) {
        surfaceViewRenderer.setScalingType(scalingType)
        surfaceViewRenderer.requestLayout()
    }

    DisposableEffect(videoTrack) {
        if (videoTrack != null) {
            videoTrack.setEnabled(true)
            videoTrack.addSink(surfaceViewRenderer)
            Log.i("WebRtcVideoView", "[WebRTC原生渲染] 已向 SurfaceViewRenderer 绑定远端 VideoTrack: ${videoTrack.id()}")
        }
        onDispose {
            if (videoTrack != null) {
                videoTrack.removeSink(surfaceViewRenderer)
                Log.i("WebRtcVideoView", "[WebRTC原生渲染] 已解除 VideoTrack 绑定")
            }
        }
    }

    DisposableEffect(surfaceViewRenderer) {
        onDispose {
            Log.i("WebRtcVideoView", "[WebRTC原生渲染] 释放 SurfaceViewRenderer 渲染资源")
            surfaceViewRenderer.release()
        }
    }

    val dims = videoDimensions
    val videoModifier = if (dims != null && dims.first > 0 && dims.second > 0 && scalingType == RendererCommon.ScalingType.SCALE_ASPECT_FIT) {
        Modifier
            .fillMaxSize()
            .wrapContentSize(Alignment.Center)
            .aspectRatio(dims.first.toFloat() / dims.second.toFloat())
    } else {
        Modifier.fillMaxSize()
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { surfaceViewRenderer },
            modifier = videoModifier,
            update = { renderer ->
                renderer.setScalingType(scalingType)
                renderer.requestLayout()
            }
        )
    }
}
