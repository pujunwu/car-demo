package com.example.phone.newapp.ui

import android.view.Gravity
import android.view.SurfaceHolder
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.phone.newapp.render.AspectRatioSurfaceView
import com.example.phone.newapp.render.RenderMode
import com.example.phone.newapp.render.WebRtcVideoView
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

/**
 * 手机端投屏显示主界面 (phone-new 独立双渲染融合方案)
 *
 * 核心渲染能力：
 * 1. 【双独立渲染层】：
 *    - 自定义硬解模式 (RenderMode.CUSTOM)：底层全屏承载 [AspectRatioSurfaceView] + MediaCodec 直出渲染。
 *    - WebRTC 原生渲染模式 (RenderMode.WEBRTC_BUILTIN)：底层全屏承载 [WebRtcVideoView] (SurfaceViewRenderer)。
 * 2. 【独立切换】：两套渲染链路完全互不影响，支持一键热切换。
 * 3. 【顶层悬浮控制面板】：车机 IP/端口输入、UDP 自动探测一键填入、横竖屏切换及全显/裁切比例调整。
 * 4. 【实时性能监控】：动态显示视频分辨率、WebRTC 接收帧率 (FPS) 及接收码率 (kbps)。
 */
@Composable
fun PhoneHomeScreen(
    serverIp: String,
    serverPort: String,
    renderMode: RenderMode,
    isConnected: Boolean,
    statusText: String,
    errorMessage: String?,
    discoveredCarIp: String?,
    remoteVideoTrack: VideoTrack?,
    videoWidth: Int,
    videoHeight: Int,
    fps: Int,
    bitrateKbps: Int,
    scaleType: AspectRatioSurfaceView.ScaleType,
    isLandscape: Boolean,
    onIpChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onRenderModeChanged: (RenderMode) -> Unit,
    onScaleTypeChanged: (AspectRatioSurfaceView.ScaleType) -> Unit,
    onToggleOrientation: () -> Unit,
    onToggleConnection: () -> Unit,
    onSurfaceReady: (SurfaceHolder) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onVideoResolutionChanged: ((Int, Int) -> Unit)? = null
) {
    var showControls by remember { mutableStateOf(true) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    showControls = !showControls
                },
            contentAlignment = Alignment.Center
        ) {
            // Layer 1: 画面渲染层（根据当前选定模式独立挂载对应的渲染控件）
            when (renderMode) {
                RenderMode.CUSTOM -> {
                    // 自定义 AspectRatioSurfaceView 画面渲染层 (MediaCodec 硬件解码输出)
                    AndroidView(
                        factory = { context ->
                            val frameLayout = FrameLayout(context).apply {
                                setBackgroundColor(android.graphics.Color.BLACK)
                            }

                            val surfaceView = AspectRatioSurfaceView(context).apply {
                                setVideoSize(videoWidth, videoHeight)
                                setScaleType(scaleType)

                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        onSurfaceReady(holder)
                                    }
                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        onSurfaceDestroyed()
                                    }
                                })
                            }

                            val params = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                gravity = Gravity.CENTER
                            }

                            frameLayout.addView(surfaceView, params)
                            frameLayout
                        },
                        update = { frameLayout ->
                            val surfaceView = frameLayout.getChildAt(0) as? AspectRatioSurfaceView
                            surfaceView?.setVideoSize(videoWidth, videoHeight)
                            surfaceView?.setScaleType(scaleType)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                RenderMode.WEBRTC_BUILTIN -> {
                    // WebRTC 官方原生 SurfaceViewRenderer 控件渲染层
                    val webrtcScale = if (scaleType == AspectRatioSurfaceView.ScaleType.FIT) {
                        RendererCommon.ScalingType.SCALE_ASPECT_FIT
                    } else {
                        RendererCommon.ScalingType.SCALE_ASPECT_FILL
                    }
                    WebRtcVideoView(
                        videoTrack = remoteVideoTrack,
                        scalingType = webrtcScale,
                        modifier = Modifier.fillMaxSize(),
                        onResolutionChanged = onVideoResolutionChanged
                    )
                }
            }

            // Layer 2: 顶部浮动控制卡片
            AnimatedVisibility(
                visible = showControls || !isConnected,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        // 1. IP / 端口 / 连接操作栏
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = serverIp,
                                onValueChange = onIpChanged,
                                label = { Text("车机 IP 地址") },
                                enabled = !isConnected,
                                modifier = Modifier.weight(2f),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            OutlinedTextField(
                                value = serverPort,
                                onValueChange = onPortChanged,
                                label = { Text("端口") },
                                enabled = !isConnected,
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            Button(
                                onClick = onToggleConnection,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isConnected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.height(56.dp)
                            ) {
                                Text(if (isConnected) "断开" else "连接")
                            }
                        }

                        // 2. UDP 探测发现提示
                        if (!discoveredCarIp.isNullOrEmpty() && !isConnected) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "✨ UDP 已自动捕获车机设备: $discoveredCarIp",
                                    fontSize = 11.sp,
                                    color = Color(0xFF2E7D32),
                                    fontWeight = FontWeight.Bold
                                )
                                OutlinedButton(
                                    onClick = { onIpChanged(discoveredCarIp) },
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("一键填入", fontSize = 10.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // 3. 实时连接状态与指标
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .height(8.dp)
                                    .width(8.dp)
                                    .background(
                                        color = if (isConnected) Color(0xFF4CAF50) else Color(0xFFF44336),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                            )
                            Text(
                                text = if (isConnected) {
                                    "${videoWidth}x${videoHeight} | ${fps} FPS | ${bitrateKbps} kbps | 当前: ${renderMode.title}"
                                } else {
                                    "$statusText (就绪模式: ${renderMode.title})"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // 4. 双渲染模式切换栏 + 显示控制按钮组
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 左侧：渲染模式切换按钮组
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("模式: ", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                OutlinedButton(
                                    onClick = { onRenderModeChanged(RenderMode.CUSTOM) },
                                    modifier = Modifier.height(30.dp),
                                    colors = if (renderMode == RenderMode.CUSTOM) {
                                        ButtonDefaults.outlinedButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    } else {
                                        ButtonDefaults.outlinedButtonColors()
                                    }
                                ) {
                                    Text(
                                        "自定义硬解",
                                        fontSize = 10.sp,
                                        fontWeight = if (renderMode == RenderMode.CUSTOM) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                OutlinedButton(
                                    onClick = { onRenderModeChanged(RenderMode.WEBRTC_BUILTIN) },
                                    modifier = Modifier.height(30.dp),
                                    colors = if (renderMode == RenderMode.WEBRTC_BUILTIN) {
                                        ButtonDefaults.outlinedButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    } else {
                                        ButtonDefaults.outlinedButtonColors()
                                    }
                                ) {
                                    Text(
                                        "WebRTC原生",
                                        fontSize = 10.sp,
                                        fontWeight = if (renderMode == RenderMode.WEBRTC_BUILTIN) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }

                            // 右侧：横竖屏及缩放控制按钮组
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = onToggleOrientation,
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text(if (isLandscape) "竖屏" else "横屏", fontSize = 10.sp)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                OutlinedButton(
                                    onClick = { onScaleTypeChanged(AspectRatioSurfaceView.ScaleType.FIT) },
                                    modifier = Modifier.height(30.dp),
                                    colors = if (scaleType == AspectRatioSurfaceView.ScaleType.FIT)
                                        ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                    else ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text("全显", fontSize = 10.sp)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                OutlinedButton(
                                    onClick = { onScaleTypeChanged(AspectRatioSurfaceView.ScaleType.CROP) },
                                    modifier = Modifier.height(30.dp),
                                    colors = if (scaleType == AspectRatioSurfaceView.ScaleType.CROP)
                                        ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                    else ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text("裁切", fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Layer 3: 未连接状态提示卡片
            if (!isConnected) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                        .padding(20.dp)
                ) {
                    Text(
                        text = "📺 当前选择：${renderMode.title}渲染模式",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = renderMode.description,
                        color = Color(0xFF81D4FA),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (renderMode == RenderMode.CUSTOM) {
                            "提示：WebRTC 仅接收 RTP 包并解包，数据直接交由 Android 原生 MediaCodec 解码上屏"
                        } else {
                            "提示：WebRTC 官方 DefaultVideoDecoderFactory 解码，由官方 SurfaceViewRenderer 控件全流程渲染"
                        },
                        color = Color.LightGray,
                        fontSize = 11.sp
                    )

                    if (!errorMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage,
                            color = Color(0xFFFF5252),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
