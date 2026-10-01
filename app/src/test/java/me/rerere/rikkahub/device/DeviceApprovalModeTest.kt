package me.rerere.rikkahub.device

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.ai.ExecutionApprovalMode
import me.rerere.rikkahub.data.ai.ExecutionApprovalSnapshot
import org.junit.Assert.*
import org.junit.Test

class DeviceApprovalModeTest {
    @Test fun switchingAwayAndBackBeforeObserverRunsDoesNotReuseAnOldApproval() {
        var snapshot = ExecutionApprovalSnapshot(ExecutionApprovalMode.IMPORTANT_ONLY, 1L)
        var ran = false
        val session = DeviceAccessSession(approvalSnapshot = { snapshot })
        assertThrows(IllegalStateException::class.java) { runBlocking {
            session.execute(DeviceCommandPolicy.preview("pm clear com.example.test", "clear"), DeviceTransport.SHIZUKU,
                object : DeviceCommandRunner { override suspend fun run(command: String): String {
                    ran = true
                    return "Success"
                } }, requestConfirmation = { _, _ ->
                    snapshot = ExecutionApprovalSnapshot(ExecutionApprovalMode.UNRESTRICTED, 2L)
                    snapshot = ExecutionApprovalSnapshot(ExecutionApprovalMode.IMPORTANT_ONLY, 3L)
                    1
                })
        } }
        assertFalse(ran)
    }

    @Test fun modeChangeBeforeTheObserverRunsStillPreventsUnconfirmedExecution() {
        var mode = ExecutionApprovalMode.UNRESTRICTED
        var ran = false
        val session = DeviceAccessSession(approvalMode = {
            mode.also { mode = ExecutionApprovalMode.IMPORTANT_ONLY }
        })
        assertThrows(IllegalStateException::class.java) { runBlocking {
            session.execute(DeviceCommandPolicy.preview("pm clear com.example.test", "clear"), DeviceTransport.SHIZUKU,
                object : DeviceCommandRunner { override suspend fun run(command: String): String {
                    ran = true
                    return "Success"
                } })
        } }
        assertFalse(ran)
    }

    @Test fun unrestrictedRunsChangesAndRootCommandsWithoutAppConfirmations() = runBlocking {
        val commands = mutableListOf<String>()
        val session = DeviceAccessSession(approvalMode = { ExecutionApprovalMode.UNRESTRICTED })
        listOf("pm uninstall com.example.test", "settings put global test 1", "cat /sys/example", "id").forEach {
            val result = session.execute(DeviceCommandPolicy.preview(it, "test"), DeviceTransport.ROOT,
                object : DeviceCommandRunner { override suspend fun run(command: String): String { commands += command; return "Success" } },
                requestConfirmation = { _, _ -> fail("Unrestricted mode must not show app confirmation"); 0 })
            assertEquals("Success", result)
        }
        assertEquals(4, commands.size)
    }

    @Test fun sessionGrantDoesNotSilentlyTurnImportantModeIntoUnrestricted() = runBlocking {
        val session = DeviceAccessSession().apply { authorizeSession() }
        var asked = 0
        val result = session.execute(DeviceCommandPolicy.preview("pm install -r /sdcard/Download/test.apk", "install test"), DeviceTransport.SHIZUKU,
            object : DeviceCommandRunner { override suspend fun run(command: String) = "Success" },
            requestConfirmation = { preview, _ -> asked++; preview.confirmationCount })
        assertEquals("Success", result)
        assertEquals(1, asked)
    }

    @Test fun switchingBackToImportantModeAffectsTheNextOperation() = runBlocking {
        var mode = ExecutionApprovalMode.UNRESTRICTED
        val session = DeviceAccessSession(approvalMode = { mode })
        var asked = 0
        val runner = object : DeviceCommandRunner { override suspend fun run(command: String) = "Success" }
        val preview = DeviceCommandPolicy.preview("pm clear com.example.test", "clear test")
        session.execute(preview, DeviceTransport.SHIZUKU, runner) { _, _ -> asked++; 1 }
        assertEquals(0, asked)
        mode = ExecutionApprovalMode.IMPORTANT_ONLY
        session.execute(preview, DeviceTransport.SHIZUKU, runner) { _, _ -> asked++; 1 }
        assertEquals(1, asked)
    }

    @Test fun stoppedRevokedAndBlockedOperationsStillNeverRunInUnrestrictedMode() {
        val runner = object : DeviceCommandRunner { override suspend fun run(command: String): String = error("Must not execute") }
        listOf<(DeviceAccessSession) -> Unit>({ it.stop() }, { it.revoke() }).forEach { change ->
            val session = DeviceAccessSession(approvalMode = { ExecutionApprovalMode.UNRESTRICTED })
            change(session)
            assertThrows(IllegalStateException::class.java) { runBlocking {
                session.execute(DeviceCommandPolicy.preview("id", "read"), DeviceTransport.SHIZUKU, runner)
            } }
        }
        val session = DeviceAccessSession(approvalMode = { ExecutionApprovalMode.UNRESTRICTED })
        assertThrows(IllegalStateException::class.java) { runBlocking {
            session.execute(DeviceCommandPolicy.preview("rm -rf /data", "blocked"), DeviceTransport.SHIZUKU, runner)
        } }
    }

    @Test fun changingApprovalModeCancelsActiveDeviceWorkAndInvalidatesTheSessionRevision() = runBlocking {
        val session = DeviceAccessSession(approvalMode = { ExecutionApprovalMode.UNRESTRICTED })
        val started = CompletableDeferred<Unit>()
        val before = session.accessVersion
        val work = async {
            session.execute(DeviceCommandPolicy.preview("id", "read"), DeviceTransport.SHIZUKU,
                object : DeviceCommandRunner { override suspend fun run(command: String): String {
                    started.complete(Unit)
                    awaitCancellation()
                } })
        }
        withTimeout(2_000) { started.await() }
        session.approvalModeChanged()
        withTimeout(2_000) { work.join() }
        assertTrue(work.isCancelled)
        assertNotEquals(before, session.accessVersion)
    }

    @Test fun changingApprovalModeNeverRevivesAStoppedDeviceSession() {
        val session = DeviceAccessSession(approvalMode = { ExecutionApprovalMode.UNRESTRICTED })
        session.stop()
        session.approvalModeChanged()
        assertTrue(session.stopped)
        assertEquals(DeviceAuthorization.REVOKED, session.authorization)
    }
}
