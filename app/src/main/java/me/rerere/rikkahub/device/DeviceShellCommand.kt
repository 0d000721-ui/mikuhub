package me.rerere.rikkahub.device

/** A single literal Android command. No shell expansion, pipelines or interpreter wrappers. */
data class DeviceShellCommand(val arguments: List<String>) {
    val command: String get() = arguments.joinToString(" ") { argument ->
        if (argument.matches(Regex("[A-Za-z0-9_./:=,+@%-]+"))) argument
        else "'${argument.replace("'", "'\\''")}'"
    }

    companion object {
        private val programs = setOf(
            "pm", "am", "cmd", "settings", "getprop", "setprop", "dumpsys", "wm", "input",
            "id", "whoami", "uptime", "pwd", "ps", "top", "ls", "cat", "df", "du", "free",
            "uname", "stat", "reboot", "chmod", "chown", "mount", "umount", "restorecon",
            "insmod", "rmmod", "rm", "echo", "printenv",
        )

        fun parse(command: String): DeviceShellCommand {
            require(command.length <= 8_192 && command.isNotBlank()) { "命令为空或过长" }
            val words = mutableListOf<String>()
            val word = StringBuilder()
            var quote: Char? = null
            var escaped = false
            var started = false
            command.forEach { char ->
                require(char >= ' ' || char == '\t') { "只支持单条命令，不支持换行或控制字符" }
                require(char != '$' && char != '`') { "不支持变量展开或命令替换" }
                when {
                    escaped -> { word.append(char); escaped = false; started = true }
                    char == '\\' && quote != '\'' -> { escaped = true; started = true }
                    quote != null -> if (char == quote) quote = null else word.append(char)
                    char == '\'' || char == '"' -> { quote = char; started = true }
                    char.isWhitespace() -> if (started) {
                        words += word.toString(); word.clear(); started = false
                    }
                    else -> {
                        require(char !in ";|&<>()*?") { "不支持管道、重定向、通配符或组合命令，请分别调用工具" }
                        word.append(char); started = true
                    }
                }
            }
            require(quote == null && !escaped) { "命令引号或转义不完整" }
            if (started) words += word.toString()
            require(words.isNotEmpty()) { "命令为空" }
            val args = when {
                words.take(2) == listOf("adb", "shell") -> words.drop(2)
                words.take(2) == listOf("adb", "uninstall") -> listOf("pm", "uninstall") + words.drop(2)
                else -> words.toList()
            }.toMutableList()
            require(args.isNotEmpty()) { "请提供要执行的 Android 命令" }
            if (args[0].startsWith("/system/bin/")) args[0] = args[0].removePrefix("/system/bin/")
            require(args[0] in programs) { "不支持的设备命令：${args[0]}。请使用 pm、am、settings 等单条 Android 命令" }
            require(args[0] != "cmd" || args.getOrNull(1) == "package") { "cmd 目前仅支持 package 子命令" }
            return DeviceShellCommand(args)
        }
    }
}
