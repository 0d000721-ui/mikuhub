package me.rerere.rikkahub.device

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class DeviceCommandControllerTest {
    @Test fun statusReflectsAuthorizationAndDoesNotExecuteOrProbeRoot() = runBlocking {
        val backend = FakeBackend()
        val controller = DeviceCommandController(DeviceAccessSession(), backend, DeviceCommandConfirmations())
        val status = controller.status()
        assertTrue(status["shizuku"]!!.jsonObject["authorized"]!!.jsonPrimitive.boolean)
        assertEquals("adb_shell", status["shizuku"]!!.jsonObject["privilege"]!!.jsonPrimitive.content)
        assertFalse(status["direct_adb_connection"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("shizuku"), status["available_transports"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(backend.commands.isEmpty())
    }

    @Test fun queriesUseActualShizukuOutputWithoutAConfirmation() = runBlocking {
        val backend = FakeBackend().apply { output = "package:com.example.demo\n" }
        val approvals = DeviceCommandConfirmations()
        val controller = DeviceCommandController(DeviceAccessSession(), backend, approvals)
        val result = controller.execute("adb shell pm list packages -3", "查询已安装应用", "adb")
        assertTrue(result.success())
        assertEquals("package:com.example.demo\n", result["output"]!!.jsonPrimitive.content)
        assertEquals(listOf(DeviceTransport.SHIZUKU to "pm list packages -3"), backend.commands)
        assertEquals("shizuku", result["transport"]!!.jsonPrimitive.content)
        assertNull(approvals.pending.value)
    }

    @Test fun uninstallExecutesOnlyAfterTheDeviceDialogEvenWithSessionAuthorization() = runBlocking {
        val backend = FakeBackend()
        val session = DeviceAccessSession().apply { authorizeSession() }
        val approvals = DeviceCommandConfirmations()
        val controller = DeviceCommandController(session, backend, approvals)
        val work = async { controller.execute("pm uninstall --user 0 com.example.demo", "卸载指定测试应用") }
        val request = withTimeout(2_000) { approvals.pending.first { it != null }!! }
        assertTrue(backend.commands.isEmpty())
        assertEquals(DeviceCommandRisk.DESTRUCTIVE, request.preview.risk)
        approvals.confirm(request.id, request.step)
        assertTrue(withTimeout(2_000) { work.await() }.success())
        assertEquals(1, backend.commands.size)
        assertEquals("Success\n", session.audit().single().output)
    }

    @Test fun deniedUninstallDoesNotCallRunnerAndIsAudited() = runBlocking {
        val backend = FakeBackend()
        val session = DeviceAccessSession()
        val approvals = DeviceCommandConfirmations()
        val controller = DeviceCommandController(session, backend, approvals)
        val work = async { controller.execute("pm clear com.example.demo", "清除指定应用数据") }
        val request = withTimeout(2_000) { approvals.pending.first { it != null }!! }
        approvals.reject(request.id)
        val result = withTimeout(2_000) { work.await() }
        assertFalse(result.success())
        assertTrue(result["user_rejected"]!!.jsonPrimitive.boolean)
        assertTrue(backend.commands.isEmpty())
        assertFalse(session.audit().single().allowed)
    }

    @Test fun rejectionFeedbackReachesTheAgentAndAuditWithoutRunningCommand() = runBlocking {
        val backend = FakeBackend()
        val session = DeviceAccessSession()
        val approvals = DeviceCommandConfirmations()
        val controller = DeviceCommandController(session, backend, approvals)
        val work = async { controller.execute("pm clear com.example.demo", "清除数据") }
        val request = withTimeout(2_000) { approvals.pending.first { it != null }!! }
        approvals.reject(request.id, "请保留数据，只查看占用空间")
        val result = withTimeout(2_000) { work.await() }
        assertFalse(result.success())
        assertTrue(result["user_rejected"]!!.jsonPrimitive.boolean)
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("请保留数据，只查看占用空间"))
        assertTrue(session.audit().single().error!!.contains("请保留数据，只查看占用空间"))
        assertTrue(backend.commands.isEmpty())
        assertNull(approvals.pending.value)
    }

    @Test fun modelSuppliedConfirmationCannotApproveItsOwnToolCall() = runBlocking {
        val backend = FakeBackend()
        val approvals = DeviceCommandConfirmations()
        val tool = buildDeviceCommandTool(DeviceCommandController(DeviceAccessSession(), backend, approvals))
        val args = buildJsonObject {
            put("command", "pm uninstall com.example.demo")
            put("explanation", "test")
            put("confirmed", true)
            put("confirmations", 3)
        }
        val work = async { tool.execute(args) }
        val request = withTimeout(2_000) { approvals.pending.first { it != null }!! }
        assertTrue(backend.commands.isEmpty())
        approvals.reject(request.id)
        val text = (withTimeout(2_000) { work.await() }.single() as UIMessagePart.Text).text
        assertFalse(Json.parseToJsonElement(text).jsonObject.success())
        val schema = tool.parameters() as InputSchema.Obj
        assertFalse("confirmed" in schema.properties)
        assertFalse("confirmations" in schema.properties)
    }

    @Test fun kernelOperationRequiresThreeDistinctStepsAndIgnoresStaleClicks() = runBlocking {
        val backend = FakeBackend()
        val approvals = DeviceCommandConfirmations()
        val controller = DeviceCommandController(DeviceAccessSession().apply { authorizeSession() }, backend, approvals)
        val work = async { controller.execute("cat /sys/class/thermal/thermal_zone0/temp", "读取温度") }
        val first = withTimeout(2_000) { approvals.pending.first { it != null }!! }
        assertEquals(3, first.preview.confirmationCount)
        approvals.confirm(first.id, 1)
        approvals.confirm(first.id, 1)
        assertEquals(2, approvals.pending.value!!.step)
        assertTrue(backend.commands.isEmpty())
        approvals.confirm(first.id, 2)
        assertTrue(backend.commands.isEmpty())
        approvals.confirm(first.id, 3)
        assertTrue(withTimeout(2_000) { work.await() }.success())
        assertNull(approvals.pending.value)
    }

    @Test fun blockedCommandNeverPromptsOrExecutesEvenInAuthorizedSession() = runBlocking {
        val backend = FakeBackend()
        val session = DeviceAccessSession().apply { authorizeSession() }
        val approvals = DeviceCommandConfirmations()
        val controller = DeviceCommandController(session, backend, approvals)
        val result = controller.execute("am broadcast -a android.intent.action.MASTER_CLEAR", "blocked test")
        assertFalse(result.success())
        assertNull(approvals.pending.value)
        assertTrue(backend.commands.isEmpty())
        assertFalse(session.audit().single().allowed)
    }

    @Test fun missingShizukuAuthorizationDoesNotFallBackToRoot() = runBlocking {
        val backend = FakeBackend().apply { state = ShizukuState.UNAUTHORIZED }
        val approvals = DeviceCommandConfirmations()
        val controller = DeviceCommandController(DeviceAccessSession(), backend, approvals)
        val result = controller.execute("pm uninstall com.example.demo", "uninstall")
        assertFalse(result.success())
        assertNull(approvals.pending.value)
        assertTrue(backend.commands.isEmpty())
    }

    @Test fun transportPermissionIsCheckedAgainAfterConfirmation() = runBlocking {
        val backend = FakeBackend()
        val approvals = DeviceCommandConfirmations()
        val controller = DeviceCommandController(DeviceAccessSession(), backend, approvals)
        val work = async { controller.execute("pm uninstall com.example.demo", "uninstall") }
        val request = withTimeout(2_000) { approvals.pending.first { it != null }!! }
        backend.state = ShizukuState.UNAVAILABLE
        approvals.confirm(request.id, request.step)
        assertFalse(withTimeout(2_000) { work.await() }.success())
        assertTrue(backend.commands.isEmpty())
    }

    @Test fun stopCancelsPendingConfirmationAndAnewSessionCannotReuseIt() = runBlocking {
        val backend = FakeBackend()
        val session = DeviceAccessSession()
        val approvals = DeviceCommandConfirmations()
        val controller = DeviceCommandController(session, backend, approvals)
        val work = async { controller.execute("pm uninstall com.example.demo", "uninstall") }
        val request = withTimeout(2_000) { approvals.pending.first { it != null }!! }
        session.stop()
        withTimeout(2_000) { work.join() }
        assertTrue(work.isCancelled)
        assertNull(approvals.pending.value)
        session.authorizeSession()
        approvals.confirm(request.id, request.step)
        assertTrue(backend.commands.isEmpty())
        assertTrue(controller.execute("id", "read identity").success())
    }

    @Test fun revokeCancelsRunningCommandAndRejectsQueuedWork() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val backend = FakeBackend().apply { block = { started.complete(Unit); awaitCancellation() } }
        val session = DeviceAccessSession()
        val controller = DeviceCommandController(session, backend, DeviceCommandConfirmations())
        val work = async { controller.execute("id", "read identity") }
        withTimeout(2_000) { started.await() }
        session.revoke()
        withTimeout(2_000) { work.join() }
        assertTrue(work.isCancelled)
        assertFalse(controller.execute("pm list packages", "list").success())
        assertEquals(1, backend.commands.size)
    }

    @Test fun nonzeroShellExitReturnsFailureAndRealOutput() = runBlocking {
        val backend = FakeBackend().apply { failure = DeviceShellException(DeviceShellResult(1, "Failure [DELETE_FAILED_INTERNAL_ERROR]")) }
        val approvals = DeviceCommandConfirmations()
        val session = DeviceAccessSession()
        val controller = DeviceCommandController(session, backend, approvals)
        val work = async { controller.execute("pm uninstall com.example.demo", "uninstall") }
        val request = withTimeout(2_000) { approvals.pending.first { it != null }!! }
        approvals.confirm(request.id, request.step)
        val result = withTimeout(2_000) { work.await() }
        assertFalse(result.success())
        assertEquals(1, result["exit_code"]!!.jsonPrimitive.int)
        assertTrue(result["output"]!!.jsonPrimitive.content.contains("DELETE_FAILED_INTERNAL_ERROR"))
        assertTrue(session.audit().single().allowed)
        assertNotNull(session.audit().single().error)
    }

    @Test fun confirmationTimeoutCleansPendingRequestWithoutExecution() = runBlocking {
        val backend = FakeBackend()
        val approvals = DeviceCommandConfirmations(timeoutMillis = 30)
        val controller = DeviceCommandController(DeviceAccessSession(), backend, approvals)
        val result = withTimeout(2_000) { controller.execute("pm uninstall com.example.demo", "uninstall") }
        assertFalse(result.success())
        assertNull(approvals.pending.value)
        assertTrue(backend.commands.isEmpty())
    }

    private fun JsonObject.success() = getValue("success").jsonPrimitive.boolean

    @Test fun userSelectedRootWorksWithoutShizukuAndStillRequiresConfirmation() = runBlocking {
        val backend = FakeBackend().apply { state = ShizukuState.UNAVAILABLE; output = "uid=0(root)\n" }
        val session = DeviceAccessSession().apply { selectTransport(DeviceTransport.ROOT); authorizeSession() }
        val approvals = DeviceCommandConfirmations()
        val controller = DeviceCommandController(session, backend, approvals)
        assertEquals("root", controller.status()["selected_transport"]!!.jsonPrimitive.content)
        val work = async { controller.execute("id", "verify root") }
        val request = withTimeout(2_000) { approvals.pending.first { it != null }!! }
        assertEquals(DeviceTransport.ROOT, request.transport)
        assertTrue(backend.commands.isEmpty())
        approvals.confirm(request.id, request.step)
        assertTrue(withTimeout(2_000) { work.await() }.success())
        assertEquals(listOf(DeviceTransport.ROOT to "id"), backend.commands)
    }

    @Test fun rootStatusReportsLastVerificationWithoutProbingAndFailureNeverFallsBack() = runBlocking {
        val backend = FakeBackend().apply {
            root = RootAccessStatus(RootState.AUTHORIZED, "UID 0 verified", 123L)
            failure = DeviceShellException(DeviceShellResult(1, "Permission denied"))
        }
        val session = DeviceAccessSession().apply { selectTransport(DeviceTransport.ROOT) }
        val approvals = DeviceCommandConfirmations()
        val controller = DeviceCommandController(session, backend, approvals)
        val status = controller.status()
        assertTrue(status["root"]!!.jsonObject["last_verified_uid_0"]!!.jsonPrimitive.boolean)
        assertTrue(status["available_transports"]!!.jsonArray.any { it.jsonPrimitive.content == "root" })
        assertTrue(backend.commands.isEmpty())
        val work = async { controller.execute("id", "verify root") }
        val request = withTimeout(2_000) { approvals.pending.first { it != null }!! }
        approvals.confirm(request.id, request.step)
        assertFalse(withTimeout(2_000) { work.await() }.success())
        assertEquals(listOf(DeviceTransport.ROOT to "id"), backend.commands)
    }

    @Test fun switchingTransportCancelsOldRootApproval() = runBlocking {
        val backend = FakeBackend()
        val session = DeviceAccessSession().apply { selectTransport(DeviceTransport.ROOT) }
        val approvals = DeviceCommandConfirmations()
        val controller = DeviceCommandController(session, backend, approvals)
        val work = async { controller.execute("id", "verify root") }
        val request = withTimeout(2_000) { approvals.pending.first { it != null }!! }
        session.selectTransport(DeviceTransport.SHIZUKU)
        withTimeout(2_000) { work.join() }
        approvals.confirm(request.id, request.step)
        assertTrue(work.isCancelled)
        assertNull(approvals.pending.value)
        assertTrue(backend.commands.isEmpty())
        assertEquals(DeviceTransport.SHIZUKU, DeviceAccessSession().state.value.preferredTransport)
    }

    private class FakeBackend : DeviceCommandBackend {
        var state = ShizukuState.AUTHORIZED
        var root = RootAccessStatus()
        var output = "Success\n"
        var failure: Exception? = null
        var block: (suspend () -> Unit)? = null
        val commands = java.util.concurrent.CopyOnWriteArrayList<Pair<DeviceTransport, String>>()
        override suspend fun status() = DeviceBackendStatus(state, 2000, root = root)
        override suspend fun run(transport: DeviceTransport, command: String): String {
            commands += transport to command
            block?.invoke()
            failure?.let { throw it }
            return output
        }
    }
}
