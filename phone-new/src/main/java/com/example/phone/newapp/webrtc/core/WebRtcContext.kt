package com.example.phone.newapp.webrtc.core

import android.content.Context
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

/**
 * WebRTC 全局上下文与 EglBase 管理器 (自包含独立实现)
 */
object WebRtcContext {
    val rootEglBase: EglBase by lazy {
        EglBase.create()
    }

    @Volatile
    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                System.loadLibrary("jingle_peerconnection_so")
            } catch (_: Throwable) {}

            val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
            isInitialized = true
        }
    }
}
