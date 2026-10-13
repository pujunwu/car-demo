package com.example.phone

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.example.protocol.StreamPacket
import java.nio.ByteBuffer

/**
 * 手机端 H.264 (AVC) 硬件解码器
 *
 * 核心设计特点：
 * 1. 采用单例长效解码器机制：避免因 SPS/PPS 周期性刷新而频繁重建 MediaCodec，消除画面周期性卡顿与拖影。
 * 2. 动态 Surface 无感切换：支持 setOutputSurface，在横竖屏切换或窗口大小改变时无需销毁解码器。
 * 3. 低延迟调优：启用 Android R+ 的 KEY_LOW_LATENCY 与最大工作速率 KEY_OPERATING_RATE。
 * 4. 自动修复 NALU 起始码：兼容 3 字节 (00 00 01) 与 4 字节 (00 00 00 01) Annex-B 封装格式。
 *
 * @param surface 画面渲染展示的底层 Surface
 * @param onVideoFormatChanged 视频流分辨率发生变动时的回调通知
 */
class H264Decoder(
    private var surface: Surface,
    private val onVideoFormatChanged: (width: Int, height: Int) -> Unit
) {

    private val tag = "H264Decoder"
    // 底层 Android 硬件视频解码器
    private var mediaCodec: MediaCodec? = null
    // 解码器是否已成功配置初始化就绪
    @Volatile
    private var isConfigured = false

    // 当前解码器生效的视频宽度与高度
    private var currentWidth = 0
    private var currentHeight = 0

    /**
     * 动态更新渲染 Surface
     * 关键点：在横竖屏旋转或界面重建时，优先使用 setOutputSurface，避免销毁并重新初始化 MediaCodec
     * @param newSurface 全新的渲染 Surface
     */
    @Synchronized
    fun updateSurface(newSurface: Surface) {
        this.surface = newSurface
        if (isConfigured) {
            try {
                mediaCodec?.setOutputSurface(newSurface)
                Log.i(tag, "[H.264解码器] 已成功更新渲染 Surface (setOutputSurface)")
            } catch (e: Exception) {
                Log.w(tag, "[H.264解码器] 该平台硬件不支持动态 updateSurface，准备重新初始化解码器", e)
                isConfigured = false
            }
        }
    }

    /**
     * 解码接收到的网络视频数据包
     * @param packet 从 Socket 接收到的 StreamPacket
     */
    @Synchronized
    fun decodeFrame(packet: StreamPacket) {
        val width = packet.width.toInt()
        val height = packet.height.toInt()

        // 检测分辨率是否发生切换
        val resolutionChanged = width > 0 && height > 0 && (width != currentWidth || height != currentHeight)
        if (resolutionChanged) {
            Log.i(tag, "[H.264解码器] 检测到视频分辨率改变: ${width}x${height}")
            currentWidth = width
            currentHeight = height
            onVideoFormatChanged(width, height)
        }

        // 1. 处理 SPS/PPS 编解码器关键参数配置数据包
        if (packet.type == StreamPacket.TYPE_SPS_PPS) {
            if (!isConfigured || resolutionChanged) {
                Log.i(tag, "[H.264解码器] 收到 SPS/PPS 参数配置帧，开始初始化硬件解码器 (${width}x${height})...")
                initDecoder(width, height, packet.payload)
                return
            } else {
                // 关键点：若解码器已在运行且分辨率未变，切勿销毁解码器，直接将 SPS/PPS 放入配置缓冲区，防止画面闪烁与残影
                val codec = mediaCodec ?: return
                try {
                    val inputIndex = codec.dequeueInputBuffer(0)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: return
                        buffer.clear()
                        val formatted = ensure4ByteStartCode(packet.payload)
                        buffer.put(formatted)
                        codec.queueInputBuffer(inputIndex, 0, formatted.size, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                    }
                } catch (_: Exception) {}
                return
            }
        }

        if (!isConfigured) {
            return
        }

        val codec = mediaCodec ?: return

        try {
            // 2. 轮询获取解码器空闲输入缓冲区
            var inputBufferIndex = codec.dequeueInputBuffer(0)
            if (inputBufferIndex < 0) {
                // 若输入缓冲区满，先尝试消化提取已解码的输出帧，腾出空间
                drainDecoderOutput()
                inputBufferIndex = codec.dequeueInputBuffer(0)
            }

            if (inputBufferIndex >= 0) {
                val inputBuffer: ByteBuffer = codec.getInputBuffer(inputBufferIndex) ?: return
                inputBuffer.clear()

                // 确保数据包包含 Annex-B 标准 4 字节起始码 00 00 00 01
                val formattedData = ensure4ByteStartCode(packet.payload)
                inputBuffer.put(formattedData)

                // 标记关键帧标志位
                val flags = if (packet.type == StreamPacket.TYPE_KEY_FRAME) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0

                val ptsUs = if (packet.timestamp > 0) packet.timestamp * 1000L else System.nanoTime() / 1000L

                // 将帧送入硬件解码队列
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    formattedData.size,
                    ptsUs,
                    flags
                )
            }

            // 3. 提取已完成解码的图像并直接渲染输出至 Surface
            drainDecoderOutput()
        } catch (e: Exception) {
            Log.e(tag, "[H.264解码器] 解码 H.264 帧发生异常", e)
        }
    }

    /**
     * 初始化配置 MediaCodec 硬件解码器
     * @param width 视频宽
     * @param height 视频高
     * @param spsPpsData SPS/PPS 二进制数据
     */
    private fun initDecoder(width: Int, height: Int, spsPpsData: ByteArray) {
        stop()

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                // Android 11+ 启用硬件低延迟解码管道
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                // 设置解码器最高工作频率（Short.MAX_VALUE），最大化解码速度
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                }
                // 注入 SPS/PPS 初始化参数集 (csd-0)
                setByteBuffer("csd-0", ByteBuffer.wrap(spsPpsData))
            }

            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            // 配置解码输出直通渲染至 surface
            decoder.configure(format, surface, null, 0)
            decoder.start()

            mediaCodec = decoder
            isConfigured = true
            Log.i(tag, "[H.264解码器] H.264 硬件解码器初始化成功 (${width}x${height})")
        } catch (e: Exception) {
            Log.e(tag, "[H.264解码器] 初始化硬件解码器失败", e)
            isConfigured = false
        }
    }

    /**
     * 从解码器输出缓冲区轮询取出解码完毕的视频帧并送显
     */
    private fun drainDecoderOutput() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            while (outputBufferIndex >= 0) {
                // 第二个参数 render = true 表示将解码像素立即渲染至绑定的 Surface
                codec.releaseOutputBuffer(outputBufferIndex, true)
                outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.e(tag, "[H.264解码器] 提取解码渲染输出发生异常", e)
        }
    }

    /**
     * 规范化 NAL 单元起始码：确保头部为 4 字节的 0x00 00 00 01 Annex-B 格式
     * @param data 原始 NALU 二进制数据
     * @return 拥有 4 字节标准起始码的数据数组
     */
    private fun ensure4ByteStartCode(data: ByteArray): ByteArray {
        // 若已经是 4 字节起始码 (00 00 00 01)，直接返回
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            return data
        }
        // 若为 3 字节起始码 (00 00 01)，前面补充一个 0x00 转换为 4 字节标准格式
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
     * 停止解码器并安全释放 MediaCodec 资源
     */
    @Synchronized
    fun stop() {
        if (!isConfigured && mediaCodec == null) return
        isConfigured = false

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(tag, "[H.264解码器] 停止解码器发生异常", e)
        }
        mediaCodec = null
        Log.i(tag, "[H.264解码器] H.264 解码器已停止并释放资源")
    }
}
