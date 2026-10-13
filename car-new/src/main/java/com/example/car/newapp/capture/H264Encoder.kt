package com.example.car.newapp.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * H.264 (AVC) 硬件编码器（car 模块自定义编码实现移植与增强）
 *
 * 核心设计：
 * 1. 采用 Android 原生 [MediaCodec] 硬件加速，将 VirtualDisplay 渲染管道的画面直接编码为 H.264 Annex-B 码流。
 * 2. 具备零拷贝输入 Surface (createInputSurface)，画面直入 GPU/VPU 硬件加速器。
 * 3. 深度低延迟调优：High Profile 编码提高画质、强制禁用 B 帧彻底消除帧重排延迟、开启低延迟管道模式。
 * 4. 自动维护 SPS/PPS 参数集，并在每一个 I 帧关键帧前自动补齐 [SPS][PPS][IDR] 序列，适配 WebRTC RFC 6184 封包规范。
 * 5. 支持动态码率调整 (updateBitrate)，可无缝响应 WebRTC GCC 拥塞控制反馈。
 * 6. 支持动态即时关键帧请求 (requestKeyFrame)。
 *
 * @param width 视频宽（偶数对齐）
 * @param height 视频高（偶数对齐）
 * @param initialBitrate 初始编码码率（默认 6 Mbps）
 * @param frameRate 目标帧率（默认 30 fps）
 * @param iFrameInterval 关键帧间隔（默认 1 秒）
 * @param onFrameEncoded 编码帧完成回调：(isKeyFrame, timestampNs, width, height, data)
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private var initialBitrate: Int = 6_000_000,
    private val frameRate: Int = 30,
    private val iFrameInterval: Int = 1,
    private val onFrameEncoded: (isKeyFrame: Boolean, timestampNs: Long, width: Int, height: Int, data: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "H264Encoder"
    }

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null

    @Volatile
    private var isEncoding = false
    private var encoderThread: Thread? = null

    // 缓存最新的 SPS/PPS 数据，用于拼接到关键帧头部供 WebRTC 打包
    private var cachedSpsPps: ByteArray? = null
    // 缓存最新的完整关键帧 (SPS + PPS + IDR)，用于在画面静止或客户端刚接入时立即投递首帧
    @Volatile
    private var cachedKeyFrame: ByteArray? = null

    /**
     * 获取供 VirtualDisplay 绑定的输入 Surface
     */
    fun getInputSurface(): Surface {
        return inputSurface ?: throw IllegalStateException("Encoder not initialized or input surface is null")
    }

    /**
     * 主动向 MediaCodec 发送请求，强制立即生成一个完整的 IDR 同步关键帧。
     * 若屏幕画面处于静止状态，底层 MediaCodec 可能暂无新 Surface 输入，此时立即重放缓存的关键帧以唤醒接收端解码器。
     */
    fun requestKeyFrame() {
        if (!isEncoding) return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
            Log.i(TAG, "[自定义硬编] 已向 MediaCodec 注入关键帧请求 (PARAMETER_KEY_REQUEST_SYNC_FRAME)")
        } catch (e: Exception) {
            Log.w(TAG, "[自定义硬编] 请求关键帧失败: ${e.message}")
        }

        // 若当前已有缓存的关键帧数据，立即重发一次，彻底解决静止画面下 MediaCodec 无帧输出导致接收端黑屏的问题
        val lastKey = cachedKeyFrame
        if (lastKey != null) {
            val nowNs = System.nanoTime()
            Log.i(TAG, "[自定义硬编] 响应关键帧请求：立即向 WebRTC 管道重发缓存的关键帧 (${lastKey.size} 字节)")
            onFrameEncoded(true, nowNs, width, height, lastKey)
        }
    }

    /**
     * 响应 WebRTC 拥塞控制算法动态调整硬件编码码率
     *
     * @param newBitrateBps 目标码率（单位：bps）
     */
    fun updateBitrate(newBitrateBps: Int) {
        if (!isEncoding || newBitrateBps <= 0) return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrateBps)
            }
            mediaCodec?.setParameters(params)
            Log.i(TAG, "[自定义硬编] 动态更新 MediaCodec 编码码率: ${newBitrateBps / 1000} kbps")
        } catch (e: Exception) {
            Log.w(TAG, "[自定义硬编] 动态调整码率失败: ${e.message}")
        }
    }

    /**
     * 配置并启动 H.264 硬件编码器
     */
    fun start() {
        if (isEncoding) return

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                // Surface 输入模式，零拷贝直通
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, initialBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)

                // High Profile 提升画质细节与边缘锐利度 (Level 4.1 适配 1080p@60)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                }
                // 强制禁用 B 帧，消灭帧重排与双向预测延迟
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                // 画质保真指数
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_QUALITY, 100)
                }
                // 画面静止保护：超过 33ms 无新帧时自动重复上一帧，维持 30 FPS 稳定帧率
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 33_333L)
                }
                // 0 延迟管道
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                }
                // 实时最高优先级
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()

            isEncoding = true
            encoderThread = Thread({ drainEncoder() }, "H264CustomEncoderThread").apply { start() }

            Log.i(TAG, "[自定义硬编] 编码器成功启动: ${width}x${height} @ ${frameRate}fps, 码率: ${initialBitrate / 1000}kbps")
        } catch (e: Exception) {
            Log.e(TAG, "[自定义硬编] 启动 MediaCodec 硬件编码器异常", e)
            stop()
        }
    }

    private var totalEncodedFrames = 0L
    private var totalKeyFrames = 0L
    private var lastStatsLogTimeMs = 0L
    private var intervalFrames = 0

    /**
     * 轮询提取已编码的 H.264 视频数据并打包回调
     */
    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (isEncoding) {
            val codec = mediaCodec ?: break
            try {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "[自定义硬编] MediaCodec 输出格式已更新: ${codec.outputFormat}")
                } else if (outputBufferIndex >= 0) {
                    val outputBuffer: ByteBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue

                    // 1. 检查是否为 SPS/PPS 配置参数帧
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        val spsPps = ByteArray(bufferInfo.size)
                        outputBuffer.get(spsPps)
                        cachedSpsPps = spsPps
                        Log.i(TAG, "[自定义硬编] 捕获并缓存 SPS/PPS 参数集 (${spsPps.size} 字节)")
                    } else if (bufferInfo.size > 0) {
                        // 2. 提取视频帧数据
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val frameBytes = ByteArray(bufferInfo.size)
                        outputBuffer.get(frameBytes)

                        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        val timestampNs = if (bufferInfo.presentationTimeUs > 0) {
                            bufferInfo.presentationTimeUs * 1000L
                        } else {
                            System.nanoTime()
                        }

                        // 如果是关键帧，将 SPS/PPS 拼接到 IDR 帧前（WebRTC H.264 RFC 6184 封包要求）
                        val completeFrame = if (isKeyFrame && cachedSpsPps != null) {
                            val spsPps = cachedSpsPps!!
                            val combined = ByteArray(spsPps.size + frameBytes.size)
                            System.arraycopy(spsPps, 0, combined, 0, spsPps.size)
                            System.arraycopy(frameBytes, 0, combined, spsPps.size, frameBytes.size)
                            combined
                        } else {
                            frameBytes
                        }

                        totalEncodedFrames++
                        intervalFrames++

                        if (isKeyFrame) {
                            totalKeyFrames++
                            cachedKeyFrame = completeFrame
                            Log.i(TAG, "[自定义硬编] 成功编码生成 IDR 关键帧: 字节大小=${completeFrame.size}, 包含SPS/PPS=${cachedSpsPps != null}, 累计I帧=$totalKeyFrames, ptsMs=${timestampNs / 1_000_000}")
                        }

                        // 周期性编码状态日志输出 (每 2 秒一次)
                        val nowMs = SystemClock.elapsedRealtime()
                        if (nowMs - lastStatsLogTimeMs >= 2000) {
                            if (lastStatsLogTimeMs > 0) {
                                val fps = (intervalFrames * 1000.0f / (nowMs - lastStatsLogTimeMs)).toInt()
                                Log.i(TAG, "[自定义硬编诊断] 编码运行正常: 累计编码=$totalEncodedFrames 帧, 当前编码帧率=$fps FPS, 累计关键帧=$totalKeyFrames 帧")
                            }
                            intervalFrames = 0
                            lastStatsLogTimeMs = nowMs
                        }

                        // 回调输出已编码的帧数据
                        onFrameEncoded(isKeyFrame, timestampNs, width, height, completeFrame)
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)
                }
            } catch (e: Exception) {
                if (isEncoding) {
                    Log.e(TAG, "[自定义硬编] drainEncoder 提取循环异常", e)
                }
            }
        }
    }

    /**
     * 停止编码并释放所有资源
     */
    fun stop() {
        if (!isEncoding) return
        isEncoding = false

        try {
            encoderThread?.join(1000)
        } catch (_: Exception) {}
        encoderThread = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "[自定义硬编] 释放 MediaCodec 异常", e)
        }
        mediaCodec = null

        inputSurface?.release()
        inputSurface = null
        cachedSpsPps = null
        cachedKeyFrame = null

        Log.i(TAG, "[自定义硬编] H.264 编码器已完全停止并释放资源")
    }
}
