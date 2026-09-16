package me.rerere.rikkahub.device

import android.app.DownloadManager
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

enum class DownloadStatus { QUEUED, DOWNLOADING, WAITING, READY, FAILED, CANCELLED, MISSING, UNAVAILABLE }

internal const val APK_MIME_TYPE = "application/vnd.android.package-archive"

data class DownloadTask(
    val id: Long,
    val url: String,
    val fileName: String,
    val createdAt: Long,
    val status: DownloadStatus,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,
    val detail: String? = null,
    val mimeType: String? = null,
) {
    val isApk get() = !isHtmlMimeType(mimeType) && (mimeType == APK_MIME_TYPE || fileName.endsWith(".apk", true))
    val isActive get() = status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING || status == DownloadStatus.WAITING
    val progressFraction: Float?
        get() = when {
            status == DownloadStatus.READY -> 1f
            totalBytes > 0 -> (downloadedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
            else -> null
        }
    val percent: Int? get() = progressFraction?.let { (it * 100).toInt() }
    val statusLabel: String get() = when (status) {
        DownloadStatus.QUEUED -> "等待下载"
        DownloadStatus.DOWNLOADING -> "正在下载"
        DownloadStatus.WAITING -> "等待网络"
        DownloadStatus.READY -> "下载完成"
        DownloadStatus.FAILED -> "下载失败"
        DownloadStatus.CANCELLED -> "已取消"
        DownloadStatus.MISSING -> "文件已移除"
        DownloadStatus.UNAVAILABLE -> "状态暂不可用"
    }
    val sizeLabel: String get() = if (totalBytes > 0) {
        "${formatDownloadBytes(downloadedBytes)} / ${formatDownloadBytes(totalBytes)}"
    } else {
        "已下载 ${formatDownloadBytes(downloadedBytes)} · 总大小未知"
    }
}

@Serializable
internal data class SavedDownload(
    val id: Long,
    val url: String,
    val fileName: String,
    val createdAt: Long,
    val cancelled: Boolean = false,
    val missing: Boolean = false,
    val mimeType: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
) {
    fun toTask(status: DownloadStatus = when {
        cancelled -> DownloadStatus.CANCELLED
        missing -> DownloadStatus.MISSING
        else -> DownloadStatus.QUEUED
    }) =
        DownloadTask(id, url, fileName, createdAt, status, mimeType = mimeType)
}

internal fun validatedDownloadUrl(value: String): String {
    val text = value.trim()
    val uri = runCatching { URI(text) }.getOrNull()
    require(uri != null && uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
        "请输入完整的 HTTP 或 HTTPS 文件地址，不支持内嵌账号密码"
    }
    return text
}

internal fun safeApkFileName(value: String): String {
    val base = safeDownloadFileName(value.substringBefore('?').substringBefore('#'))
    return if (base.endsWith(".apk", true)) base.dropLast(4) + ".apk" else "$base.apk"
}

/** A display name also becomes a local file name, so keep it inside one path component. */
internal fun safeDownloadFileName(value: String): String {
    val clean = value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim(' ', '.')
    val extension = clean.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }
        ?.let { ".$it" }.orEmpty()
    val stem = if (extension.isNotEmpty()) clean.dropLast(extension.length) else clean
    val limit = 180 - extension.toByteArray(Charsets.UTF_8).size
    val trimmed = StringBuilder()
    var bytes = 0
    for (codePoint in stem.codePoints().toArray()) {
        val char = String(Character.toChars(codePoint))
        val length = char.toByteArray(Charsets.UTF_8).size
        if (bytes + length > limit) break
        trimmed.append(char)
        bytes += length
    }
    return trimmed.toString().ifBlank { "download" } + extension
}

internal fun normalizedDownloadMimeType(value: String?): String? = value?.substringBefore(';')
    ?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) }

internal fun isHtmlMimeType(value: String?): Boolean = normalizedDownloadMimeType(value) in setOf("text/html", "application/xhtml+xml")

internal fun looksLikeHtmlDownload(prefix: ByteArray): Boolean {
    val text = prefix.toString(Charsets.UTF_8).trimStart { it.isWhitespace() || it == '\uFEFF' }
    return Regex("(?is)^(?:<!--.*?-->\\s*)*(?:<!doctype\\s+html\\b|<html\\b|<head\\b|<body\\b)").containsMatchIn(text)
}

