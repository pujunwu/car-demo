package com.example.car.newapp

import android.app.Application
import android.util.Log
import com.example.car.newapp.webrtc.core.WebRtcContext

/**
 * 车机端全局 Application 类 (car-new 独立应用入口)
 *
 * 核心功能：
 * 1. 在应用启动时预先初始化 WebRTC 全局上下文环境与 EglBase。
 * 2. 注入全局未捕获异常防护，防御车机特定定制 ROM 兼容性崩溃。
 */
class CarApplication : Application() {

    companion object {
        private const val TAG = "CarNewApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CarApplication 启动，初始化 WebRTC 环境...")
        try {
            WebRtcContext.initialize(this)
        } catch (t: Throwable) {
            Log.e(TAG, "WebRtcContext 初始化失败", t)
        }

        // 全局未捕获异常防护机制
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTraceStr = Log.getStackTraceString(throwable)
            if (stackTraceStr.contains("bluetooth_second") || stackTraceStr.contains("BluetoothProfileConnector")) {
                Log.e(TAG, "[车机崩溃防护] 拦截车机定制系统底层框架异常: ${throwable.message}")
                return@setDefaultUncaughtExceptionHandler
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
