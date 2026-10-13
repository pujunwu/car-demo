package com.example.car.webrtc.encode

import org.webrtc.EncodedImage
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * 自定义编码器
 */
class CustomPassthroughEncoder : VideoEncoder {

    private var encodeCallback: VideoEncoder.Callback? = null
    private var width: Int = 1280
    private var height: Int = 720

    override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
        this.encodeCallback = callback
        this.width = settings.width
        this.height = settings.height
        return VideoCodecStatus.OK
    }

    override fun release(): VideoCodecStatus {
        encodeCallback = null
        return VideoCodecStatus.OK
    }

    override fun encode(frame: VideoFrame, encodeInfo: VideoEncoder.EncodeInfo): VideoCodecStatus {
        // 数据是由外部编好的，此方法通常无需处理原始 VideoFrame
        return VideoCodecStatus.OK
    }

    /**
     * 核心方法：供外部编码模块直接调用，把编码后的数据帧喂入 WebRTC 管道
     *
     * @param encodedBytes 编码后的字节流（如 H.264 Annex-B 格式，带 00 00 00 01 起始码）
     * @param isKeyFrame   是否为关键帧（IDR 帧必须拼接 SPS 和 PPS）
     */
    fun feedExternalEncodedFrame(encodedBytes: ByteArray, isKeyFrame: Boolean) {
        val callback = encodeCallback ?: return

        // 必须分配 DirectByteBuffer
        val buffer = ByteBuffer.allocateDirect(encodedBytes.size).apply {
            put(encodedBytes)
            flip()
        }

        val captureTimeNs = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis())

        val frameType = if (isKeyFrame) {
            EncodedImage.FrameType.VideoFrameKey
        } else {
            EncodedImage.FrameType.VideoFrameDelta
        }

        // 使用 WebRTC M125 的 Builder 构造 EncodedImage
        val encodedImage = EncodedImage.builder()
            .setBuffer(buffer) {
                // releaseCallback: 内存使用完毕后的回收动作（如有需要）
            }
            .setEncodedWidth(width)
            .setEncodedHeight(height)
            .setCaptureTimeNs(captureTimeNs)
            .setFrameType(frameType)
            .setRotation(0)
            .createEncodedImage()

        val codecSpecificInfo = VideoEncoder.CodecSpecificInfo()
        callback.onEncodedFrame(encodedImage, codecSpecificInfo)
    }

    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation, framerate: Int): VideoCodecStatus {
        // WebRTC 动态网络反馈建议码率，可用于通知外部编码器动态升降码率
        return VideoCodecStatus.OK
    }

    override fun getScalingSettings(): VideoEncoder.ScalingSettings {
        return VideoEncoder.ScalingSettings.OFF
    }

    override fun getImplementationName(): String = "CustomPassthroughEncoder"
}