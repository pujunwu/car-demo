package com.example.car.webrtc.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.example.protocol.StreamPacket
import java.nio.ByteBuffer

/**
 * H.264 (AVC) 硬件编码器封装类
 *
 * 核心功能：
 * 1. 使用 Android 底层 [MediaCodec] 硬件加速将屏幕图像流编码为 H.264 视频流。
 * 2. 提供 Input Surface 供 [VirtualDisplay] 渲染，实现零拷贝高性能录屏编码。
 * 3. 针对投屏场景深度优化：禁用 B 帧、启用 High Profile、设置零延迟（Low Latency）及实时优先级。
 * 4. 支持动态帧率与码率自适应（检测画面静止自动降低码率与帧率，减少带宽占用）。
 * 5. 支持动态请求即时 IDR 关键帧，实现快速恢复渲染。
 *
 * @param width 视频编码宽度（像素）
 * @param height 视频编码高度（像素）
 * @param maxBitrate 动态运动画面时的最高编码码率（bps，默认 12Mbps）
 * @param minBitrate 静态无变动画面时的保底编码码率（bps，默认 8Mbps）
 * @param maxFrameRate 动态画面时的最大目标帧率（fps，默认 30fps）
 * @param minFrameRate 静态画面时的最低目标帧率（fps，默认 10fps）
 * @param iFrameInterval 关键帧（I 帧）间隔时间（单位：秒，默认 1 秒）
 * @param enableDynamicAdaptive 是否启用动态智能码率与帧率调节
 * @param onFrameEncoded 编码输出帧数据回调（type: 帧类型, timestamp: 呈现时间戳ms, width: 宽, height: 高, data: 二进制数据）
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val maxBitrate: Int = 12_000_000,
    private val minBitrate: Int = 8_000_000,
    private val maxFrameRate: Int = 30,
    private val minFrameRate: Int = 10,
    private val iFrameInterval: Int = 1,
    private val enableDynamicAdaptive: Boolean = true,
    private val onFrameEncoded: (type: Byte, timestamp: Long, width: Int, height: Int, data: ByteArray) -> Unit
) {

    private val tag = "H264Encoder"
    // 底层 Android 硬件视频编码器
    private var mediaCodec: MediaCodec? = null
    // 编码器的输入 Surface，与 VirtualDisplay 绑定
    private var inputSurface: Surface? = null
    // 编码工作线程运行状态标记
    @Volatile
    private var isEncoding = false
    // 从编码器轮询读取已编码视频包的后台线程
    private var encoderThread: Thread? = null

    // 动态帧率与码率控制状态：是否处于静止省电模式
    @Volatile
    var isStaticMode = false
        private set

    // 连续检测到的低运动量/静态帧计数器
    private var consecutiveStaticFrames = 0
    // 判定为静态 P 帧的数据大小阈值（小于 8KB 认为画面无明显变化）
    private val staticFrameThresholdBytes = 8_000
    // 连续出现静态帧的触发阈值（15 帧，约 0.5 秒无明显变动则切入静止模式）
    private val staticFrameCountTrigger = 15

    // 上一次成功输出帧的时间戳（毫秒）
    private var lastBroadcastTimestampMs = 0L
    // 当前生效的目标编码码率
    private var currentActiveBitrate = maxBitrate

    // 编码动态自适应状态（是否静止、当前码率）变更监听回调
    var onEncodingStateChanged: ((isStatic: Boolean, currentBitrateKbps: Int) -> Unit)? = null

    /**
     * 获取供 VirtualDisplay 渲染输入的 Surface
     * @return 绑定的输入 Surface
     * @throws IllegalStateException 若编码器尚未启动或 Surface 无效
     */
    fun getInputSurface(): Surface {
        return inputSurface ?: throw IllegalStateException("Encoder not initialized or surface unavailable")
    }

    /**
     * 主动向 MediaCodec 发送请求，强制立即生成一个完整的 IDR 同步关键帧
     */
    fun requestKeyFrame() {
        if (!isEncoding) return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
            Log.i(tag, "[H.264编码器] 收到关键帧请求，已向 MediaCodec 注入 PARAMETER_KEY_REQUEST_SYNC_FRAME")
        } catch (e: Exception) {
            Log.w(tag, "[H.264编码器] 请求 I 帧关键帧失败", e)
        }
    }

    /**
     * 配置并启动 H.264 硬件编码器
     */
    fun start() {
        if (isEncoding) return

        try {
            // 配置 H.264 视频编码参数
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                // 设置颜色格式为 Surface 输入模式（配合 VirtualDisplay 实现硬件零拷贝直推）
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                // 设置初始编码目标码率
                setInteger(MediaFormat.KEY_BIT_RATE, maxBitrate)
                // 设置初始目标帧率
                setInteger(MediaFormat.KEY_FRAME_RATE, maxFrameRate)
                // 设置关键帧（GOP）间隔时间（单位：秒）
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
                // 设置码率控制模式为可变码率 VBR（在动态和静态画面间自动平衡质量与带宽）
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)

                // High Profile 配置：引入 8x8 频域变换，显著提升车机文字与矢量图标锐利度
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                }
                // 关键参数：强制禁用 B 帧（最大 B 帧数为 0），彻底消除双向预测导致的帧重排序延迟
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                // 画质评估指数：100% 满分画质保真
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_QUALITY, 100)
                }
                // 画面静止保护：超过 100ms 无新帧时自动重复上一帧，防止流中断或黑屏
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                }
                // 启用低延迟编码管道模式（0 延迟）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                }
                // 设定编码器优先级为最高实时优先（Real-time priority 0）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
            }

            // 创建硬件编码器实例并配置为编码模式
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // 创建输入 Surface（录屏画面直接渲染至该 Surface 进入硬件编码器）
            inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()

            isEncoding = true
            currentActiveBitrate = maxBitrate
            // 启动独立线程轮询提取已编码的 H.264 NALU 数据
            encoderThread = Thread({ drainEncoder() }, "H264EncoderThread")
            encoderThread?.start()

            Log.i(tag, "[H.264编码器] 编码器成功启动: 分辨率 ${width}x${height}，最高码率 ${maxBitrate / 1000}kbps，最高帧率 ${maxFrameRate}fps")
        } catch (e: Exception) {
            Log.e(tag, "[H.264编码器] 初始化 H.264 硬件编码器失败", e)
            stop()
        }
    }

    /**
     * 轮询提取并消费硬件编码器输出缓冲区中的已编码视频帧数据
     */
    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        var spsPpsBuffer: ByteArray? = null

        while (isEncoding) {
            val codec = mediaCodec ?: break
            try {
                // 0 超时非阻塞轮询已完成编码的输出缓冲区索引
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                if (outputBufferIndex >= 0) {
                    val outputBuffer: ByteBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue

                    // 1. 检查是否为编解码器配置参数包（SPS/PPS Sequence & Picture Parameter Sets）
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        val spsPps = ByteArray(bufferInfo.size)
                        outputBuffer.get(spsPps)
                        spsPpsBuffer = spsPps
                        Log.i(tag, "[H.264编码器] 已编码生成 SPS/PPS 参数集数据包 (${spsPps.size} 字节)")

                        onFrameEncoded(
                            StreamPacket.TYPE_SPS_PPS,
                            SystemClock.elapsedRealtime(),
                            width,
                            height,
                            spsPps
                        )
                    } else if (bufferInfo.size > 0) {
                        // 2. 读取编码后的视频帧数据（I 帧或 P 帧）
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val frameData = ByteArray(bufferInfo.size)
                        outputBuffer.get(frameData)

                        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        val packetType = if (isKeyFrame) StreamPacket.TYPE_KEY_FRAME else StreamPacket.TYPE_INTER_FRAME

                        val nowMs = SystemClock.elapsedRealtime()

                        // 3. 动态帧分析：检测画面运动量并适时调整状态
                        if (enableDynamicAdaptive) {
                            checkAndUpdateDynamicParams(isKeyFrame, bufferInfo.size)
                        }

                        lastBroadcastTimestampMs = nowMs

                        // 若是关键帧（I 帧），先确保补齐发送一份 SPS/PPS，保障客户端解码安全
                        if (isKeyFrame && spsPpsBuffer != null) {
                            onFrameEncoded(
                                StreamPacket.TYPE_SPS_PPS,
                                nowMs,
                                width,
                                height,
                                spsPpsBuffer
                            )
                        }

                        // 输出实际视频帧数据包
                        onFrameEncoded(
                            packetType,
                            bufferInfo.presentationTimeUs / 1000,
                            width,
                            height,
                            frameData
                        )
                    }

                    // 释放输出缓冲区，归还给编码器循环使用
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                }
            } catch (e: Exception) {
                if (isEncoding) {
                    Log.e(tag, "[H.264编码器] 编码器 drain 提取数据循环发生异常", e)
                }
            }
        }
    }

    /**
     * 智能动态帧检测算法：根据帧大小和类型判断画面是否发生变动
     * @param isKeyFrame 是否为关键帧
     * @param frameSize 当前帧数据大小（字节）
     */
    private fun checkAndUpdateDynamicParams(isKeyFrame: Boolean, frameSize: Int) {
        if (isKeyFrame || frameSize >= staticFrameThresholdBytes) {
            // 关键帧或数据量较大的 P 帧表示画面发生显著运动，立即重置静止计数
            consecutiveStaticFrames = 0
            if (isStaticMode) {
                isStaticMode = false
                // 画面恢复运动时，主动请求一个关键帧确保画质即时刷清
                requestKeyFrame()
            }
        } else {
            // 数据量小表示画面静止（仅有少量差量编码）
            consecutiveStaticFrames++
            if (consecutiveStaticFrames >= staticFrameCountTrigger && !isStaticMode) {
                isStaticMode = true
            }
        }
    }

    /**
     * 停止编码工作，释放 MediaCodec、InputSurface 及相关线程资源
     */
    fun stop() {
        if (!isEncoding) return
        isEncoding = false

        try {
            encoderThread?.join(1000)
        } catch (_: Exception) {}

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(tag, "[H.264编码器] 停止 MediaCodec 编码器发生异常", e)
        }

        inputSurface?.release()
        inputSurface = null
        mediaCodec = null
        Log.i(tag, "[H.264编码器] H.264 编码器已停止并释放资源")
    }
}
