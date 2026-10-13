package com.example.phone.ui

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
import com.example.phone.AspectRatioSurfaceView
import com.example.phone.PhoneSocketClient

/**
 * 手机端投屏主界面 Compose UI
 *
 * @param serverIp 车机端目标 IP 地址
 * @param serverPort 车机端目标端口（默认为 22345）
 * @param status 当前 Socket 连接状态（DISCONNECTED, CONNECTING, CONNECTED, ERROR）
 * @param errorMessage 连接失败时的详细错误说明或排查引导
 * @param discoveredCarIp 通过局域网 UDP 自动广播捕获到的车机 IP 地址
 * @param videoWidth 当前正在渲染的视频宽度
 * @param videoHeight 当前正在渲染的视频高度
 * @param fps 实时统计的接收/渲染帧率（FPS）
 * @param bitrateKbps 实时统计的视频接收带宽（Kbps）
 * @param scaleType 画面拉伸模式（FIT: 保比例黑边全显, CROP: 铺满裁切）
 * @param isLandscape 当前是否处于横屏全屏模式
 * @param onIpChanged 用户修改 IP 地址输入框时的回调
 * @param onPortChanged 用户修改端口输入框时的回调
 * @param onScaleTypeChanged 用户切换画面拉伸模式时的回调
 * @param onToggleOrientation 用户点击横竖屏切换按钮时的回调
 * @param onToggleConnection 用户点击连接/断开按钮时的回调
 * @param onSurfaceReady 底层 SurfaceHolder 创建就绪时的回调
 * @param onSurfaceDestroyed 底层 SurfaceHolder 销毁时的回调
 */
@Composable
fun PhoneHomeScreen(
    serverIp: String,
    serverPort: String,
    status: PhoneSocketClient.Status,
    errorMessage: String?,
    discoveredCarIp: String?,
    videoWidth: Int,
    videoHeight: Int,
    fps: Int,
    bitrateKbps: Long,
    scaleType: AspectRatioSurfaceView.ScaleType,
    isLandscape: Boolean,
    onIpChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onScaleTypeChanged: (AspectRatioSurfaceView.ScaleType) -> Unit,
    onToggleOrientation: () -> Unit,
    onToggleConnection: () -> Unit,
    onSurfaceReady: (SurfaceHolder) -> Unit,
    onSurfaceDestroyed: () -> Unit
) {
    // 是否显示顶部悬浮控制栏（点击屏幕可切换隐显）
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
            // Layer 1: Full-Screen AspectRatioSurfaceView Background Video Render Area
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

            // Layer 2: Floating Animated Top Control Panel (IP/Port/Controls)
            AnimatedVisibility(
                visible = showControls || status != PhoneSocketClient.Status.CONNECTED,
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
                                enabled = status == PhoneSocketClient.Status.DISCONNECTED || status == PhoneSocketClient.Status.ERROR,
                                modifier = Modifier.weight(2f),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            OutlinedTextField(
                                value = serverPort,
                                onValueChange = onPortChanged,
                                label = { Text("端口") },
                                enabled = status == PhoneSocketClient.Status.DISCONNECTED || status == PhoneSocketClient.Status.ERROR,
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            Button(
                                onClick = onToggleConnection,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (status == PhoneSocketClient.Status.CONNECTED)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.height(56.dp)
                            ) {
                                Text(
                                    text = when (status) {
                                        PhoneSocketClient.Status.CONNECTED -> "断开"
                                        PhoneSocketClient.Status.CONNECTING -> "连接中"
                                        else -> "连接"
                                    }
                                )
                            }
                        }

                        // Auto Discovered Tip
                        if (!discoveredCarIp.isNullOrEmpty() && (status == PhoneSocketClient.Status.DISCONNECTED || status == PhoneSocketClient.Status.ERROR)) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "✨ 局域网组播已自动搜索到车机: $discoveredCarIp",
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

                        // Status and scaling mode controls
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
                                            color = when (status) {
                                                PhoneSocketClient.Status.CONNECTED -> Color(0xFF4CAF50)
                                                PhoneSocketClient.Status.CONNECTING -> Color(0xFFFF9800)
                                                else -> Color(0xFFF44336)
                                            },
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                )
                                Text(
                                    text = when (status) {
                                        PhoneSocketClient.Status.CONNECTED -> "${videoWidth}x${videoHeight} | ${fps}FPS | ${bitrateKbps}kbps (点击屏幕隐显控制条)"
                                        PhoneSocketClient.Status.CONNECTING -> "正在连接..."
                                        PhoneSocketClient.Status.ERROR -> "连接失败"
                                        PhoneSocketClient.Status.DISCONNECTED -> "未连接"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Aspect Ratio Scale Mode & Orientation Selector
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
                                    onClick = { onScaleTypeChanged(AspectRatioSurfaceView.ScaleType.FIT) },
                                    modifier = Modifier.height(32.dp),
                                    colors = if (scaleType == AspectRatioSurfaceView.ScaleType.FIT)
                                        ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                    else ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text("全显", fontSize = 10.sp)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                OutlinedButton(
                                    onClick = { onScaleTypeChanged(AspectRatioSurfaceView.ScaleType.CROP) },
                                    modifier = Modifier.height(32.dp),
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

            // Layer 3: Connection Prompt Overlay (When Disconnected / Error)
            if (status == PhoneSocketClient.Status.DISCONNECTED || status == PhoneSocketClient.Status.CONNECTING || status == PhoneSocketClient.Status.ERROR) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                        .padding(20.dp)
                ) {
                    Text(
                        text = when (status) {
                            PhoneSocketClient.Status.CONNECTING -> "📺 正在连接车机推流服务..."
                            PhoneSocketClient.Status.ERROR -> "❌ 连接失败 (EHOSTUNREACH / 无法到达主机)"
                            else -> "📺 等待连接车机画面"
                        },
                        color = if (status == PhoneSocketClient.Status.ERROR) Color(0xFFFF5252) else Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    if (status == PhoneSocketClient.Status.ERROR) {
                        Spacer(modifier = Modifier.height(10.dp))
                        if (!errorMessage.isNullOrBlank()) {
                            Text(
                                text = errorMessage,
                                color = Color(0xFFFFD54F),
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            text = "💡 常见排查方案:",
                            color = Color(0xFFFFD54F),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "1. 当前企业/公用 Wi-Fi 开启了【AP 隔离限制】，禁止了设备间直接通信。\n" +
                                    "2. 推荐方式：在一台手机/车机上开启【个人热点】，让另一台设备连接该热点！热点局域网 100% 互通。\n" +
                                    "3. 请确认车机端已点击【启动车机端录屏推流】。",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "已支持 UDP 自动搜索车机 IP，或手动输入车机 IP 地址点击连接",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
