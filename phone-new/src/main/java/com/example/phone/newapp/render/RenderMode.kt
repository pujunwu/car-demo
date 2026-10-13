package com.example.phone.newapp.render

/**
 * 手机端画面渲染模式枚举
 */
enum class RenderMode(val title: String, val description: String) {
    /**
     * 自定义硬解模式：
     * WebRTC 拦截原始 H.264 码流 -> 自定义 MediaCodec 硬件解码 -> AspectRatioSurfaceView
     */
    CUSTOM(
        title = "自定义硬解",
        description = "拦截 H.264 码流 + 自定义 MediaCodec 硬解直出 Surface"
    ),

    /**
     * WebRTC 内置模式：
     * WebRTC 官方 DefaultVideoDecoderFactory 解码 -> VideoTrack -> 官方 SurfaceViewRenderer
     */
    WEBRTC_BUILTIN(
        title = "WebRTC原生",
        description = "WebRTC 官方解码器 + 原生 SurfaceViewRenderer 控件渲染"
    )
}
