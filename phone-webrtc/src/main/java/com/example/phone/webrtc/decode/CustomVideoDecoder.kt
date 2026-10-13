package com.example.phone.webrtc.decode

import org.webrtc.EncodedImage
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoDecoder

/**
 * 自定义 VideoDecoder 拦截码流
 */
class CustomVideoDecoder(
    private val onFrameReceived: (data: ByteArray, isKeyFrame: Boolean, width: Int, height: Int, timestampNs: Long) -> Unit
) : VideoDecoder {

    private var decodeCallback: VideoDecoder.Callback? = null

    override fun initDecode(settings: VideoDecoder.Settings, callback: VideoDecoder.Callback): VideoCodecStatus {
        this.decodeCallback = callback
        return VideoCodecStatus.OK
    }

    override fun release(): VideoCodecStatus {
        decodeCallback = null
        return VideoCodecStatus.OK
    }

    override fun decode(frame: EncodedImage, decodeInfo: VideoDecoder.DecodeInfo): VideoCodecStatus {
        val buffer = frame.buffer ?: return VideoCodecStatus.ERR_PARAMETER

        // 1. 提取完整的编码字节数组（WebRTC 已自动帮你组装好的单帧）
        val frameData = ByteArray(buffer.remaining())
        buffer.get(frameData)

        val isKeyFrame = frame.frameType == EncodedImage.FrameType.VideoFrameKey

        // 2. 回调通知你的外部模块（如硬件 MediaCodec 或自定义播放器）
        onFrameReceived(
            frameData,
            isKeyFrame,
            frame.encodedWidth,
            frame.encodedHeight,
            frame.captureTimeNs
        )

        // ================= 渲染分支 =================
        // 分支 A：自己渲染（外部直接将画面解码到 Surface 显示）
        return VideoCodecStatus.OK

        /*
        // 分支 B：若外部解码后想交回给 WebRTC 渲染：
        val decodedVideoFrame: VideoFrame = ...
        decodeCallback?.onDecodedFrame(decodedVideoFrame, null, null)
        return VideoCodecStatus.OK
        */
    }

//    override fun getPrefersLateDecoding(): Boolean = true

    override fun getImplementationName(): String = "CustomVideoDecoder"
}