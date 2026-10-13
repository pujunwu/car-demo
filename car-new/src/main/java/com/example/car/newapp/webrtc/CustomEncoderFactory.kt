package com.example.car.newapp.webrtc

import android.util.Log
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory

/**
 * WebRTC 自定义编码器工厂类 (CustomEncoderFactory)
 *
 * 核心功能：
 * 1. 向 WebRTC 协议栈注册自定义 [CustomPassthroughEncoder]，替代默认编码器。
 * 2. 提前实例化直通编码器单例 [passthroughEncoder]，彻底消除 activeEncoder 为空的启动时序死锁。
 * 3. 规范声明支持 H.264 Baseline 与 High Profile (含 packetization-mode=1 与 level-asymmetry-allowed=1)，
 *    确保 SDP 媒体协商无缝匹配，并允许超大关键帧 (FU-A) 正常分包传输。
 */
class CustomEncoderFactory : VideoEncoderFactory {

    companion object {
        private const val TAG = "CustomEncoderFactory"
    }

    // 预先初始化单例直通编码器，杜绝外部采集推送帧时 activeEncoder 为 null 的死锁
    val passthroughEncoder = CustomPassthroughEncoder()

    val activeEncoder: CustomPassthroughEncoder
        get() = passthroughEncoder

    // 当底层编码器被 WebRTC 正式创建或绑定时通知外部
    var onEncoderCreated: ((CustomPassthroughEncoder) -> Unit)? = null

    override fun createEncoder(info: VideoCodecInfo?): VideoEncoder {
        Log.i(TAG, "[CustomEncoderFactory] WebRTC 底层请求创建编码器: name=${info?.name}, params=${info?.params}")
        onEncoderCreated?.invoke(passthroughEncoder)
        return passthroughEncoder
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

