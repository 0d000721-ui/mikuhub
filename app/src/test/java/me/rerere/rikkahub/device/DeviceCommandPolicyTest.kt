package me.rerere.rikkahub.device

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCommandPolicyTest {
    @Test fun factoryResetIsAlwaysBlocked() {
        assertEquals(DeviceCommandRisk.BLOCKED, DeviceCommandPolicy.preview("am broadcast -a android.intent.action.MASTER_CLEAR", "reset").risk)
    }

    @Test fun sessionAuthorizationAllowsNonDestructiveCommand() = runBlocking {
        val session = DeviceAccessSession()
        session.authorizeSession()
        val result = session.execute(
            DeviceCommandPolicy.preview("getprop ro.product.model", "read model"),
            DeviceTransport.SHIZUKU,
            object : DeviceCommandRunner { override suspend fun run(command: String) = "test" },
        )
        assertEquals("test", result)
    }

    @Test fun revokedSessionStopsExecution() = runBlocking {
        val session = DeviceAccessSession()
        session.revoke()
        assertThrows(IllegalStateException::class.java) { runBlocking {
            session.execute(DeviceCommandPolicy.preview("getprop", "read"), DeviceTransport.ADB,
                object : DeviceCommandRunner { override suspend fun run(command: String) = "" })
        } }
        Unit
    }

    @Test fun applicationQueriesAreReadOnlyButUninstallRequiresApproval() {
        assertEquals(DeviceCommandRisk.READ_ONLY, DeviceCommandPolicy.preview("pm list packages -3", "list").risk)
        assertEquals(DeviceCommandRisk.READ_ONLY, DeviceCommandPolicy.preview("am get-current-user", "user").risk)
        assertEquals(DeviceCommandRisk.DESTRUCTIVE, DeviceCommandPolicy.preview("adb uninstall com.example.demo", "uninstall").risk)
        assertEquals(DeviceCommandRisk.DESTRUCTIVE, DeviceCommandPolicy.preview("cmd package clear com.example.demo", "clear").risk)
        assertEquals(DeviceCommandRisk.DESTRUCTIVE, DeviceCommandPolicy.preview("pm uninstall-system-updates", "uninstall updates").risk)
        assertEquals(DeviceCommandRisk.MODIFIES_STATE, DeviceCommandPolicy.preview("settings put global airplane_mode_on 1", "setting").risk)
    }

    @Test fun dangerousWrappersAndWipesAreBlocked() {
        listOf(
            "rm -r -f /", "rm --recursive --force /data", "rm -rf /storage/emulated/0",
            "rm -rf ./data", "rm -rf data/user/0", "rm -rf a/..",
            "adb shell recovery --wipe_data", "mkfs.ext4 /dev/block/test", "dd if=/dev/zero of=/data/test",
            "setenforce 0", "reboot -fp", "sh -c 'pm uninstall com.example.demo'",
            "toybox rm -rf /", "env sh -c id", "pm list packages; reboot",
            "pm list packages\nreboot", "echo \$(id)", "echo test > /sys/example",
            "am broadcast -a android.intent.action.FACTORY_RESET", "/data/local/tmp/pm uninstall com.example.demo",
            "am broadcast -n android/com.android.server.MasterClearReceiver",
        ).forEach { command ->
            val preview = DeviceCommandPolicy.preview(command, "policy test")
            assertEquals(command, DeviceCommandRisk.BLOCKED, preview.risk)
            assertTrue(preview.rejectionReason?.isNotBlank() == true)
        }
    }

    @Test fun literalArgumentsRoundTripWithoutShellExpansion() {
        val command = DeviceShellCommand.parse("adb shell am start -a android.intent.action.VIEW --es title 'hello;world'")
        assertEquals("am", command.arguments.first())
        assertEquals("hello;world", command.arguments.last())
        assertEquals(command.arguments, DeviceShellCommand.parse(command.command).arguments)
        assertEquals("pm uninstall --user 0 com.example.demo",
            DeviceShellCommand.parse("adb uninstall --user 0 com.example.demo").command)
    }

    @Test fun normalizedProtectedPathsKeepThreeConfirmations() {
        assertEquals(DeviceCommandRisk.KERNEL_CRITICAL,
            DeviceCommandPolicy.preview("cat /tmp/../sys/class/thermal/thermal_zone0/temp", "read").risk)
    }
}
