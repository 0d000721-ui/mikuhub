package me.rerere.rikkahub.device

import kotlinx.serialization.Serializable

/**
 * Safety policy for commands originating from an AI tool. The policy is
 * deliberately independent from the transport (ADB, Shizuku, or root).
 */
@Serializable
enum class DeviceCommandRisk { READ_ONLY, MODIFIES_STATE, DESTRUCTIVE, KERNEL_CRITICAL, BLOCKED }

data class DeviceCommandPreview(
    val command: String,
    val explanation: String,
    val risk: DeviceCommandRisk,
    val requiresConfirmation: Boolean,
    val confirmationCount: Int,
    val redWarning: Boolean,
    val impact: String = "",
    val riskExplanation: String = "",
    val rejectionReason: String? = null,
)

object DeviceCommandPolicy {
    private val kernelCritical = Regex("(/proc|/sys|/dev/block|/system|/vendor|/product|/odm|/data/adb|setprop\\s+ro\\.|insmod|rmmod)", RegexOption.IGNORE_CASE)
    private val prohibitedAction = Regex("master_?clear|factory_?reset|wipe[-_]?(?:user)?data", RegexOption.IGNORE_CASE)

    fun preview(command: String, explanation: String, impact: String = "", riskExplanation: String = ""): DeviceCommandPreview {
        require(command.isNotBlank()) { "Command is required" }
        val parsed = runCatching { DeviceShellCommand.parse(command) }
        val args = parsed.getOrNull()?.arguments.orEmpty()
        val normalized = parsed.getOrNull()?.command ?: command.trim()
        val packageArgs = if (args.take(2) == listOf("cmd", "package")) listOf("pm") + args.drop(2) else args
        val fileCommand = args.firstOrNull() in setOf("rm", "cat", "ls", "stat", "chmod", "chown", "mount", "umount", "restorecon", "du")
        var optionsEnded = false
        val paths = args.drop(1).filter {
            if (it == "--") { optionsEnded = true; false }
            else it.startsWith("/") || (fileCommand && (optionsEnded || !it.startsWith("-")))
        }.map(::normalizeDevicePath)
        val wipesStorage = args.firstOrNull() == "rm" && paths.any {
            it in setOf("/", "/data", "/userdata", "/data/data", "/data/user", "/data/user_de", "/sdcard", "/storage", "/storage/emulated", "/mnt") ||
                it.matches(Regex("/(?:data/user(?:_de)?|storage/emulated)/[0-9]+"))
        }
        val powersOff = args.firstOrNull() == "reboot" && args.drop(1).any { it.startsWith("-") && 'p' in it }
        val risk = when {
            parsed.isFailure || prohibitedAction.containsMatchIn(normalized) || wipesStorage || powersOff -> DeviceCommandRisk.BLOCKED
            kernelCritical.containsMatchIn(normalized) || paths.any { kernelCritical.containsMatchIn(it) } -> DeviceCommandRisk.KERNEL_CRITICAL
            packageArgs.firstOrNull() == "pm" && (packageArgs.getOrNull(1) == "clear" || packageArgs.getOrNull(1)?.startsWith("uninstall") == true) -> DeviceCommandRisk.DESTRUCTIVE
            args.firstOrNull() in setOf("rm", "reboot", "mount", "umount", "chmod", "chown", "restorecon") -> DeviceCommandRisk.DESTRUCTIVE
            args.firstOrNull() in setOf("id", "whoami", "uptime", "pwd", "getprop", "ps", "top", "ls", "cat", "df", "du", "free", "uname", "stat", "printenv", "echo") -> DeviceCommandRisk.READ_ONLY
            packageArgs.firstOrNull() == "pm" && packageArgs.getOrNull(1) in setOf("list", "path", "help", "has-feature", "get-install-location") -> DeviceCommandRisk.READ_ONLY
            args.firstOrNull() == "settings" && args.getOrNull(1) in setOf("get", "list") -> DeviceCommandRisk.READ_ONLY
            args == listOf("am", "get-current-user") -> DeviceCommandRisk.READ_ONLY
            else -> DeviceCommandRisk.MODIFIES_STATE
        }
        return DeviceCommandPreview(
            command = normalized,
            explanation = explanation.ifBlank { "The assistant did not provide an explanation." },
            risk = risk,
            requiresConfirmation = risk != DeviceCommandRisk.READ_ONLY,
            confirmationCount = if (risk == DeviceCommandRisk.KERNEL_CRITICAL) 3 else if (risk == DeviceCommandRisk.READ_ONLY) 0 else 1,
            redWarning = risk == DeviceCommandRisk.KERNEL_CRITICAL,
            impact = impact.ifBlank { when (risk) {
                DeviceCommandRisk.READ_ONLY -> "读取设备信息。"
                DeviceCommandRisk.DESTRUCTIVE -> "可能卸载应用、清除指定应用数据、删除文件或重启设备。"
                DeviceCommandRisk.KERNEL_CRITICAL -> "访问或修改关键系统/内核路径，可能影响设备运行。"
                else -> "可能修改设备设置或应用状态。"
            } },
            riskExplanation = riskExplanation.ifBlank { when (risk) {
                DeviceCommandRisk.READ_ONLY -> "输出可能包含设备或应用信息。"
                DeviceCommandRisk.DESTRUCTIVE -> "应用或文件数据可能不可恢复，正在运行的任务可能中断。"
                DeviceCommandRisk.KERNEL_CRITICAL -> "可能导致系统不稳定，需要逐次确认三次。"
                else -> "设置变更可能影响设备或应用的正常使用。"
            } },
            rejectionReason = if (risk == DeviceCommandRisk.BLOCKED) parsed.exceptionOrNull()?.message ?: "该设备操作被策略永久禁止" else null,
        )
    }

    private fun normalizeDevicePath(path: String): String {
        val parts = ArrayDeque<String>()
        path.split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(it)
            }
        }
        return "/" + parts.joinToString("/")
    }
}
