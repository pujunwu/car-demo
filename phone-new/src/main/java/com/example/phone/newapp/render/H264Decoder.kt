package com.example.phone.newapp.render

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * 手机端 H.264 (AVC) 硬件解码与直通渲染器 (phone 模块自定义解码实现移植与增强)
 *
 * 核心设计与职责：
 * 1. 【自定义硬件解码与渲染】：使用 Android 底层 [MediaCodec]，将从 WebRTC 拦截到的 H.264 视频流解码并直接送显至 Surface。
 * 2. 【零拷贝直出】：直接将解码图像通过 [MediaCodec.releaseOutputBuffer(index, true)] 渲染至 [AspectRatioSurfaceView] 的 Surface，
 *    消灭 CPU 内存拷贝和 TextureView 重新打包纹理的性能开销。
 * 3. 【无缝 Surface 动态切换】：支持 [updateSurface] (setOutputSurface)，在手机横竖屏切换时复用已初始化的解码器，不卡顿、不闪烁。
 * 4. 【智能 Annex-B 容错】：自动对齐 4 字节 NALU 起始码 (00 00 00 01)，智能从首个关键帧中提取 SPS/PPS 完成解码器冷启动。
 */
class H264Decoder(
    private var surface: Surface,
    private val onVideoFormatChanged: (width: Int, height: Int) -> Unit
) {
    companion object {
        private const val TAG = "H264Decoder"
    }

    private var mediaCodec: MediaCodec? = null

    @Volatile
    private var isConfigured = false

    private var currentWidth = 0
    private var currentHeight = 0

    // 请求上游投递关键帧的回调
    var onRequestKeyFrame: (() -> Unit)? = null

    // 实际物理送显上屏帧率回调
    var onRenderFpsUpdated: ((fps: Int) -> Unit)? = null

    /**
     * 动态更新渲染 Surface
     * 当屏幕旋转、界面重建但底层解码器仍在工作时，直接使用 setOutputSurface 无感重定向画面
     */
    @Synchronized
    fun updateSurface(newSurface: Surface) {
        this.surface = newSurface
        if (isConfigured) {
            try {
                mediaCodec?.setOutputSurface(newSurface)
                Log.i(TAG, "[自定义硬解] 已动态更新渲染 Surface (setOutputSurface)")
            } catch (e: Exception) {
                Log.w(TAG, "[自定义硬解] 该芯片不支持 setOutputSurface，将在下一关键帧重建解码器", e)
                isConfigured = false
            }
        }
    }

    /**
     * 核心解码入口：接收从 WebRTC [CustomVideoDecoder] 拦截到的完整 H.264 单帧数据
     *
     * @param data 完整的 H.264 帧数据 (包含 Annex-B 起始码)
     * @param isKeyFrame 是否为关键帧 (IDR)
     * @param width 视频宽
     * @param height 视频高
     * @param timestampNs 帧时间戳（纳秒）
     */
    @Synchronized
    fun decodeFrame(data: ByteArray, isKeyFrame: Boolean, width: Int, height: Int, timestampNs: Long) {
        val validWidth = if (width > 0) width else currentWidth
        val validHeight = if (height > 0) height else currentHeight

        // 检查分辨率变动
        val resolutionChanged = validWidth > 0 && validHeight > 0 &&
                (validWidth != currentWidth || validHeight != currentHeight)

        if (resolutionChanged) {
            Log.i(TAG, "[自定义硬解] 视频分辨率变更为: ${validWidth}x${validHeight}")
            currentWidth = validWidth
            currentHeight = validHeight
            onVideoFormatChanged(validWidth, validHeight)
        }

        // 规范化 4 字节起始码
        val formattedData = ensure4ByteStartCode(data)

        // 若解码器尚未配置或分辨率改变，必须等待包含 SPS/PPS 的关键帧初始化硬件解码器
        if (!isConfigured || resolutionChanged) {
            val spsPps = extractSpsPps(formattedData)
            if (isKeyFrame || spsPps != null) {
                Log.i(TAG, "[自定义硬解] 关键帧到达 (检测到 SPS/PPS: ${spsPps != null})，初始化 MediaCodec 解码器 (${validWidth}x${validHeight})")
                initDecoder(validWidth.coerceAtLeast(1280), validHeight.coerceAtLeast(720), spsPps)
            } else {
                Log.d(TAG, "[自定义硬解] 解码器尚未初始化且当前为非关键帧，立即请求关键帧并跳过")
                onRequestKeyFrame?.invoke()
                return
            }
            if (!isConfigured) {
                // 等待下一个有效关键帧到来后再行初始化
                return
            }
        }

        val codec = mediaCodec ?: return

        try {
            // 获取解码器可用输入缓冲
            var inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex < 0) {
                drainDecoderOutput()
                inputIndex = codec.dequeueInputBuffer(10_000)
            }

            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(formattedData)

                val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                val ptsUs = if (timestampNs > 0) timestampNs / 1000L else System.nanoTime() / 1000L

                totalInputFrames++
                if (isKeyFrame) {
                    Log.i(TAG, "[自定义硬解] 将关键帧送入 MediaCodec: 大小=${formattedData.size} 字节, ptsUs=$ptsUs, 累计输入帧=$totalInputFrames")
                }

                // 提交至硬件解码器队列
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    formattedData.size,
                    ptsUs,
                    flags
                )
            } else {
                Log.w(TAG, "[自定义硬解] dequeueInputBuffer 超时无可用缓冲 (index=$inputIndex), 丢弃当前帧 (大小=${formattedData.size})")
            }

            // 提取已完成解码的图像并立即渲染至 Surface
            drainDecoderOutput()
        } catch (e: Exception) {
            Log.e(TAG, "[自定义硬解] 解码 H.264 视频帧发生异常", e)
        }
    }

    /**
     * 配置并启动 MediaCodec 硬件解码器
     */
    private fun initDecoder(width: Int, height: Int, spsPpsData: ByteArray?) {
        stop()

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                // 开启硬件低延迟解码管道
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                // 强制禁用 B 帧重排缓冲，防止 MTK/高通 驱动缓存 4~8 帧才送显
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                // 设定解码器最高性能工作频率
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                }
                // 厂商私有低延迟参数（MTK / 高通兼容）
                setInteger("vendor.low-latency.enable", 1)
                setInteger("vdec-lowlatency", 1)

                if (spsPpsData != null) {
                    setByteBuffer("csd-0", ByteBuffer.wrap(spsPpsData))
                }
            }

            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            // 配置直出 Surface 渲染
            decoder.configure(format, surface, null, 0)
            decoder.start()

            mediaCodec = decoder
            isConfigured = true
            hasRenderedFirstFrame = false
            Log.i(TAG, "[自定义硬解] MediaCodec 硬件解码器初始化成功: ${width}x${height} (已配置低延迟与无B帧重排)")
        } catch (e: Exception) {
            Log.e(TAG, "[自定义硬解] 初始化硬件解码器失败", e)
            isConfigured = false
        }
    }

    private var totalInputFrames = 0L
    private var totalRenderedFrames = 0L
    private var lastRenderStatsMs = 0L
    private var intervalRenderedFrames = 0
    private var hasRenderedFirstFrame = false

    /**
     * 轮询提取已完成解码的视频帧，并通过 render=true 直接送显至绑定的 Surface
     */
    private fun drainDecoderOutput() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            // 首帧到达时硬件 VPU 需要数毫秒初始化输出缓冲，首帧给予自适应等待，避免非阻塞 0 超时错过首帧导致十几秒黑屏等待
            val timeoutUs = if (!hasRenderedFirstFrame) 50_000L else 0L
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            var retryCount = 0
            while (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && !hasRenderedFirstFrame && retryCount < 3) {
                retryCount++
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 30_000L)
            }

            while (outputIndex >= 0 || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    val w = if (newFormat.containsKey("crop-right") && newFormat.containsKey("crop-left")) {
                        newFormat.getInteger("crop-right") - newFormat.getInteger("crop-left") + 1
                    } else {
                        newFormat.getInteger(MediaFormat.KEY_WIDTH)
                    }
                    val h = if (newFormat.containsKey("crop-bottom") && newFormat.containsKey("crop-top")) {
                        newFormat.getInteger("crop-bottom") - newFormat.getInteger("crop-top") + 1
                    } else {
                        newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    Log.i(TAG, "[自定义硬解] MediaCodec 硬件输出格式变更: ${w}x${h}, 格式详情: $newFormat")
                    if (w > 0 && h > 0 && (w != currentWidth || h != currentHeight)) {
                        currentWidth = w
                        currentHeight = h
                        onVideoFormatChanged(w, h)
                    }
                } else if (outputIndex >= 0) {
                    // render = true: 指示底层驱动直接将当前解码图像送至 SurfaceFlinger 合成上屏
                    codec.releaseOutputBuffer(outputIndex, true)
                    totalRenderedFrames++
                    intervalRenderedFrames++

                    if (!hasRenderedFirstFrame) {
                        hasRenderedFirstFrame = true
                        Log.i(TAG, "[自定义硬解渲染] ★★★ 画面首帧已成功解码并送显至 Surface ★★★ (尺寸: ${currentWidth}x${currentHeight})")
                    }

                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastRenderStatsMs >= 1000) {
                        if (lastRenderStatsMs > 0) {
                            val fps = (intervalRenderedFrames * 1000.0f / (nowMs - lastRenderStatsMs)).toInt()
                            Log.i(TAG, "[自定义硬解渲染诊断] 渲染送显正常: 累计已送显=$totalRenderedFrames 帧, 当前渲染帧率=$fps FPS, 累计输入帧=$totalInputFrames")
                            onRenderFpsUpdated?.invoke(fps)
                        }
                        intervalRenderedFrames = 0
                        lastRenderStatsMs = nowMs
                    }
                }
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[自定义硬解] drainDecoderOutput 提取送显异常", e)
        }
    }

    /**
     * 从完整关键帧字节流中探测并提取 SPS/PPS 参数集
     */
    private fun extractSpsPps(data: ByteArray): ByteArray? {
        if (data.size < 8) return null
        var spsStart = -1
        var idrStart = -1

        for (i in 0 until data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                val nalType = data[i + 4].toInt() and 0x1F
                if (nalType == 7) { // SPS
                    spsStart = i
                } else if (nalType == 5 && spsStart >= 0) { // IDR
                    idrStart = i
                    break
                }
            }
        }

        if (spsStart >= 0 && idrStart > spsStart) {
            val length = idrStart - spsStart
            val spsPps = ByteArray(length)
            System.arraycopy(data, spsStart, spsPps, 0, length)
            return spsPps
        }
        return null
    }

    /**
     * 规范化 Annex-B 起始码为标准 4 字节 00 00 00 01
     */
    private fun ensure4ByteStartCode(data: ByteArray): ByteArray {
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            return data
        }
        if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            val newData = ByteArray(data.size + 1)
            newData[0] = 0
            newData[1] = 0
            newData[2] = 0
            newData[3] = 1
            System.arraycopy(data, 3, newData, 4, data.size - 3)
            return newData
        }
        return data
    }

    /**
     * 停止解码并释放 MediaCodec
     */
    @Synchronized
    fun stop() {
        if (!isConfigured && mediaCodec == null) return
        isConfigured = false

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "[自定义硬解] 停止解码器异常", e)
        }
        mediaCodec = null
        Log.i(TAG, "[自定义硬解] H.264 解码器已停止并释放资源")
    }
}
