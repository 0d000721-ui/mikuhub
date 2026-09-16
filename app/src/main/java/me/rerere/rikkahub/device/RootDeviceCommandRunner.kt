package me.rerere.rikkahub.device

import java.io.IOException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible

enum class RootState { NOT_CHECKED, CHECKING, AUTHORIZED, UNAVAILABLE, DENIED, ERROR }

data class RootAccessStatus(
    val state: RootState = RootState.NOT_CHECKED,
    val detail: String = "尚未请求 Magisk / Root 授权",
    val checkedAt: Long? = null,
)

/** Uses the root manager's standard su entry point; reading status never requests root. */
class RootDeviceCommandRunner(
    private val suPath: String = "su",
    private val executeProcess: suspend (List<String>, Long) -> DeviceShellResult = ::executeRootProcess,
) : DeviceCommandRunner {
    private val _status = MutableStateFlow(RootAccessStatus())
    val status = _status.asStateFlow()

    suspend fun isMagiskCompatibleRoot(): Boolean = try {
        run("id")
        status.value.state == RootState.AUTHORIZED
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    override suspend fun run(command: String): String = executeGuarded(command) { arguments ->
        executeProcess(arguments, 30_000)
    }

    suspend fun installApk(file: File, size: Long, userId: Int): String {
        val command = DeviceShellCommand(apkInstallArguments(size, userId)).command
        return requireApkInstallSuccess(executeGuarded(command) { arguments ->
            val executor = DeviceShellExecutor()
            try {
                runInterruptible(Dispatchers.IO) {
                    file.inputStream().use { input ->
                        executor.execute(arguments, timeoutMillis = APK_INSTALL_TIMEOUT, standardInput = input, inputSize = size)
                    }
                }
            } finally { executor.cancel() }
        })
    }

    private suspend fun executeGuarded(command: String, execute: suspend (List<String>) -> DeviceShellResult): String {
        _status.value = RootAccessStatus(RootState.CHECKING, "等待 Root 管理器授权并验证 UID 0")
        // Verify identity in the SAME privileged shell before running the requested command.
        val guardedCommand = "if [ \"\$(id -u)\" != \"0\" ]; then printf '$DENIED_MARKER\\n'; exit 126; fi; " +
            "printf '$AUTHORIZED_MARKER\\n'; $command"
        val result = try {
            execute(listOf(suPath, "-c", guardedCommand))
        } catch (error: CancellationException) {
            _status.value = RootAccessStatus(detail = "Root 验证或命令已取消")
            throw error
        } catch (error: IOException) {
            _status.value = RootAccessStatus(RootState.UNAVAILABLE, "无法启动 su：${error.message}", System.currentTimeMillis())
            throw IllegalStateException("未找到可用的 su，请确认设备已安装并启用 Magisk / Root。${error.message.orEmpty()}", error)
        } catch (error: Exception) {
            _status.value = RootAccessStatus(RootState.ERROR, error.message.orEmpty(), System.currentTimeMillis())
            throw error
        }
        val lines = result.output.lineSequence().toList()
        val authorized = AUTHORIZED_MARKER in lines
        val cleaned = result.copy(output = result.output
            .replace("$AUTHORIZED_MARKER\r\n", "").replace("$AUTHORIZED_MARKER\n", "")
            .replace("$DENIED_MARKER\r\n", "").replace("$DENIED_MARKER\n", ""))
        val detail = when {
            authorized -> "上次验证为 UID 0；每次执行仍由 su 验证权限"
            result.timedOut -> "Root 请求超时，请检查 Magisk 超级用户授权后重试"
            result.cancelled -> "Root 请求已取消"
            DENIED_MARKER in lines -> "su 返回的身份不是 UID 0，命令未执行"
            else -> "Root 未获授权：${cleaned.output.ifBlank { "请在 Magisk 超级用户页面允许当前应用" }}"
        }
        _status.value = RootAccessStatus(
            when {
                authorized -> RootState.AUTHORIZED
                result.timedOut || result.cancelled || result.exitCode == 0 -> RootState.ERROR
                else -> RootState.DENIED
            }, detail, System.currentTimeMillis(),
        )
        if (!authorized) {
            throw DeviceShellException(cleaned.copy(
                exitCode = result.exitCode.takeIf { it != 0 } ?: 126,
                output = detail,
            ))
        }
        return cleaned.checkedOutput()
    }

    internal companion object {
        const val AUTHORIZED_MARKER = "__RIKKAHUB_ROOT_UID_0__"
        const val DENIED_MARKER = "__RIKKAHUB_ROOT_NOT_UID_0__"
    }
}

private suspend fun executeRootProcess(arguments: List<String>, timeout: Long): DeviceShellResult {
    val executor = DeviceShellExecutor()
    return try {
        runInterruptible(Dispatchers.IO) { executor.execute(arguments, timeoutMillis = timeout) }
    } finally {
        executor.cancel()
    }
}
