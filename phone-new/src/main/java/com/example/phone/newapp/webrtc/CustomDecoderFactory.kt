package com.example.phone.newapp.webrtc

import android.util.Log
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory

/**
 * WebRTC 自定义解码器工厂类 (CustomDecoderFactory)
 *
 * 核心功能：
 * 1. 注册并提供自定义的 [CustomVideoDecoder] 拦截实例。
 * 2. 声明精准支持 H.264 Baseline 与 High Profile (含 packetization-mode=1 与 level-asymmetry-allowed=1)，
 *    确保两端 SDP 协商锁定 H.264 格式并匹配车机端编码参数。
 */
class CustomDecoderFactory(
    private val onFrameReceived: (data: ByteArray, isKeyFrame: Boolean, width: Int, height: Int, timestampNs: Long) -> Unit
) : VideoDecoderFactory {

    companion object {
        private const val TAG = "CustomDecoderFactory"
    }

    override fun createDecoder(info: VideoCodecInfo?): VideoDecoder {
        Log.i(TAG, "[CustomDecoderFactory] WebRTC 底层请求创建解码器: name=${info?.name}, params=${info?.params}")
        return CustomVideoDecoder(onFrameReceived)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val baselineParams = mapOf(
            "level-asymmetry-allowed" to "1",
            "packetization-mode" to "1",
            "profile-level-id" to "42e01f"
        )
        val highProfileParams = mapOf(
            "level-asymmetry-allowed" to "1",
            "packetization-mode" to "1",
            "profile-level-id" to "640c1f"
        )
        return arrayOf(
            VideoCodecInfo("H264", baselineParams, emptyList()),
            VideoCodecInfo("H264", highProfileParams, emptyList()),
            VideoCodecInfo("H264", emptyMap(), emptyList())
        )
    }
}

