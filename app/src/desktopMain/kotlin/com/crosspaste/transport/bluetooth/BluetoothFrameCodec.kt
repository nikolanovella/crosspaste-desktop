package com.crosspaste.transport.bluetooth

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Bounded framing for a duplex RFCOMM byte stream, not a new trust protocol. */
internal object BluetoothFrameCodec {
    const val MAX_PAYLOAD = 16 * 1024
    const val OPEN = 1
    const val DATA = 2
    const val FIN = 3
    const val RESET = 4
    const val CREDIT = 5
    const val HELLO = 6

    data class Frame(
        val type: Int,
        val streamId: Int,
        val payload: ByteArray = byteArrayOf(),
    )

    fun read(input: InputStream): Frame? {
        val data = DataInputStream(input)
        val type = input.read()
        if (type == -1) return null
        val id = data.readInt()
        val size = data.readInt()
        if (type !in OPEN..HELLO || id < 0 || size !in 0..MAX_PAYLOAD) {
            throw IOException("Invalid Bluetooth transport frame")
        }
        if (type == HELLO && id != 0 || type != HELLO && id == 0) {
            throw IOException("Invalid Bluetooth stream identifier")
        }
        if (type in setOf(OPEN, FIN, RESET, CREDIT) && size != 0) {
            throw IOException("Unexpected Bluetooth control payload")
        }
        val payload = ByteArray(size)
        try {
            data.readFully(payload)
        } catch (error: EOFException) {
            throw IOException("Truncated Bluetooth transport frame", error)
        }
        return Frame(type, id, payload)
    }

    fun write(
        output: OutputStream,
        frame: Frame,
    ) {
        require(frame.payload.size <= MAX_PAYLOAD)
        val data = DataOutputStream(output)
        data.writeByte(frame.type)
        data.writeInt(frame.streamId)
        data.writeInt(frame.payload.size)
        data.write(frame.payload)
        data.flush()
    }
}
