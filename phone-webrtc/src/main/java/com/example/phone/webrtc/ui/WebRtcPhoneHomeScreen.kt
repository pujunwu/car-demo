package com.example.phone.webrtc.ui

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
import com.example.phone.webrtc.WebRtcVideoView
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

@Composable
fun WebRtcPhoneHomeScreen(
    serverIp: String,
    serverPort: String,
    isConnected: Boolean,
    statusText: String,
    fpsText: String = "0 FPS",
    bitrateText: String = "0.0 Mbps",
    discoveredCarIp: String?,
    remoteVideoTrack: VideoTrack?,
    scalingType: RendererCommon.ScalingType,
    isLandscape: Boolean,
    onIpChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onScaleTypeChanged: (RendererCommon.ScalingType) -> Unit,
    onToggleOrientation: () -> Unit,
    onToggleConnection: () -> Unit
) {
    var showControls by remember { mutableStateOf(true) }

    androidx.compose.runtime.LaunchedEffect(isConnected) {
        if (isConnected) {
            // 连接成功 2.5 秒后自动收起控制面板，避免遮挡车机顶部画面内容；轻触屏幕任意空白区域可随时显示/隐藏
            kotlinx.coroutines.delay(2500)
            showControls = false
        } else {
            showControls = true
        }
    }

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
            WebRtcVideoView(
                videoTrack = remoteVideoTrack,
                scalingType = scalingType,
                modifier = Modifier.fillMaxSize()
            )

            // 实时帧率与码率 HUD 悬浮信息面板
            if (isConnected) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 16.dp, end = 16.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.70f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .height(8.dp)
                                .width(8.dp)
                                .background(
                                    color = Color(0xFF00E676),
                                    shape = RoundedCornerShape(4.dp)
                                )
                        )
                        Text(
                            text = "⚡ $fpsText  |  $bitrateText",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

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
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.90f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
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
                                    containerColor = if (isConnected)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.height(56.dp)
                            ) {
                                Text(text = if (isConnected) "断开" else "连接")
                            }
                        }

                        if (!discoveredCarIp.isNullOrEmpty() && !isConnected) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "✨ UDP 自动搜索到车机: $discoveredCarIp",
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    text = statusText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row {
                                OutlinedButton(
                                    onClick = onToggleOrientation,
                                    modifier = Modifier.height(32.dp),
                                    colors = ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text(if (isLandscape) "竖屏" else "横屏", fontSize = 10.sp)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                OutlinedButton(
                                    onClick = { onScaleTypeChanged(RendererCommon.ScalingType.SCALE_ASPECT_FIT) },
                                    modifier = Modifier.height(32.dp),
                                    colors = if (scalingType == RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                                        ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                    else ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text("全显", fontSize = 10.sp)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                OutlinedButton(
                                    onClick = { onScaleTypeChanged(RendererCommon.ScalingType.SCALE_ASPECT_FILL) },
                                    modifier = Modifier.height(32.dp),
                                    colors = if (scalingType == RendererCommon.ScalingType.SCALE_ASPECT_FILL)
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

            if (!isConnected) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                        .padding(20.dp)
                ) {
                    Text(
                        text = "📺 WebRTC 画面共享等待连接",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "启动车机端 WebRTC 共享后，系统将通过 UDP 自动搜寻车机 IP，或在上方输入车机 IP 点击连接。",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
