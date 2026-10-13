package com.example.phone.newapp

import android.app.Application
import android.util.Log
import com.example.phone.newapp.webrtc.core.WebRtcContext

/**
 * 手机端全局 Application 类 (phone-new 独立应用入口)
 */
class PhoneApplication : Application() {

    companion object {
        private const val TAG = "PhoneNewApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PhoneApplication 启动，初始化 WebRTC 环境...")
        try {
            WebRtcContext.initialize(this)
        } catch (t: Throwable) {
            Log.e(TAG, "WebRtcContext 初始化失败", t)
        }
    }
}
