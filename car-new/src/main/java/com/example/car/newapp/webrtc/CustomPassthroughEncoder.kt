package com.example.car.newapp.webrtc

import android.util.Log
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * WebRTC 自定义直通编码器 (CustomPassthroughEncoder)
 *
 * 核心设计：
 * 1. 作为 WebRTC [VideoEncoder] 的自定义实现接入 WebRTC 媒体推流管线。
 * 2. 覆盖 [createNative] 接口返回 0L，满足 WebRTC C++ 层 JNI 探测要求，彻底规避崩溃。
 * 3. 接收由外部 [H264Encoder]（Android MediaCodec 硬件加速）生成的 H.264 Annex-B 关键帧与预测帧，
 *    包装为 [EncodedImage] 后直接投递给 WebRTC 的 RTP 封包发送器 (encodeCallback.onEncodedFrame)。
 * 4. 监听 WebRTC 拥塞控制模块的 [setRateAllocation] 码率分配建议，反向驱动硬件编码器动态升降码率。
 */
class CustomPassthroughEncoder : VideoEncoder {

    companion object {
        private const val TAG = "CustomPassthroughEncoder"
    }

    private var encodeCallback: VideoEncoder.Callback? = null
    private var width: Int = 1280
    private var height: Int = 720

    // 动态码率变更回调，通知外部硬件编码器 (MediaCodec) 调整目标码率 (单位: bps)
    var onBitrateAllocationChanged: ((newBitrateBps: Int) -> Unit)? = null

    /**
     * WebRTC C++ 探测 JNI 签名方法，返回 0L 防止引发 NoSuchMethodError 或 SIGABRT
     */
    override fun createNative(nativeVideoEncoderFactory: Long): Long {
        return 0L
    }

    // 当 WebRTC 完成 initEncode 时触发，用于立即注入首帧关键帧
    var onEncoderInitialized: (() -> Unit)? = null

    override fun initEncode(settings: VideoEncoder.Settings?, callback: VideoEncoder.Callback?): VideoCodecStatus {
        this.encodeCallback = callback
        if (settings != null) {
            this.width = settings.width
            this.height = settings.height
        }
        pendingFrames.clear()
        Log.i(TAG, "[WebRTC直通编码器] initEncode 初始化完成: ${width}x${height}, 初始目标帧率: ${settings?.maxFramerate ?: 30}fps")
        onEncoderInitialized?.invoke()
        return VideoCodecStatus.OK
    }

    override fun release(): VideoCodecStatus {
        Log.i(TAG, "[WebRTC直通编码器] release 释放编码器资源")
        encodeCallback = null
        pendingFrames.clear()
        return VideoCodecStatus.OK
    }

    data class PendingEncodedFrame(
        val data: ByteArray,
        val isKeyFrame: Boolean,
        val width: Int,
        val height: Int,
        val timestampNs: Long
    )

    private val pendingFrames = java.util.concurrent.LinkedBlockingQueue<PendingEncodedFrame>(15)

    fun isReady(): Boolean = encodeCallback != null

    fun enqueueEncodedFrame(frame: PendingEncodedFrame) {
        if (frame.isKeyFrame) {
            // 关键帧到来，清空旧的未发出预测帧，确保新关键帧第一时间被消费
            pendingFrames.clear()
        }
        if (!pendingFrames.offer(frame)) {
            pendingFrames.poll()
            pendingFrames.offer(frame)
        }
    }

