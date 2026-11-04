package com.crosspaste.transport.bluetooth

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BluetoothFrameCodecTest {
    @Test
    fun `frame header is network order and retains binary payload`() {
        val encoded = ByteArrayOutputStream()
        BluetoothFrameCodec.write(
            encoded,
            BluetoothFrameCodec.Frame(BluetoothFrameCodec.DATA, 0x01020304, byteArrayOf(0, -1, 3)),
        )
        assertContentEquals(byteArrayOf(2, 1, 2, 3, 4, 0, 0, 0, 3, 0, -1, 3), encoded.toByteArray())
        val decoded = BluetoothFrameCodec.read(ByteArrayInputStream(encoded.toByteArray()))!!
        assertEquals(0x01020304, decoded.streamId)
        assertContentEquals(byteArrayOf(0, -1, 3), decoded.payload)
    }

    @Test
    fun `oversized and invalid control frames fail before payload allocation`() {
        fun header(
            type: Int,
            id: Int,
            size: Int,
        ): ByteArrayInputStream {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).apply {
                writeByte(type)
                writeInt(id)
                writeInt(size)
            }
            return ByteArrayInputStream(bytes.toByteArray())
        }
        assertFailsWith<IOException> { BluetoothFrameCodec.read(header(2, 1, Int.MAX_VALUE)) }
        assertFailsWith<IOException> { BluetoothFrameCodec.read(header(2, 1, -1)) }
        assertFailsWith<IOException> { BluetoothFrameCodec.read(header(1, 1, 1)) }
        assertFailsWith<IOException> { BluetoothFrameCodec.read(header(6, 1, 0)) }
        assertFailsWith<IOException> { BluetoothFrameCodec.read(header(2, 0, 0)) }
        assertFailsWith<IOException> { BluetoothFrameCodec.read(header(7, 1, 0)) }
    }

    @Test
    fun `truncated data is rejected instead of exposing a partial application request`() {
        val encoded = ByteArrayOutputStream()
        BluetoothFrameCodec.write(encoded, BluetoothFrameCodec.Frame(BluetoothFrameCodec.DATA, 1, byteArrayOf(1, 2, 3)))
        assertFailsWith<IOException> {
            BluetoothFrameCodec.read(ByteArrayInputStream(encoded.toByteArray().dropLast(1).toByteArray()))
        }
        assertNull(BluetoothFrameCodec.read(ByteArrayInputStream(byteArrayOf())))
    }
}
