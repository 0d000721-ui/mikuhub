package me.rerere.rikkahub.device

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AdbProtocolTest {
    @Test fun roundTripFrame() {
        val bytes = ByteArrayOutputStream()
        AdbProtocol.writeFrame(DataOutputStream(bytes), "CNXN", "hello".toByteArray())
        val frame = AdbProtocol.readFrame(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        assertEquals("CNXN", frame.command)
        assertArrayEquals("hello".toByteArray(), frame.payload)
    }
}
