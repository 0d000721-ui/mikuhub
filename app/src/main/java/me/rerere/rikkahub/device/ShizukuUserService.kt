package me.rerere.rikkahub.device

import androidx.annotation.Keep
import java.io.File

@Keep
class ShizukuUserService : IShizukuUserService.Stub() {
    private val executor = DeviceShellExecutor()

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
