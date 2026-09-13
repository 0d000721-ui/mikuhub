package me.rerere.rikkahub.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class DeviceBackendStatus(val shizukuState: ShizukuState, val shizukuUid: Int? = null, val detail: String? = null)

interface DeviceCommandBackend {
    suspend fun status(): DeviceBackendStatus
    suspend fun run(transport: DeviceTransport, command: String): String
}

class DeviceCommandController(
    private val session: DeviceAccessSession,
    private val backend: DeviceCommandBackend,
    private val confirmations: DeviceCommandConfirmations,
) {
    suspend fun status(): JsonObject {
        val backendStatus = backend.status()
        return buildJsonObject {
            put("device", "the Android device running RikkaHub")
            put("session_authorization", session.authorization.name)
            put("agent_stopped", session.stopped)
            put("execution_allowed_by_session", !session.stopped && session.authorization != DeviceAuthorization.REVOKED)
            putJsonObject("shizuku") {
                put("state", backendStatus.shizukuState.name)
                put("authorized", backendStatus.shizukuState == ShizukuState.AUTHORIZED)
                backendStatus.shizukuUid?.let { put("uid", it) }
                put("privilege", when (backendStatus.shizukuUid) { 2000 -> "adb_shell"; 0 -> "root"; else -> "unknown" })
                backendStatus.detail?.let { put("detail", it) }
            }
            putJsonArray("available_transports") {
                if (backendStatus.shizukuState == ShizukuState.AUTHORIZED) add("shizuku")
            }
            put("direct_adb_connection", false)
            put("root", "Not probed. Explicit root selection requires on-device confirmation and the su manager's permission.")
            put("usage", "Use device_command with transport=auto or shizuku for local Android shell commands. Shizuku started via wireless debugging supplies ADB shell privileges. Uninstall/clear always require on-device confirmation.")
        }
    }

    suspend fun execute(
        command: String,
        explanation: String,
        requestedTransport: String = "auto",
        impact: String = "",
        riskExplanation: String = "",
    ): JsonObject = withContext(Dispatchers.IO) {
        require(command.isNotBlank() && explanation.isNotBlank()) { "command 和 explanation 不能为空" }
        val transport = when (requestedTransport.lowercase()) {
            "auto", "adb", "shizuku" -> DeviceTransport.SHIZUKU
            "root" -> DeviceTransport.ROOT
            else -> error("不支持的 transport：$requestedTransport")
        }
        val preview = DeviceCommandPolicy.preview(command, explanation, impact, riskExplanation)
        try {
            val output = session.execute(
                preview, transport,
                runner = object : DeviceCommandRunner {
                    override suspend fun run(command: String): String {
                        // Re-check the real transport after confirmation, not only when the tool was advertised.
                        if (transport == DeviceTransport.SHIZUKU) {
                            val status = backend.status()
                            check(status.shizukuState == ShizukuState.AUTHORIZED) {
                                "Shizuku 未就绪或未授权：${status.detail ?: status.shizukuState}。请打开设备控制页授权，再调用 device_status；不会自动改用 root。"
                            }
                        }
                        return backend.run(transport, command)
                    }
                },
                requestConfirmation = { checked, actualTransport ->
                    if (actualTransport == DeviceTransport.SHIZUKU) {
                        val status = backend.status()
                        check(status.shizukuState == ShizukuState.AUTHORIZED) { status.detail ?: "请先在设备控制页完成 Shizuku 授权" }
                    }
                    confirmations.request(checked, actualTransport)
                },
            )
            result(preview, transport, requestedTransport, true, output, exitCode = 0)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val shell = (error as? DeviceShellException)?.result
            result(preview, transport, requestedTransport, false, shell?.output.orEmpty(), shell?.exitCode,
                error.message ?: error.javaClass.simpleName, error is DeviceCommandRejectedException, shell?.timedOut == true)
        }
    }

    private fun result(
        preview: DeviceCommandPreview,
        transport: DeviceTransport,
        requestedTransport: String,
        success: Boolean,
        output: String,
        exitCode: Int? = null,
        error: String? = null,
        rejected: Boolean = false,
        timedOut: Boolean = false,
    ) = buildJsonObject {
        put("success", success)
        put("command", preview.command)
        put("transport", transport.name.lowercase())
        put("requested_transport", requestedTransport)
        put("risk", preview.risk.name)
        put("output", output)
        exitCode?.let { put("exit_code", it) }
        error?.let { put("error", it) }
        put("user_rejected", rejected)
        put("timed_out", timedOut)
    }
}