    override fun encode(frame: VideoFrame?, encodeInfo: VideoEncoder.EncodeInfo?): VideoCodecStatus {
        frame ?: return VideoCodecStatus.OK
        val callback = encodeCallback
        if (callback == null) {
            val count = ++nullCallbackWarnCount
            if (count % 30 == 1) {
                Log.w(TAG, "[WebRTC直通编码器] encode 驱动到达但 encodeCallback 尚未就绪 (第 $count 次)")
            }
            return VideoCodecStatus.OK
        }
        val pending = pendingFrames.poll() ?: return VideoCodecStatus.OK

        try {
            val buffer = ByteBuffer.allocateDirect(pending.data.size).apply {
                put(pending.data)
                flip()
            }

            val frameType = if (pending.isKeyFrame) {
                EncodedImage.FrameType.VideoFrameKey
            } else {
                EncodedImage.FrameType.VideoFrameDelta
            }

            // 核心关键：EncodedImage 的 captureTimeNs 必须与 WebRTC VideoStreamEncoder 传入的 frame.timestampNs 严格一致！
            // 否则 WebRTC C++ 层会因找不到 incoming_frame_info_ 记录而全量丢弃数据包！
            val encodedImage = EncodedImage.builder()
                .setBuffer(buffer) {
                    // DirectBuffer 释放钩子
                }
                .setEncodedWidth(pending.width)
                .setEncodedHeight(pending.height)
                .setCaptureTimeNs(frame.timestampNs)
                .setFrameType(frameType)
                .setRotation(0)
                .createEncodedImage()

            val codecSpecificInfo = VideoEncoder.CodecSpecificInfo()
            // 提交至 WebRTC RTP 封包器
            callback.onEncodedFrame(encodedImage, codecSpecificInfo)

            totalFedFrames++
            intervalFedFrames++

            if (pending.isKeyFrame) {
                totalFedKeyFrames++
                Log.i(TAG, "[WebRTC直通编码器] ★★★ 成功将关键帧(IDR)通过 WebRTC 封包下发: 大小=${pending.data.size} 字节, 分辨率=${pending.width}x${pending.height}, pts=${frame.timestampNs} ★★★")
            }

            val nowMs = System.currentTimeMillis()
            if (nowMs - lastFedStatsTimeMs >= 2000) {
                if (lastFedStatsTimeMs > 0) {
                    val fps = (intervalFedFrames * 1000.0f / (nowMs - lastFedStatsTimeMs)).toInt()
                    Log.i(TAG, "[WebRTC直通编码器诊断] WebRTC 编码发送正常: 累计已送=$totalFedFrames 帧, 当前帧率=$fps FPS, 累计关键帧=$totalFedKeyFrames")
                }
                intervalFedFrames = 0
                lastFedStatsTimeMs = nowMs
            }
            return VideoCodecStatus.OK
        } catch (e: Exception) {
            Log.e(TAG, "[WebRTC直通编码器] encode 投包异常", e)
            return VideoCodecStatus.ERROR
        }
    }

    private var totalFedFrames = 0L
    private var totalFedKeyFrames = 0L
    private var lastFedStatsTimeMs = 0L
    private var intervalFedFrames = 0
    private var nullCallbackWarnCount = 0

    /**
     * 兼容接口：供外部直接入队并等待同步驱动
     */
    fun feedExternalEncodedFrame(
        encodedBytes: ByteArray,
        isKeyFrame: Boolean,
        frameWidth: Int = width,
        frameHeight: Int = height,
        timestampNs: Long = System.nanoTime()
    ) {
        enqueueEncodedFrame(
            PendingEncodedFrame(
                data = encodedBytes,
                isKeyFrame = isKeyFrame,
                width = frameWidth,
                height = frameHeight,
                timestampNs = timestampNs
            )
        )
    }

    /**
     * WebRTC GCC 拥塞控制引擎动态调整码率分配
     */
    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation?, framerate: Int): VideoCodecStatus {
        val totalBps = allocation?.sum ?: 0
        if (totalBps > 0) {
            Log.d(TAG, "[WebRTC直通编码器] 收到 WebRTC 网络建议码率: ${totalBps / 1000} kbps, 建议帧率: $framerate fps")
            onBitrateAllocationChanged?.invoke(totalBps)
        }
        return VideoCodecStatus.OK
    }

    override fun getScalingSettings(): VideoEncoder.ScalingSettings {
        return VideoEncoder.ScalingSettings.OFF
    }

    override fun getImplementationName(): String = "CustomPassthroughEncoder"
}
