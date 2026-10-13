package com.example.webrtc.core

import android.content.Context
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global WebRTC Context and EglBase manager.
 */
object WebRtcContext {

    private val isInitialized = AtomicBoolean(false)
    val rootEglBase: EglBase by lazy {
        EglBase.create()
    }

    fun initialize(context: Context) {
        if (isInitialized.getAndSet(true)) return

        val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions()

        PeerConnectionFactory.initialize(options)
    }
}
