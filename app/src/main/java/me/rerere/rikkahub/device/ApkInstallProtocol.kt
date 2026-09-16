package me.rerere.rikkahub.device

internal const val APK_INSTALL_TIMEOUT = 180_000L

/** Only a regular APK stream, replacement permitted, scoped to this app's Android user. */
internal fun apkInstallArguments(size: Long, userId: Int): List<String> {
    require(size in 1..(8L * 1024 * 1024 * 1024)) { "APK 大小无效或超过 8 GB" }
    require(userId >= 0) { "Android 用户编号无效" }
    // Android's getNextOption() treats a bare "-" as an option too. End option parsing
    // before supplying the stdin path, otherwise makeInstallParams throws "Unknown option -".
    return listOf("pm", "install", "-r", "--user", userId.toString(), "-S", size.toString(), "--", "-")
}

internal fun requireApkInstallSuccess(output: String): String {
    check(output.lineSequence().any { it.trim() == "Success" } &&
        !output.contains("Failure [") && !output.contains("INSTALL_FAILED")) {
        "静默安装未成功：${output.trim().ifBlank { "安装服务未返回成功结果" }}"
    }
    return output
}

internal fun apkInstallFailureHint(message: String): String = when {
    message.contains("Unknown option -", true) ->
        "安装服务拒绝了输入参数。请更新 MikuHub 后重试；若仍出现此错误，请重启 Shizuku。\n$message"
    message.contains("INSTALL_FAILED_USER_RESTRICTED", true) ->
        "系统限制了通过 ADB 安装。请检查开发者选项中的 USB 安装/安全设置，以及设备管理策略。\n$message"
    message.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", true) ->
        "安装包与手机已有应用的签名不一致，不能直接覆盖安装。\n$message"
    message.contains("INSTALL_FAILED_VERSION_DOWNGRADE", true) ->
        "安装包版本低于已安装版本，请使用较新版本。\n$message"
    message.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE", true) -> "存储空间不足，请清理后重试。\n$message"
    message.contains("INSTALL_FAILED_MISSING_SPLIT", true) -> "这个 APK 缺少分包，请下载完整的单文件 APK。\n$message"
    else -> message
}

/** Keep the task readable while retaining the full system error in its expandable details. */
internal fun apkInstallFailureSummary(message: String): String {
    val summary = when {
        message.contains("Unknown option -", true) -> "安装服务参数不兼容，请更新 MikuHub 后重试；仍失败时重启 Shizuku。"
        message.contains("INSTALL_FAILED_USER_RESTRICTED", true) -> "系统阻止了安装，请检查 USB 安装授权或使用系统安装器。"
        message.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", true) -> "应用签名不一致，无法覆盖安装。"
        message.contains("INSTALL_FAILED_VERSION_DOWNGRADE", true) -> "安装包版本较旧，请使用较新版本。"
        message.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE", true) -> "手机存储空间不足，请清理后重试。"
        message.contains("INSTALL_FAILED_MISSING_SPLIT", true) -> "安装包缺少分包，请下载完整的单文件 APK。"
        else -> {
            val lines = message.lineSequence().map(String::trim).filter(String::isNotEmpty)
                .filterNot { it.startsWith("at ") || it.startsWith("Exception occurred while executing") }
                .toList()
            // A package-manager failure/exception is more useful than the generic exit-code wrapper.
            lines.firstOrNull { it.startsWith("Failure [") || it.startsWith("java.") || it.startsWith("Caused by:") }
                ?: lines.firstOrNull() ?: "操作未完成，请稍后重试"
        }
    }
    return if (summary.length > 180) summary.take(179) + "…" else summary
}
