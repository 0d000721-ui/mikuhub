package me.rerere.rikkahub.device

import org.junit.Assert.*
import org.junit.Test

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
        assertEquals(listOf("pm", "install", "-r", "--user", "10", "-S", "456789", "-"), apkInstallArguments(456789, 10))
        val command = DeviceShellCommand(apkInstallArguments(456789, 10)).command
        assertEquals(DeviceCommandRisk.MODIFIES_STATE, DeviceCommandPolicy.preview(command, "Install").risk)
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
