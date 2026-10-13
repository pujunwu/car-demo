package com.example.protocol

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Socket Video Stream Packet Protocol for Car Screen Streaming.
 */
class StreamPacket(
    val type: Byte,
    val timestamp: Long,
    val width: Short,
    val height: Short,
    val payload: ByteArray
) {
    companion object {
        const val MAGIC: Int = 0x43415253 // "CARS"

        const val TYPE_SPS_PPS: Byte = 1//sps pps
        const val TYPE_KEY_FRAME: Byte = 2//关键帧
        const val TYPE_INTER_FRAME: Byte = 3//
        const val TYPE_HEARTBEAT: Byte = 4//

        const val HEADER_SIZE: Int = 21 // 4 + 1 + 8 + 2 + 2 + 4

        fun write(
            output: DataOutputStream,
            type: Byte,
            timestamp: Long,
            width: Int,
            height: Int,
            payload: ByteArray
        ) {
            output.writeInt(MAGIC)
            output.writeByte(type.toInt())
            output.writeLong(timestamp)
            output.writeShort(width)
            output.writeShort(height)
            output.writeInt(payload.size)
            output.write(payload)
            output.flush()
        }

        fun readFromStream(input: DataInputStream): StreamPacket? {
            while (true) {
                val magic = try {
                    input.readInt()
                } catch (e: Exception) {
                    return null
                }
                if (magic == MAGIC) {
                    break
                }
            }

            val type = input.readByte()
            val timestamp = input.readLong()
            val width = input.readShort()
            val height = input.readShort()
            val length = input.readInt()

            if (length < 0 || length > 10 * 1024 * 1024) {
                return null
            }

            val payload = ByteArray(length)
            input.readFully(payload)

            return StreamPacket(
                type = type,
                timestamp = timestamp,
                width = width,
                height = height,
                payload = payload
            )
        }
    }
}
