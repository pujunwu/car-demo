package com.example.car.webrtc.encode

import org.webrtc.VideoCodecInfo
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import kotlin.collections.emptyMap

/**
 * 自定义编码器工厂
 */
class CustomEncoderFactory : VideoEncoderFactory {

    var activeEncoder: CustomPassthroughEncoder? = null
        private set

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder {
        return CustomPassthroughEncoder().also {
            activeEncoder = it
        }
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        // 声明支持 H264（SDP 协商以此为准）
        return arrayOf(
            VideoCodecInfo("H264", emptyMap(),emptyList<String>())
        )
    }
}