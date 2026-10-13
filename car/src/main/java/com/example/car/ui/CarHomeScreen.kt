package com.example.car.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 车机端主界面 Compose UI
 *
 * @param serverIp 车机本地局域网 IP 地址
 * @param serverPort 车机 Socket 服务端监听端口号（默认 22345）
 * @param isStreaming 当前是否处于屏幕录制推流中
 * @param clientCount 当前已连接的手机端数量
 * @param selectedResolution 当前选中的录屏分辨率（"720p" 或 "1080p"）
 * @param enableDynamicAdaptive 是否启用动态帧率/码率自适应（静止降帧）
 * @param isStaticMode 编码器当前是否判定为静止画面模式
 * @param currentBitrateKbps 编码器当前的实时码率（Kbps）
 * @param onResolutionSelected 用户选择录屏分辨率时的回调
 * @param onToggleDynamicAdaptive 用户切换动态自适应开关时的回调
 * @param onToggleStreaming 用户点击启动/停止推流按钮时的回调
 */
@Composable
fun CarHomeScreen(
    serverIp: String,
    serverPort: Int,
    isStreaming: Boolean,
    clientCount: Int,
    selectedResolution: String,
    enableDynamicAdaptive: Boolean,
    isStaticMode: Boolean,
    currentBitrateKbps: Int,
    onResolutionSelected: (String) -> Unit,
    onToggleDynamicAdaptive: (Boolean) -> Unit,
    onToggleStreaming: () -> Unit
) {
    // 推流计时器（秒）
    var streamSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            streamSeconds = 0
            while (isActive) {
                delay(500)
                streamSeconds++
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Title
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "🚗 车机端录屏 Socket 推流服务",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "系统应用 (System App) • 硬件编码 H.264",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "服务状态",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "推流状态:")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .height(10.dp)
                                    .width(10.dp)
                                    .background(
                                        color = if (isStreaming) Color(0xFF4CAF50) else Color(0xFFF44336),
                                        shape = RoundedCornerShape(5.dp)
                                    )
                            )
                            Text(
                                text = if (isStreaming) "正在推流 (${streamSeconds / 2}s)" else "已停止",
                                fontWeight = FontWeight.Bold,
                                color = if (isStreaming) Color(0xFF2E7D32) else Color(0xFFC62828)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "实时编解码状态:")
                        Text(
                            text = if (isStreaming) {
                                if (enableDynamicAdaptive && isStaticMode) "🍃 画面静止 (10 FPS, ${currentBitrateKbps} Kbps)"
                                else "🚀 画面运动 (30 FPS, ${currentBitrateKbps} Kbps)"
                            } else "未工作",
                            fontWeight = FontWeight.Bold,
                            color = if (isStaticMode) Color(0xFF388E3C) else MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "车机 IP 地址:")
                        Text(text = serverIp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Socket 端口:")
                        Text(text = "$serverPort", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "已连接手机客户端:")
                        Text(
                            text = "$clientCount 台",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Dynamic Adaptive Setting Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "智能动态帧率与码率",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "画面未变动时自动降至 10 FPS / 800 Kbps 节省带宽与功耗",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = enableDynamicAdaptive,
                            onCheckedChange = onToggleDynamicAdaptive,
                            enabled = !isStreaming
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "录屏分辨率设置",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        OutlinedButton(
                            onClick = { onResolutionSelected("720p") },
                            enabled = !isStreaming,
                            colors = if (selectedResolution == "720p")
                                ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            else ButtonDefaults.outlinedButtonColors()
                        ) {
                            Text("1280x720 (720p)")
                        }

                        OutlinedButton(
                            onClick = { onResolutionSelected("1080p") },
                            enabled = !isStreaming,
                            colors = if (selectedResolution == "1080p")
                                ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            else ButtonDefaults.outlinedButtonColors()
                        ) {
                            Text("1920x1080 (1080p)")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Toggle Action Button
            Button(
                onClick = onToggleStreaming,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isStreaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (isStreaming) "停止车机端录屏推流" else "启动车机端录屏推流",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(130.dp))
        }
    }
}
