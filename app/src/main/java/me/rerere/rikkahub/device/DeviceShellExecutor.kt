package me.rerere.rikkahub.device

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DeviceShellResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val truncated: Boolean = false,
) {
    fun checkedOutput(): String {
        if (timedOut || cancelled || exitCode != 0) throw DeviceShellException(this)
        return output + if (truncated) "\n[输出已截断]" else ""
    }
}

class DeviceShellException(val result: DeviceShellResult) : IllegalStateException(when {
    result.timedOut -> "设备命令执行超时"
    result.cancelled -> "设备命令已取消"
    else -> "设备命令失败（退出码 ${result.exitCode}）：${result.output}"
})

/** Bounded, cancellable process I/O shared by the privileged service and root runner. */
class DeviceShellExecutor(private val outputLimit: Int = 16_384) {
    private class Execution(val process: Process) { @Volatile var cancelled = false }
    private val lock = Any()
    @Volatile private var active: Execution? = null
    val isRunning: Boolean get() = active != null

    fun execute(arguments: List<String>, directory: File? = null, timeoutMillis: Long = 30_000): DeviceShellResult {
        require(timeoutMillis > 0 && outputLimit > 0)
        val execution = synchronized(lock) {
            check(active == null) { "Another device command is running" }
            Execution(ProcessBuilder(arguments).directory(directory).redirectErrorStream(true).start()).also { active = it }
        }
        val process = execution.process
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var truncated = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        fun readAvailable(): Int {
            val available = process.inputStream.available()
            if (available <= 0) return 0
            val size = process.inputStream.read(buffer, 0, minOf(available, buffer.size))
            if (size > 0) {
                val retained = minOf(size, outputLimit - bytes.size())
                if (retained > 0) bytes.write(buffer, 0, retained)
                if (retained < size) truncated = true
            }
            return size
        }
        fun result(exitCode: Int, timedOut: Boolean = false) = DeviceShellResult(
            exitCode, bytes.toByteArray().toString(Charsets.UTF_8), timedOut, execution.cancelled, truncated,
        )
        try {
            process.outputStream.close() // Device tools are non-interactive.
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Device command interrupted")
                if (execution.cancelled) return result(-1)
                if (System.nanoTime() >= deadline) return result(-1, timedOut = true)
                val read = readAvailable()
                if (!process.isAlive) {
                    // Do not wait for EOF held open by a descendant; retained output is bounded.
                    repeat(64) { if (readAvailable() <= 0) return result(process.exitValue()) }
                    truncated = true
                    return result(process.exitValue())
                }
                if (read <= 0) Thread.sleep(10)
            }
        } catch (error: IOException) {
            if (execution.cancelled) return result(-1)
            throw error
        } finally {
            process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
            synchronized(lock) { if (active === execution) active = null }
        }
    }

    fun cancel() = synchronized(lock) {
        active?.let { it.cancelled = true; it.process.destroyForcibly() }
        Unit
    }
}
