package com.example.phone.newapp.protocol

import com.google.gson.Gson

/**
 * WebRTC 信令交互数据包实体类 (自包含独立实现)
 */
data class SignalingMessage(
    val type: String,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null
) {
    companion object {
        const val TYPE_OFFER = "offer"
        const val TYPE_ANSWER = "answer"
        const val TYPE_ICE_CANDIDATE = "candidate"
        const val TYPE_DISCONNECT = "disconnect"
        const val TYPE_REQUEST_KEY_FRAME = "request_key_frame"

        private val gson = Gson()

        fun fromJson(json: String): SignalingMessage? {
            return try {
                gson.fromJson(json, SignalingMessage::class.java)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun toJson(): String = gson.toJson(this)
}
