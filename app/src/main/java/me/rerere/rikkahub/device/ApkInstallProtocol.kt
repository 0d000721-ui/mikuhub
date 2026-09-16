package me.rerere.rikkahub.device

internal const val APK_INSTALL_TIMEOUT = 180_000L

/** Only a regular APK stream, replacement permitted, scoped to this app's Android user. */
internal fun apkInstallArguments(size: Long, userId: Int): List<String> {
    require(size in 1..(8L * 1024 * 1024 * 1024)) { "APK 大小无效或超过 8 GB" }
    require(userId >= 0) { "Android 用户编号无效" }
    return listOf("pm", "install", "-r", "--user", userId.toString(), "-S", size.toString(), "-")
}

internal fun requireApkInstallSuccess(output: String): String {
    check(output.lineSequence().any { it.trim() == "Success" } &&
        !output.contains("Failure [") && !output.contains("INSTALL_FAILED")) {
        "静默安装未成功：${output.trim().ifBlank { "安装服务未返回成功结果" }}"
    }
    return output
}

internal fun apkInstallFailureHint(message: String): String = when {
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
