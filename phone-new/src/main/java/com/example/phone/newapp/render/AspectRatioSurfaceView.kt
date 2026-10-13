package com.example.phone.newapp.render

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView

/**
 * 自适应视频宽高比的 SurfaceView 控件 (phone 模块自定义渲染组件移植与优化)
 *
 * 核心功能：
 * 1. 根据输入的车机端真实视频宽高比，动态精确计算并在 onMeasure 中调整显示大小。
 * 2. 避免投屏画面在手机屏幕上被无序拉伸、压缩或变形。
 * 3. 支持两种自适应缩放模式：
 *    - [ScaleType.FIT]：等比完整显示，居中留黑边 (Letterbox)，适合文字与细节查看。
 *    - [ScaleType.CROP]：充满屏幕视口，居中等比裁切多余黑边，适合全屏沉浸式观看。
 */
class AspectRatioSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {

    init {
        try {
            // 设置底层 Surface 像素格式为 32 位 RGBA_8888 色彩深度
            holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
        } catch (_: Exception) {}
    }

    enum class ScaleType {
        FIT,   // 保持原始宽高比，居中完整呈现，不裁剪
        CROP   // 保持原始宽高比，充满容器，适度裁切
    }

    private var videoWidth = 0
    private var videoHeight = 0
    private var scaleType = ScaleType.FIT

    /**
     * 更新当前接收到的视频帧真实像素尺寸
     *
     * @param width 视频宽（例如 1280）
     * @param height 视频高（例如 720）
     */
    fun setVideoSize(width: Int, height: Int) {
        if (width > 0 && height > 0 && (this.videoWidth != width || this.videoHeight != height)) {
            this.videoWidth = width
            this.videoHeight = height
            try {
                // 固定 Surface 渲染缓冲区大小，提高硬件合成器渲染效率
                holder.setFixedSize(width, height)
            } catch (_: Exception) {}
            requestLayout()
        }
    }

    /**
     * 切换画面缩放模式
     */
    fun setScaleType(type: ScaleType) {
        if (this.scaleType != type) {
            this.scaleType = type
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val aspectVideo = videoWidth.toFloat() / videoHeight.toFloat()
        val aspectView = width.toFloat() / height.toFloat()

        var targetWidth = width
        var targetHeight = height

        when (scaleType) {
            ScaleType.FIT -> {
                if (aspectVideo > aspectView) {
                    targetHeight = (width / aspectVideo).toInt()
                } else {
                    targetWidth = (height * aspectVideo).toInt()
                }
            }
            ScaleType.CROP -> {
                if (aspectVideo > aspectView) {
                    targetWidth = (height * aspectVideo).toInt()
                } else {
                    targetHeight = (width / aspectVideo).toInt()
                }
            }
        }

        setMeasuredDimension(targetWidth, targetHeight)
    }
}
