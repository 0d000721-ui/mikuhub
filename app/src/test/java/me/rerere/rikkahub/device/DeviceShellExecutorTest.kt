package me.rerere.rikkahub.device

import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider

class DeviceShellExecutorTest {
    @Test fun streamsBinaryInputWithoutShellEncodingOrBinderPayloadLimits() {
        val bytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val result = DeviceShellExecutor().execute(fixture("input"), standardInput = bytes.inputStream(), inputSize = bytes.size.toLong())
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("Received=${bytes.size}"))
        assertTrue(result.output.contains(hash))
    }

    @Test fun incompleteInputCannotReportSuccessfulInstallation() {
        val result = DeviceShellExecutor().execute(fixture("input"), standardInput = byteArrayOf(1, 2, 3).inputStream(), inputSize = 20)
        assertNotEquals(0, result.exitCode)
        assertTrue(result.output.contains("数据传输未完成"))
    }

    @Test(timeout = 10_000) fun drainsOutputWhileStreamingInput() {
        val result = DeviceShellExecutor(1024).execute(fixture("input-flood"),
            standardInput = ByteArray(1024 * 1024).inputStream(), inputSize = 1024 * 1024)
        assertEquals(0, result.exitCode)
        assertTrue(result.truncated)
    }

    @Test(timeout = 10_000) fun timeoutStopsAnInstallerThatNeverReadsItsInput() {
        val executor = DeviceShellExecutor()
        val result = executor.execute(fixture("wait"), timeoutMillis = 750,
            standardInput = ByteArray(8 * 1024 * 1024).inputStream(), inputSize = 8 * 1024 * 1024)
        assertTrue(result.timedOut)
        assertFalse(executor.isRunning)
    }

    @Test(timeout = 10_000) fun cancellationStopsBlockedInputTransfer() {
        val executor = DeviceShellExecutor()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val future = worker.submit<DeviceShellResult> { executor.execute(fixture("wait"),
                standardInput = ByteArray(8 * 1024 * 1024).inputStream(), inputSize = 8 * 1024 * 1024) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!executor.isRunning && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(executor.isRunning)
            executor.cancel()
            assertTrue(future.get(5, TimeUnit.SECONDS).cancelled)
        } finally { executor.cancel(); worker.shutdownNow() }
    }

    @Test fun readsActualOutputAndExitStatus() {
        val result = DeviceShellExecutor().execute(fixture("success"))
        assertEquals(0, result.exitCode)
        assertEquals("Success\n", result.checkedOutput())
    }

    @Test fun failingExitPreservesStderrAndCannotBeReportedAsSuccess() {
        val result = DeviceShellExecutor().execute(fixture("failure"))
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("DELETE_FAILED_INTERNAL_ERROR"))
        assertThrows(DeviceShellException::class.java) { result.checkedOutput() }
    }

    @Test fun outputIsBoundedWithoutBlockingALargeWriter() {
        val result = DeviceShellExecutor(outputLimit = 1024).execute(fixture("flood"))
        assertEquals(0, result.exitCode)
        assertTrue(result.truncated)
        assertTrue(result.output.toByteArray().size <= 1024)
    }

    @Test fun timeoutAppliesWhileTheOutputPipeIsOpen() {
        val executor = DeviceShellExecutor()
        val start = System.nanoTime()
        val result = executor.execute(fixture("wait"), timeoutMillis = 750)
        assertTrue(result.timedOut)
        assertFalse(executor.isRunning)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5_000)
    }

    @Test fun cancellationStopsARealProcess() {
        val executor = DeviceShellExecutor()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val future = worker.submit<DeviceShellResult> { executor.execute(fixture("wait")) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!executor.isRunning && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(executor.isRunning)
            executor.cancel()
            assertTrue(future.get(5, TimeUnit.SECONDS).cancelled)
            assertFalse(executor.isRunning)
        } finally {
            executor.cancel()
            worker.shutdownNow()
        }
    }

    companion object {
        private lateinit var directory: File
        @BeforeClass @JvmStatic fun compileFixture() {
            directory = Files.createTempDirectory("rikka-device-shell-test").toFile()
            val source = File(directory, "ShellFixture.java")
            source.writeText(checkNotNull(DeviceShellExecutorTest::class.java.getResourceAsStream("/device/ShellFixture.java.txt"))
                .bufferedReader().use { it.readText() })
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.path, source.path))
        }
        @AfterClass @JvmStatic fun cleanFixture() { if (::directory.isInitialized) directory.deleteRecursively() }
        private fun fixture(mode: String) = listOf(
            File(System.getProperty("java.home"), "bin/java${if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""}").path,
            "-cp", directory.path, "ShellFixture", mode,
        )
    }
}
