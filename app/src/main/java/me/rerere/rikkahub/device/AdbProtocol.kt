package me.rerere.rikkahub.device

import java.io.DataInput
import java.io.DataOutput
import java.nio.charset.StandardCharsets

data class AdbFrame(val command: String, val payload: ByteArray = ByteArray(0), val arg0: Int = 0, val arg1: Int = 0)

object AdbProtocol {
    fun writeFrame(output: DataOutput, command: String, payload: ByteArray = ByteArray(0), arg0: Int = 0, arg1: Int = 0) {
        require(command.length == 4) { "ADB command must be four ASCII characters" }
        val bytes = command.toByteArray(StandardCharsets.US_ASCII)
        val code = bytes.toIntLE()
        listOf(code, arg0, arg1, payload.size, payload.sumOf { it.toInt() and 255 }, code.inv()).forEach {
            output.writeInt(Integer.reverseBytes(it))
        }
        output.write(payload)
    }

    fun readFrame(input: DataInput): AdbFrame {
        val code = Integer.reverseBytes(input.readInt())
        val command = code.toFourCC()
        val arg0 = Integer.reverseBytes(input.readInt())
        val arg1 = Integer.reverseBytes(input.readInt())
        val length = Integer.reverseBytes(input.readInt())
        val checksum = Integer.reverseBytes(input.readInt())
        require(Integer.reverseBytes(input.readInt()) == code.inv()) { "Invalid ADB magic" }
        require(length in 0..16 * 1024 * 1024) { "Invalid ADB frame length" }
        val payload = ByteArray(length)
        input.readFully(payload)
        require(checksum == 0 || checksum == payload.sumOf { it.toInt() and 255 }) { "Invalid ADB checksum" }
        return AdbFrame(command, payload, arg0, arg1)
    }

    private fun ByteArray.toIntLE(): Int = (this[0].toInt() and 255) or ((this[1].toInt() and 255) shl 8) or ((this[2].toInt() and 255) shl 16) or ((this[3].toInt() and 255) shl 24)
    private fun Int.toFourCC(): String = byteArrayOf(toByte(), (this ushr 8).toByte(), (this ushr 16).toByte(), (this ushr 24).toByte()).toString(StandardCharsets.US_ASCII)
}
