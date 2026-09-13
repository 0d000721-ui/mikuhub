package me.rerere.rikkahub.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

class RootDeviceCommandRunner(private val suPath: String = "su") : DeviceCommandRunner {
    suspend fun isMagiskCompatibleRoot(): Boolean = try {
        Regex("uid=0(?:\\(|\\s|$)").containsMatchIn(execute("id", 5_000))
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    override suspend fun run(command: String): String = execute(command, 30_000)

    private suspend fun execute(command: String, timeout: Long): String {
        val executor = DeviceShellExecutor()
        return try {
            runInterruptible(Dispatchers.IO) {
                executor.execute(listOf(suPath, "-c", command), timeoutMillis = timeout).checkedOutput()
            }
        } finally {
            executor.cancel()
        }
    }
}
