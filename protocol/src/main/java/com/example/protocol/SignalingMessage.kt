package com.example.protocol

import com.google.gson.Gson

/**
 * WebRTC Signaling Protocol Message Data Model.
 */
data class SignalingMessage(
    val type: String,
    val sdp: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val candidate: String? = null
) {
    companion object {
        const val TYPE_OFFER = "OFFER"
        const val TYPE_ANSWER = "ANSWER"
        const val TYPE_ICE_CANDIDATE = "ICE_CANDIDATE"
        const val TYPE_DISCONNECT = "DISCONNECT"
        const val TYPE_PING = "PING"
        const val TYPE_PONG = "PONG"

        private val gson = Gson()

        fun fromJson(json: String): SignalingMessage? {
            return try {
                gson.fromJson(json, SignalingMessage::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toJson(): String {
        return gson.toJson(this)
    }
}
