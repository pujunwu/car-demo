package com.example.phone.webrtc.decode

import org.webrtc.VideoCodecInfo
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory

/**
 * 自定义拦截工厂
 */
class CustomDecoderFactory(
    private val onFrameReceived: (data: ByteArray, isKeyFrame: Boolean, width: Int, height: Int, timestampNs: Long) -> Unit
) : VideoDecoderFactory {

    override fun createDecoder(info: VideoCodecInfo): VideoDecoder {
        return CustomVideoDecoder(onFrameReceived)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        return arrayOf(
            VideoCodecInfo("H264", emptyMap(),emptyList<String>())
        )
    }
}