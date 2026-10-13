package com.example.phone

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView

/**
 * 自适应视频宽高比的 SurfaceView 控件
 *
 * 核心功能：
 * 根据输入的真实视频宽度和高度（[videoWidth] x [videoHeight]），
 * 自动计算并保持画面宽高比，防止车机屏幕投射到手机上时发生拉伸或变形。
 * 支持两种画面适配模式：等比居中适应（FIT）与全屏居中裁切（CROP）。
 */
class AspectRatioSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {

    init {
        try {
            // 设置 Surface 像素格式为 32 位 RGBA_8888 色彩保真
            holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
        } catch (_: Exception) {}
    }

    /**
     * 画面缩放适配模式枚举
     */
    enum class ScaleType {
        FIT,   // 保持原始宽高比，居中显示，留黑边（Letterbox）
        CROP   // 保持原始宽高比，充满容器，裁剪超出部分（Fill & Crop）
    }

    // 视频源的物理像素宽与高
    private var videoWidth = 0
    private var videoHeight = 0
    // 当前缩放适配模式（默认为 FIT 全显）
    private var scaleType = ScaleType.FIT

    /**
     * 更新视频真实像素尺寸
     * @param width 视频宽（例如 1920 或 1280）
     * @param height 视频高（例如 1080 或 720）
     */
    fun setVideoSize(width: Int, height: Int) {
        if (width > 0 && height > 0 && (this.videoWidth != width || this.videoHeight != height)) {
            this.videoWidth = width
            this.videoHeight = height
            try {
                // 固定 SurfaceView 缓冲区大小，优化硬件加速与 SurfaceFlinger 合成效率
                holder.setFixedSize(width, height)
            } catch (_: Exception) {}
            requestLayout()
        }
    }

    /**
     * 切换画面缩放模式
     * @param type 缩放模式（FIT 或 CROP）
     */
    fun setScaleType(type: ScaleType) {
        if (this.scaleType != type) {
            this.scaleType = type
            requestLayout()
        }
    }

    /**
     * 重写测量逻辑，根据宽高比精准计算布局目标尺寸
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        // 若尚未获得有效视频尺寸，走系统默认测量流程
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
                // FIT 模式：等比完整显示全部画面
                if (aspectVideo > aspectView) {
                    targetHeight = (width / aspectVideo).toInt()
                } else {
                    targetWidth = (height * aspectVideo).toInt()
                }
            }
            ScaleType.CROP -> {
                // CROP 模式：充满整个视口，裁切超出视口的多余边缘
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
