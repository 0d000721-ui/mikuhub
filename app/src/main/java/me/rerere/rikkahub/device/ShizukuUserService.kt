package me.rerere.rikkahub.device

import androidx.annotation.Keep
import java.io.File
import android.os.ParcelFileDescriptor

@Keep
class ShizukuUserService : IShizukuUserService.Stub() {
    private val executor = DeviceShellExecutor()

    override fun installApk(source: ParcelFileDescriptor, size: Long, userId: Int): DeviceShellResult {
        check(android.os.Process.myUid() in setOf(0, 2000)) { "安装服务没有 ADB shell / Root 权限" }
        val args = apkInstallArguments(size, userId)
        return ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            executor.execute(listOf("/system/bin/pm") + args.drop(1), timeoutMillis = APK_INSTALL_TIMEOUT,
                standardInput = input, inputSize = size)
        }
    }

    override fun exec(command: String, cwd: String): DeviceShellResult {
        require(cwd == "/") { "Unsupported working directory" }
        val preview = DeviceCommandPolicy.preview(command, "Device tool")
        check(preview.risk != DeviceCommandRisk.BLOCKED) { preview.rejectionReason ?: "Blocked device command" }
        val args = DeviceShellCommand.parse(preview.command).arguments
        return executor.execute(listOf("/system/bin/${args.first()}") + args.drop(1), File(cwd))
    }

    override fun cancel() = executor.cancel()

    override fun destroy() {
        executor.cancel()
        kotlin.system.exitProcess(0)
    }
}