internal fun downloadedContentIssue(prefix: ByteArray, fileName: String, mimeType: String?): String? = when {
    isHtmlMimeType(mimeType) || looksLikeHtmlDownload(prefix) -> "服务器返回了网页，而不是所需文件。请更换实际文件直链。"
    (normalizedDownloadMimeType(mimeType) == APK_MIME_TYPE || fileName.endsWith(".apk", true)) &&
        !(prefix.size >= 4 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte() && prefix[2] == 0x03.toByte() && prefix[3] == 0x04.toByte()) ->
        "下载内容不是有效的 APK 安装包，请更换实际文件直链。"
    else -> null
}

/** RFC 5987 UTF-8 filename* takes precedence over the older quoted filename parameter. */
internal fun contentDispositionFileName(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val extended = Regex("(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
        .find(value)?.groupValues?.get(1)?.trim()?.trim('"')
    if (extended != null) {
        val pieces = extended.split('\'', limit = 3)
        if (pieces.size == 3 && pieces[0].equals("UTF-8", true)) {
            runCatching { URLDecoder.decode(pieces[2].replace("+", "%2B"), "UTF-8") }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return Regex("(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))", RegexOption.IGNORE_CASE)
        .find(value)?.let { it.groupValues[1].ifBlank { it.groupValues[2].trim() } }?.takeIf { it.isNotBlank() }
}

internal fun resolvedDownloadFileName(url: String, name: String?, disposition: String?, mimeType: String?): String {
    val urlName = runCatching { URI(url).rawPath?.substringAfterLast('/')?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") } }.getOrNull()
    var result = safeDownloadFileName(name?.takeIf { it.isNotBlank() } ?: contentDispositionFileName(disposition) ?: urlName.orEmpty())
    val mime = normalizedDownloadMimeType(mimeType)
    require(!isHtmlMimeType(mime)) { "这个地址返回的是网页，不是文件直链。请在网页中找到实际下载按钮或文件地址后重试。" }
    if ('.' !in result) {
        val extension = when (mime) {
            APK_MIME_TYPE -> "apk"
            "application/pdf" -> "pdf"
            "application/zip", "application/x-zip-compressed" -> "zip"
            "application/json" -> "json"
            "text/plain" -> "txt"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "audio/mpeg" -> "mp3"
            "video/mp4" -> "mp4"
            else -> null
        }
        if (extension != null) result += ".$extension"
    }
    return result
}

internal fun validatedDownloadHeader(value: String?): String? = value?.takeIf { it.isNotBlank() }?.also {
    require(it.length <= 2048 && it.none { char -> char == '\r' || char == '\n' || char == '\u0000' }) { "下载请求头格式无效" }
}

internal fun downloadStatus(status: Int): DownloadStatus = when (status) {
    DownloadManager.STATUS_PENDING -> DownloadStatus.QUEUED
    DownloadManager.STATUS_RUNNING -> DownloadStatus.DOWNLOADING
    DownloadManager.STATUS_PAUSED -> DownloadStatus.WAITING
    DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.READY
    DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
    else -> DownloadStatus.UNAVAILABLE
}

internal fun downloadStatusDetail(status: Int, reason: Int): String? = when (status) {
    DownloadManager.STATUS_PAUSED -> when (reason) {
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "网络恢复后会自动继续"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "连接 Wi-Fi 后会自动继续"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "连接中断，系统正在重试"
        else -> "系统已暂停，将在条件满足后继续"
    }
    DownloadManager.STATUS_FAILED -> when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足，清理空间后重试"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载存储位置不可用"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "文件名冲突，请重试"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "网络传输中断，请重试"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "下载地址重定向次数过多，请更换直链"
        DownloadManager.ERROR_CANNOT_RESUME -> "无法续传，请重新下载"
        DownloadManager.ERROR_FILE_ERROR -> "文件无法写入，请检查存储空间"
        in 400..599 -> "服务器返回 HTTP $reason，请检查下载地址"
        else -> "下载未完成，请检查网络和地址后重试"
    }
    else -> null
}

internal fun formatDownloadBytes(bytes: Long): String {
    val amount = bytes.coerceAtLeast(0).toDouble()
    return when {
        amount >= 1024 * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GB", amount / (1024 * 1024 * 1024))
        amount >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", amount / (1024 * 1024))
        amount >= 1024 -> String.format(Locale.ROOT, "%.1f KB", amount / 1024)
        else -> "${amount.toLong()} B"
    }
}
