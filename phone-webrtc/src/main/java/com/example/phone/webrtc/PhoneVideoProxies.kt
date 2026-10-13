package com.example.phone.webrtc

import org.webrtc.EncodedImage
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoFrame

/**
 * 带有 C++ JNI createNative(J)J 兼容签名的 VideoDecoder 代理类
 */
class PhoneVideoDecoderProxy(private val delegate: VideoDecoder) : VideoDecoder {

    /**
     * 关键 JNI 映射方法：
     * WebRTC C++ 层的 video_decoder_wrapper.cc 在接收 Java Decoder 时，
     * 会通过 env->GetMethodID(clazz, "createNative", "(J)J") 探测该 Java 对象。
     * 覆盖此接口方法并返回 0L，可防止 JNI 抛出 NoSuchMethodError 并避免引发 Check failed SIGABRT 崩溃。
     */
    override fun createNative(nativeVideoDecoderFactory: Long): Long {
        return 0L
    }

    override fun initDecode(settings: VideoDecoder.Settings?, callback: VideoDecoder.Callback?): VideoCodecStatus {
        return try {
            delegate.initDecode(settings, callback)
        } catch (e: UnsupportedOperationException) {
            VideoCodecStatus.FALLBACK_SOFTWARE
        } catch (t: Throwable) {
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
 * 带有 C++ JNI createNative(J)J 兼容签名的 VideoEncoder 代理类
 */
class PhoneVideoEncoderProxy(private val delegate: VideoEncoder) : VideoEncoder {

    override fun createNative(nativeVideoEncoderFactory: Long): Long {
        return 0L
    }

    override fun initEncode(settings: VideoEncoder.Settings?, callback: VideoEncoder.Callback?): VideoCodecStatus {
        return try {
            delegate.initEncode(settings, callback)
        } catch (e: UnsupportedOperationException) {
            VideoCodecStatus.FALLBACK_SOFTWARE
        } catch (t: Throwable) {
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

    override fun encode(frame: VideoFrame?, info: VideoEncoder.EncodeInfo?): VideoCodecStatus {
        return try {
            delegate.encode(frame, info)
        } catch (e: UnsupportedOperationException) {
            VideoCodecStatus.FALLBACK_SOFTWARE
        } catch (t: Throwable) {
            VideoCodecStatus.ERROR
        }
    }

    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation?, framerate: Int): VideoCodecStatus {
        return try {
            delegate.setRateAllocation(allocation, framerate)
        } catch (t: Throwable) {
            VideoCodecStatus.ERROR
        }
    }

    override fun getScalingSettings(): VideoEncoder.ScalingSettings {
        return try {
            delegate.scalingSettings
        } catch (t: Throwable) {
            VideoEncoder.ScalingSettings.OFF
        }
    }

    override fun getImplementationName(): String {
        return try {
            delegate.implementationName
        } catch (t: Throwable) {
            "PhoneVideoEncoderProxy"
        }
    }
}

class PhoneProxyVideoDecoderFactory(private val delegate: VideoDecoderFactory) : VideoDecoderFactory {
    override fun createDecoder(info: VideoCodecInfo?): VideoDecoder? {
        android.util.Log.i("PhoneProxyVideoDecoder", "[手机解码诊断] 请求创建解码器: codec=${info?.name}, params=${info?.params}")
        val decoder = delegate.createDecoder(info)
        if (decoder == null) {
            android.util.Log.e("PhoneProxyVideoDecoder", "[手机解码诊断] 底层工厂未能创建解码器: ${info?.name} (返回 null)")
            return null
        }
        val implName = try { decoder.implementationName } catch (t: Throwable) { "Unknown" }
        android.util.Log.i("PhoneProxyVideoDecoder", "[手机解码诊断] 成功创建底层解码器: $implName (${decoder.javaClass.simpleName})")
        if (decoder is org.webrtc.WrappedNativeVideoDecoder) {
            return decoder
        }
        return PhoneVideoDecoderProxy(decoder)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val codecs = delegate.supportedCodecs
        // 【关键过滤：强行独占 H.264 硬件解码】
        // 手机端强过滤只保留 H264 格式，杜绝 SDP 协商偏差选中使用 CPU 软解的 VP8/VP9，确保 100% 走芯片硬件解码
        val h264Codecs = codecs.filter { it.name.equals("H264", ignoreCase = true) }.toTypedArray()
        android.util.Log.i("PhoneProxyVideoDecoder", "[手机解码诊断] 支持的解码格式列表: ${codecs.map { it.name }}, 其中 H264 数量: ${h264Codecs.size}")
        return if (h264Codecs.isNotEmpty()) h264Codecs else codecs
    }
}

class PhoneProxyVideoEncoderFactory(private val delegate: VideoEncoderFactory) : VideoEncoderFactory {
    override fun createEncoder(info: VideoCodecInfo?): VideoEncoder? {
        val encoder = delegate.createEncoder(info) ?: return null
        if (encoder is org.webrtc.WrappedNativeVideoEncoder) {
            return encoder
        }
        return PhoneVideoEncoderProxy(encoder)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val codecs = delegate.supportedCodecs
        // 手机端作为可能存在的反向控制或预留推流，同样只暴露 H264 硬件编码
        val h264Codecs = codecs.filter { it.name.equals("H264", ignoreCase = true) }.toTypedArray()
        return if (h264Codecs.isNotEmpty()) h264Codecs else codecs
    }
}
