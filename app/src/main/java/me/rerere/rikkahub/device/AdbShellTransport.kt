package me.rerere.rikkahub.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

class AdbShellTransport(private val host: String, private val port: Int = 5555) : DeviceCommandRunner, AutoCloseable {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    suspend fun connect() = withContext(Dispatchers.IO) {
        socket = Socket(host, port).apply { soTimeout = 30_000 }
        input = DataInputStream(socket!!.getInputStream()); output = DataOutputStream(socket!!.getOutputStream())
        AdbProtocol.writeFrame(output!!, "CNXN", "host::features=shell_v2".toByteArray())
        val response = AdbProtocol.readFrame(input!!)
        check(response.command == "CNXN") { "ADB handshake failed: ${response.command}" }
    }

    override suspend fun run(command: String): String = withContext(Dispatchers.IO) {
        if (socket == null) connect()
        val token = "shell,v2,raw:$command".toByteArray(StandardCharsets.UTF_8)
        AdbProtocol.writeFrame(output!!, "OPEN", token)
        val buffer = StringBuilder()
        var closed = false
        while (!closed) {
            when (val frame = AdbProtocol.readFrame(input!!).also { }) {
                else -> when (frame.command) {
                    "WRTE" -> { buffer.append(frame.payload.toString(StandardCharsets.UTF_8)); AdbProtocol.writeFrame(output!!, "OKAY") }
                    "CLSE" -> { closed = true; AdbProtocol.writeFrame(output!!, "CLSE") }
                    "FAIL" -> error(frame.payload.toString(StandardCharsets.UTF_8))
                }
            }
        }
        buffer.toString()
    }

    override fun close() { runCatching { socket?.close() }; socket = null; input = null; output = null }
}
