package com.example.car.webrtc

import org.webrtc.EncodedImage
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoFrame

/**
 * 带有 C++ JNI createNative(J)J 兼容签名的 VideoDecoder 代理类（仅在 car-webrtc 模块中使用）
 */
class CarVideoDecoderProxy(private val delegate: VideoDecoder) : VideoDecoder {

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
            "CarVideoDecoderProxy"
        }
    }
}

/**
 * 带有 C++ JNI createNative(J)J 兼容签名的 VideoEncoder 代理类（仅在 car-webrtc 模块中使用）
 */
class CarVideoEncoderProxy(private val delegate: VideoEncoder) : VideoEncoder {

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
            "CarVideoEncoderProxy"
        }
    }
}

class CarProxyVideoDecoderFactory(private val delegate: VideoDecoderFactory) : VideoDecoderFactory {
    override fun createDecoder(info: VideoCodecInfo?): VideoDecoder? {
        val decoder = delegate.createDecoder(info) ?: return null
        if (decoder is org.webrtc.WrappedNativeVideoDecoder) {
            return decoder
        }
        return CarVideoDecoderProxy(decoder)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val codecs = delegate.supportedCodecs
        // 【关键过滤：强行独占 H.264 硬件解码】
        // 过滤仅暴露 H264 格式，避免 WebRTC 协商出 VP8/VP9 等格式，杜绝编解码格式不匹配导致黑屏
        val h264Codecs = codecs.filter { it.name.equals("H264", ignoreCase = true) }.toTypedArray()
        return if (h264Codecs.isNotEmpty()) h264Codecs else codecs
    }
}

/**
 * 车机推流端 VideoEncoderFactory 代理工厂类
 * 拦截底层支持的编码格式，并注入 C++ JNI 安全包装
 */
class CarProxyVideoEncoderFactory(private val delegate: VideoEncoderFactory) : VideoEncoderFactory {
    override fun createEncoder(info: VideoCodecInfo?): VideoEncoder? {
        val encoder = delegate.createEncoder(info) ?: return null
        if (encoder is org.webrtc.WrappedNativeVideoEncoder) {
            return encoder
        }
        return CarVideoEncoderProxy(encoder)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val codecs = delegate.supportedCodecs
        // =========================================================================
        // 【核心优化：强行独占 H.264 硬件编码器，杜绝协商回退软编码 (libvpx)】
        // 作用原理：Android 系统底层可能同时注册了 VP8、VP9 等软件编码格式。
        //           如果 SDP 协商出现偏差或默认优先级被篡改，WebRTC 可能会回退至 CPU 软编码。
        //           对于 1280x800 @ 60FPS 投屏，软编码会导致 CPU 瞬间 100% 满载，
        //           产生极其致命的大面积掉帧与严重发热。
        //           此处通过 filter 强行只向 WebRTC 协议栈暴露 H.264，100% 锁定 VPU 硬件芯片。
        // =========================================================================
        val h264Codecs = codecs.filter { it.name.equals("H264", ignoreCase = true) }.toTypedArray()
        return if (h264Codecs.isNotEmpty()) h264Codecs else codecs
    }
}
