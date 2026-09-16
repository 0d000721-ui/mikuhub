package me.rerere.rikkahub.device

import org.junit.Assert.*
import org.junit.Test
import me.rerere.rikkahub.device.fixture.AospShellArgumentParser

class ApkInstallProtocolTest {
    @Test fun revokingAndReauthorizingStillInvalidatesAWaitingInstall() {
        val session = DeviceAccessSession()
        session.authorizeSession()
        val before = session.accessVersion
        session.revoke()
        session.authorizeSession()
        assertNotEquals(before, session.accessVersion)
    }
    @Test fun installationUsesExactSizeAndSpecificAndroidUserWithStdin() {
        assertEquals(listOf("pm", "install", "-r", "--user", "10", "-S", "456789", "--", "-"), apkInstallArguments(456789, 10))
        val command = DeviceShellCommand(apkInstallArguments(456789, 10)).command
        assertEquals(DeviceCommandRisk.MODIFIES_STATE, DeviceCommandPolicy.preview(command, "Install").risk)
    }
    @Test fun bareStdinPathReproducesTheReportedAndroidParserFailure() {
        val oldArguments = listOf("pm", "install", "-r", "--user", "0", "-S", "456789", "-")
        val error = assertThrows(IllegalArgumentException::class.java) { parseLikePackageManager(oldArguments) }
        assertEquals("Unknown option -", error.message)
    }
    @Test fun stdinIsAPathAfterAndroidOptionsForShizuku() {
        for (user in listOf(0, 10)) {
            for (size in listOf(1L, 456789L, 3L * 1024 * 1024 * 1024)) {
                val parsed = parseLikePackageManager(apkInstallArguments(size, user))
                assertEquals(ParsedInstall(true, user, size, "-"), parsed)
            }
        }
    }
    @Test fun rootShellSerializationPreservesStdinOptionBoundary() {
        val command = DeviceShellCommand(apkInstallArguments(456789, 10)).command
        val parsed = parseLikePackageManager(DeviceShellCommand.parse(command).arguments)
        assertEquals(ParsedInstall(true, 10, 456789, "-"), parsed)
    }
    @Test fun reportedStackTraceHasACompactSummaryAndKeepsRawDetails() {
        val reported = "设备命令失败（退出码 255）：Exception occurred while executing 'install':\n" +
            "java.lang.IllegalArgumentException: Unknown option -\n" +
            "    at com.android.server.pm.PackageManagerShellCommand.makeInstallParams(PackageManagerShellCommand.java:3671)\n".repeat(30)
        val detail = apkInstallFailureHint(reported)
        val summary = apkInstallFailureSummary(detail)
        assertTrue(detail.contains(reported))
        assertTrue(summary.contains("参数不兼容"))
        assertFalse(summary.contains("\n"))
        assertFalse(summary.contains("PackageManagerShellCommand"))
        assertTrue(summary.length <= 180)
    }
    @Test fun failureSummaryKeepsTheUsefulPackageManagerReason() {
        val output = "设备命令失败（退出码 1）：\nFailure [INSTALL_FAILED_INVALID_APK: Package is invalid]\n    at example.Frame.run(Frame.java:1)"
        assertEquals("Failure [INSTALL_FAILED_INVALID_APK: Package is invalid]", apkInstallFailureSummary(output))
        assertTrue(apkInstallFailureSummary("Failure [INSTALL_FAILED_USER_RESTRICTED]").contains("系统阻止"))
        assertEquals("下载已完成", apkInstallFailureSummary("下载已完成"))
        assertTrue(apkInstallFailureSummary("x".repeat(1000)).length <= 180)
    }

    private data class ParsedInstall(val replace: Boolean, val user: Int, val size: Long, val path: String?)

    /** Mirrors PackageManager's supported install options, using Android's actual option parser. */
    private fun parseLikePackageManager(arguments: List<String>): ParsedInstall {
        val parser = AospShellArgumentParser(arguments.toTypedArray(), 2)
        var replace = false
        var user = -1
        var size = -1L
        while (true) {
            val option = parser.nextOption ?: break
            when (option) {
                "-r" -> replace = true
                "--user" -> user = parser.nextArgRequired.toInt()
                "-S" -> size = parser.nextArgRequired.toLong()
                else -> throw IllegalArgumentException("Unknown option $option")
            }
        }
        val path = parser.nextArg
        assertNull("Unexpected trailing APK argument", parser.nextArg)
        return ParsedInstall(replace, user, size, path)
    }
    @Test fun invalidSizesAndUsersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { apkInstallArguments(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { apkInstallArguments(100, -1) }
        assertThrows(IllegalArgumentException::class.java) { apkInstallArguments(Long.MAX_VALUE, 0) }
    }
    @Test fun requiresExactSuccessfulCommitMessage() {
        assertEquals("Success\n", requireApkInstallSuccess("Success\n"))
        listOf("", "Success: created install session [4]", "Failure [INSTALL_FAILED_USER_RESTRICTED]", "Success\nFailure [INSTALL_FAILED_INTERNAL_ERROR]").forEach {
            assertThrows(IllegalStateException::class.java) { requireApkInstallSuccess(it) }
        }
    }
    @Test fun actionableFailureHintsPreserveTheSystemError() {
        val restricted = "Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]"
        assertTrue(apkInstallFailureHint(restricted).contains("USB 安装"))
        assertTrue(apkInstallFailureHint(restricted).contains(restricted))
        assertTrue(apkInstallFailureHint("INSTALL_FAILED_UPDATE_INCOMPATIBLE").contains("签名"))
        assertEquals("Permission denied", apkInstallFailureHint("Permission denied"))
    }
    @Test fun adbInstallAliasIsALocalLiteralAndroidCommand() {
        assertEquals("pm install -r /sdcard/Download/example.apk",
            DeviceShellCommand.parse("adb install -r /sdcard/Download/example.apk").command)
    }
}
