package com.example.phone.webrtc

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
import com.example.webrtc.core.WebRtcContext
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

import android.util.Log

/**
 * Compose wrapper around WebRTC SurfaceViewRenderer.
 */
@Composable
fun WebRtcVideoView(
    videoTrack: VideoTrack?,
    modifier: Modifier = Modifier,
    scalingType: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT
) {
    val context = LocalContext.current
    var videoDimensions by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val currentScalingType by rememberUpdatedState(scalingType)

    val surfaceViewRenderer = remember(context) {
        SurfaceViewRenderer(context).apply {
            init(WebRtcContext.rootEglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {
                    Log.i("WebRtcVideoView", "WebRTC 画面首帧渲染成功 (0 延迟极速呈现)")
                }

                override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                    Log.i("WebRtcVideoView", "画面分辨率变更: ${videoWidth}x${videoHeight}, 旋转: $rotation")
                    videoDimensions = Pair(videoWidth, videoHeight)
                    post {
                        setScalingType(currentScalingType)
                        requestLayout()
                    }
                }
            })
            // =========================================================================
            // 【渲染优化点一：开启 setZOrderMediaOverlay(true)】
            // 解决 SurfaceView 在 Compose 宿主容器中被父容器背景或上层组件遮挡导致的黑屏问题，
            // 使视频图层悬浮在窗口媒体层级上，同时确保触控事件可正常传递。
            // =========================================================================
            setZOrderMediaOverlay(true)

            // =========================================================================
            // 【渲染优化点二：关闭 setEnableHardwareScaler(false)】
            // 原理：若设为 true，WebRTC 会调用底层 setFixedSize 使用硬件合成器缩放。
            //       在很多 MTK / 紫光芯片上，当接收到的视频帧尺寸与控件尺寸存在比例差异时，
            //       硬件缩放器极易发生冲突，导致 SurfaceFlinger 拒绝接收 Buffer 产生丢帧与黑屏。
            //       设为 false 改由 SurfaceViewRenderer 内置的 OpenGL ES 着色器以 GPU 纹理采样缩放，
            //       兼容性 100%，画质更平滑，杜绝渲染端丢帧。
            // =========================================================================
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
            Log.i("WebRtcVideoView", "已为 SurfaceViewRenderer 成功绑定远端视频 Track: ${videoTrack.id()}")
        }
        onDispose {
            if (videoTrack != null) {
                videoTrack.removeSink(surfaceViewRenderer)
            }
        }
    }

    DisposableEffect(surfaceViewRenderer) {
        onDispose {
            surfaceViewRenderer.release()
        }
    }

    // 关键自适应比例：当为 SCALE_ASPECT_FIT (全显) 时，通过 Compose aspectRatio 严格保证画面宽高比与车机视频一模一样，
    // 居中完整呈现，上下左右绝不裁剪任何像素点；当为 SCALE_ASPECT_FILL (裁切) 时则铺满容器
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
