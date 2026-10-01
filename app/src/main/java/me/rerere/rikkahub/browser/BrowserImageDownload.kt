package me.rerere.rikkahub.browser

import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64

internal const val CHATGPT_IMAGES_URL = "https://chatgpt.com/"
internal const val MAX_BROWSER_IMAGE_BYTES = 20 * 1024 * 1024
internal const val BROWSER_IMAGE_CHUNK_BYTES = 48 * 1024
internal const val IMAGE_DOWNLOAD_ACTION_TTL_MS = 15_000L

/** This is a web entry point, independent of the ChatGPT API account and its tokens. */
internal fun isChatGptImagePage(value: String?): Boolean = imageHttpsUri(value)?.host?.equals("chatgpt.com", ignoreCase = true) == true

internal fun isChatGptImageSource(page: String?, source: String): Boolean {
    if (!isChatGptImagePage(page)) return false
    if (source.startsWith("blob:")) return isChatGptImagePage(source.removePrefix("blob:"))
    val host = imageHttpsUri(source)?.host?.lowercase() ?: return false
    return host == "chatgpt.com" || host == "oaiusercontent.com" || host.endsWith(".oaiusercontent.com")
}

private fun imageHttpsUri(value: String?): URI? {
    if (value == null || value.length !in 1..8192 || value.any { it.isISOControl() }) return null
    return runCatching { URI(value) }.getOrNull()?.takeIf {
        it.scheme.equals("https", ignoreCase = true) && it.rawUserInfo == null &&
            !it.host.isNullOrEmpty() && (it.port == -1 || it.port == 443)
    }
}

internal fun imageFileExtension(mime: String): String = when (mime) {
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    "image/webp" -> "webp"
    else -> error("只支持保存 PNG、JPEG 或 WebP 图片")
}

/** A website cannot start repeated downloads without a recent touch or permitted AI click. */
internal class BrowserImageDownloadGrant {
    private var actionAt = -1L
    private var document = -1L
    fun allow(now: Long, document: Long) {
        actionAt = now
        this.document = document
    }
    fun consume(now: Long, document: Long): Boolean {
        val allowed = this.document == document && actionAt >= 0 && now >= actionAt &&
            now - actionAt <= IMAGE_DOWNLOAD_ACTION_TTL_MS
        clear()
        return allowed
    }
    fun clear() { actionAt = -1; document = -1 }
}

/** Bounds both the complete image and each WebView IPC result, and rejects changed metadata. */
internal class BrowserImageChunks {
    private val bytes = ByteArrayOutputStream()
    private var total = -1
    var mime: String = ""
        private set

    fun append(offset: Int, total: Int, mime: String, base64: String) {
        require(total in 1..MAX_BROWSER_IMAGE_BYTES) { "图片为空或超过 20 MiB" }
        imageFileExtension(mime)
        require(offset == bytes.size()) { "图片分块顺序错误" }
        require(this.total == -1 || this.total == total && this.mime == mime) { "图片下载内容已改变" }
        require(base64.length in 1..(BROWSER_IMAGE_CHUNK_BYTES / 3 * 4)) { "图片分块过大或为空" }
        val chunk = Base64.getDecoder().decode(base64)
        require(chunk.size in 1..BROWSER_IMAGE_CHUNK_BYTES && bytes.size() + chunk.size <= total) { "图片分块超过声明大小" }
        this.total = total
        this.mime = mime
        bytes.write(chunk)
    }

    fun finish(): ByteArray {
        require(total > 0 && bytes.size() == total) { "图片下载不完整" }
        return bytes.toByteArray().also { requireImageSignature(it, mime) }
    }
}

internal fun requireImageSignature(bytes: ByteArray, mime: String) {
    val valid = when (mime) {
        "image/png" -> bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
        )
        "image/jpeg" -> bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        "image/webp" -> bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        else -> false
    }
    require(valid) { "服务器返回的内容不是所声明的图片，未保存" }
}
