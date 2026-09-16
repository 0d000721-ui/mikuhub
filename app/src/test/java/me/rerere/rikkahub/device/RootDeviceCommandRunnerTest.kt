package me.rerere.rikkahub.device

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RootDeviceCommandRunnerTest {
    @Test fun readingStatusDoesNotRequestRoot() {
        var calls = 0
        val runner = RootDeviceCommandRunner { _, _ -> calls++; error("must not run") }
        assertEquals(RootState.NOT_CHECKED, runner.status.value.state)
        assertEquals(0, calls)
    }

    @Test fun usesSuAndVerifiesUidInTheSameShellBeforeReturningCleanOutput() = runBlocking {
        val runner = RootDeviceCommandRunner("test-su") { arguments, timeout ->
            assertEquals(listOf("test-su", "-c"), arguments.take(2))
            assertTrue(arguments[2].startsWith("if [ \"\$(id -u)\" != \"0\" ]"))
            assertTrue(arguments[2].endsWith("; id"))
            assertEquals(30_000L, timeout)
            DeviceShellResult(0, "${RootDeviceCommandRunner.AUTHORIZED_MARKER}\nuid=0(root) gid=0(root)\n")
        }
        assertEquals("uid=0(root) gid=0(root)\n", runner.run("id"))
        assertEquals(RootState.AUTHORIZED, runner.status.value.state)
        assertNotNull(runner.status.value.checkedAt)
    }

    @Test fun successfulProcessWithoutUidVerificationIsNotAccepted() = runBlocking {
        val runner = RootDeviceCommandRunner { _, _ -> DeviceShellResult(0, "uid=2000(shell)\n") }
        val error = runCatching { runner.run("id") }.exceptionOrNull() as DeviceShellException
        assertEquals(126, error.result.exitCode)
        assertEquals(RootState.ERROR, runner.status.value.state)
    }

    @Test fun nonRootIdentityIsRejected() = runBlocking {
        val runner = RootDeviceCommandRunner { _, _ ->
            DeviceShellResult(126, "${RootDeviceCommandRunner.DENIED_MARKER}\n")
        }
        assertTrue(runCatching { runner.run("id") }.exceptionOrNull() is DeviceShellException)
        assertEquals(RootState.DENIED, runner.status.value.state)
        assertTrue(runner.status.value.detail.contains("不是 UID 0"))
    }

    @Test fun rootCommandFailureKeepsActualExitCodeAndOutput() = runBlocking {
        val runner = RootDeviceCommandRunner { _, _ ->
            DeviceShellResult(1, "${RootDeviceCommandRunner.AUTHORIZED_MARKER}\nFailure [not found]\n")
        }
        val error = runCatching { runner.run("pm path com.example.missing") }.exceptionOrNull() as DeviceShellException
        assertEquals(1, error.result.exitCode)
        assertEquals("Failure [not found]\n", error.result.output)
        assertEquals(RootState.AUTHORIZED, runner.status.value.state)
    }

    @Test fun deniedPermissionReplacesEarlierVerifiedStatus() = runBlocking {
        var granted = true
        val runner = RootDeviceCommandRunner { _, _ ->
            if (granted) DeviceShellResult(0, "${RootDeviceCommandRunner.AUTHORIZED_MARKER}\nuid=0(root)\n")
            else DeviceShellResult(1, "Permission denied")
        }
        runner.run("id")
        granted = false
        assertTrue(runCatching { runner.run("id") }.isFailure)
        assertEquals(RootState.DENIED, runner.status.value.state)
    }

    @Test fun missingSuAndTimeoutAreVisibleFailures() = runBlocking {
        val missing = RootDeviceCommandRunner { _, _ -> throw IOException("No such file") }
        assertTrue(runCatching { missing.run("id") }.isFailure)
        assertEquals(RootState.UNAVAILABLE, missing.status.value.state)
        val timeout = RootDeviceCommandRunner { _, _ -> DeviceShellResult(-1, "", timedOut = true) }
        val error = runCatching { timeout.run("id") }.exceptionOrNull() as DeviceShellException
        assertTrue(error.result.timedOut)
        assertEquals(RootState.ERROR, timeout.status.value.state)
    }

    @Test fun cancellationPropagatesAndClearsCheckingState() = runBlocking {
        val runner = RootDeviceCommandRunner { _, _ -> throw CancellationException("stopped") }
        assertTrue(runCatching { runner.run("id") }.exceptionOrNull() is CancellationException)
        assertEquals(RootState.NOT_CHECKED, runner.status.value.state)
    }
}
