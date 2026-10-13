package com.example.phone.newapp.webrtc

import android.util.Log
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoDecoder

/**
 * WebRTC 自定义码流拦截解码器 (CustomVideoDecoder)
 *
 * 核心设计与职责：
 * 1. 作为 WebRTC 接收端的解码器实现接入 WebRTC 管道。
 * 2. 覆盖 [createNative] 接口返回 0L，满足 WebRTC C++ 层 JNI 签名探测，防止底层崩溃。
 * 3. 当 WebRTC 传输层接收到 RTP 分包并重组出完整的单帧 [EncodedImage] 后，
 *    在 [decode] 方法中直接提取原始 H.264 Annex-B 字节数组。
 * 4. 将码流直通回调给外部自定义解码器 [H264Decoder]，由其直接解码并渲染上屏。
 * 5. 方法返回 [VideoCodecStatus.OK]，切断 WebRTC 内部着色器渲染路径，实现完全自定义的渲染接管。
 */
class CustomVideoDecoder(
    private val onFrameReceived: (data: ByteArray, isKeyFrame: Boolean, width: Int, height: Int, timestampNs: Long) -> Unit
) : VideoDecoder {

    companion object {
        private const val TAG = "CustomVideoDecoder"
    }

    private var totalInterceptedFrames = 0L
    private var totalInterceptedKeyFrames = 0L
    private var lastInterceptStatsMs = 0L
    private var intervalInterceptFrames = 0

    /**
     * WebRTC C++ 探测 JNI 签名方法，返回 0L 防止引发 NoSuchMethodError 或 SIGABRT
     */
    override fun createNative(nativeVideoDecoderFactory: Long): Long {
        return 0L
    }

    override fun initDecode(settings: VideoDecoder.Settings?, callback: VideoDecoder.Callback?): VideoCodecStatus {
        if (settings != null) {
            Log.i(TAG, "[手机拦截解码] WebRTC initDecode 接收端解码管线已就绪: ${settings.width}x${settings.height}")
        }
        return VideoCodecStatus.OK
    }

    override fun release(): VideoCodecStatus {
        Log.i(TAG, "[手机拦截解码] WebRTC release 释放拦截解码器")
        return VideoCodecStatus.OK
    }

    override fun decode(frame: EncodedImage?, decodeInfo: VideoDecoder.DecodeInfo?): VideoCodecStatus {
        frame ?: return VideoCodecStatus.ERR_PARAMETER
        val originalBuffer = frame.buffer ?: return VideoCodecStatus.ERR_PARAMETER

        // 1. 提取完整的 H.264 编码字节数组（WebRTC 已在底层完成 RTP 解包、JitterBuffer 排队与帧重组）
        val buffer = originalBuffer.duplicate()
        val frameData = ByteArray(buffer.remaining())
        buffer.get(frameData)

        val isKeyFrame = frame.frameType == EncodedImage.FrameType.VideoFrameKey

        totalInterceptedFrames++
        intervalInterceptFrames++

        if (isKeyFrame) {
            totalInterceptedKeyFrames++
            Log.i(TAG, "[手机拦截解码] 拦截到关键帧(IDR): 大小=${frameData.size} 字节, 分辨率=${frame.encodedWidth}x${frame.encodedHeight}, 累计I帧=$totalInterceptedKeyFrames, ptsNs=${frame.captureTimeNs}")
        }

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastInterceptStatsMs >= 2000) {
            if (lastInterceptStatsMs > 0) {
                val fps = (intervalInterceptFrames * 1000.0f / (nowMs - lastInterceptStatsMs)).toInt()
                Log.i(TAG, "[手机拦截解码诊断] 拦截接收正常: 累计已拦截=$totalInterceptedFrames 帧, 当前帧率=$fps FPS, 累计关键帧=$totalInterceptedKeyFrames 帧")
            }
            intervalInterceptFrames = 0
            lastInterceptStatsMs = nowMs
        }

        // 2. 回调通知自定义渲染管线 (MediaCodec -> AspectRatioSurfaceView)
        onFrameReceived(
            frameData,
            isKeyFrame,
            frame.encodedWidth,
            frame.encodedHeight,
            frame.captureTimeNs
        )

        // 3. 自定义渲染模式：返回 OK，无需交由 WebRTC 默认着色器渲染
        return VideoCodecStatus.OK
    }

    override fun getImplementationName(): String = "CustomVideoDecoder"
}
