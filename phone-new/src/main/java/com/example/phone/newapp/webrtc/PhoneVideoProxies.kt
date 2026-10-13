package com.example.phone.newapp.webrtc

import android.util.Log
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory

/**
 * 带有 C++ JNI createNative(J)J 兼容签名的 VideoDecoder 代理类
 *
 * 核心作用：
 * WebRTC C++ 层的 video_decoder_wrapper.cc 在接收 Java Decoder 时，
 * 会通过 env->GetMethodID(clazz, "createNative", "(J)J") 探测该 Java 对象。
 * 覆盖此接口方法并返回 0L，可防止 JNI 抛出 NoSuchMethodError 并避免引发 Check failed SIGABRT 崩溃。
 */
class PhoneVideoDecoderProxy(private val delegate: VideoDecoder) : VideoDecoder {

    override fun createNative(nativeVideoDecoderFactory: Long): Long {
        return 0L
    }

    override fun initDecode(settings: VideoDecoder.Settings?, callback: VideoDecoder.Callback?): VideoCodecStatus {
        return try {
            delegate.initDecode(settings, callback)
        } catch (e: UnsupportedOperationException) {
            VideoCodecStatus.FALLBACK_SOFTWARE
        } catch (t: Throwable) {
            Log.e("PhoneVideoDecoderProxy", "initDecode 异常: ${t.message}")
            VideoCodecStatus.ERROR
        }
    }

    override fun release(): VideoCodecStatus {
        return try {
            delegate.release()
        } catch (t: Throwable) {
            VideoCodecStatus.OK
        }
    }

    override fun decode(frame: EncodedImage?, info: VideoDecoder.DecodeInfo?): VideoCodecStatus {
        return try {
            delegate.decode(frame, info)
        } catch (e: UnsupportedOperationException) {
            VideoCodecStatus.FALLBACK_SOFTWARE
        } catch (t: Throwable) {
            Log.e("PhoneVideoDecoderProxy", "decode 异常: ${t.message}")
            VideoCodecStatus.ERROR
        }
    }

    override fun getImplementationName(): String {
        return try {
            delegate.implementationName
        } catch (t: Throwable) {
            "PhoneVideoDecoderProxy"
        }
    }
}

/**
 * WebRTC 原生解码器代理工厂类
 *
 * 核心职责：
 * 1. 拦截并包装 WebRTC 官方原生解码器，注入 [PhoneVideoDecoderProxy] 防御 JNI 崩溃。
 * 2. 规范声明对 H.264 (Baseline & High Profile) 的支持，确保与车机推流端 SDP 媒体协商 100% 成功。
 */
class PhoneProxyVideoDecoderFactory(private val delegate: VideoDecoderFactory) : VideoDecoderFactory {

    companion object {
        private const val TAG = "PhoneProxyVideoDecoder"
    }

    override fun createDecoder(info: VideoCodecInfo?): VideoDecoder? {
        Log.i(TAG, "[WebRTC原生解码] 请求创建解码器: codec=${info?.name}, params=${info?.params}")
        val decoder = delegate.createDecoder(info)
        if (decoder == null) {
            Log.e(TAG, "[WebRTC原生解码] 底层工厂未能创建解码器: ${info?.name} (返回 null)")
            return null
        }
        val implName = try { decoder.implementationName } catch (t: Throwable) { "Unknown" }
        Log.i(TAG, "[WebRTC原生解码] 成功创建底层解码器: $implName (${decoder.javaClass.simpleName})")
        if (decoder is org.webrtc.WrappedNativeVideoDecoder) {
            return decoder
        }
        return PhoneVideoDecoderProxy(decoder)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val codecs = delegate.supportedCodecs
        val h264Codecs = codecs.filter { it.name.equals("H264", ignoreCase = true) }
        Log.i(TAG, "[WebRTC原生解码] 底层支持解码格式: ${codecs.map { it.name }}, H264 数量: ${h264Codecs.size}")
        if (h264Codecs.isNotEmpty()) {
            return h264Codecs.toTypedArray()
        }

        // 如果底层未显式暴露完整的 H264 配置，补充标准 Profile 声明保证 SDP 协商锁定 H264
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
