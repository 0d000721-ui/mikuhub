package me.rerere.rikkahub.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/** Only web documents are accepted, including for redirects and clicked links. */
internal fun browserUrl(value: String, addHttps: Boolean = false): String {
    val trimmed = value.trim()
    val candidate = if (addHttps && !trimmed.contains("://") && !trimmed.contains(':')) "https://$trimmed" else trimmed
    require(candidate.length in 1..8192 && candidate.none { it.isISOControl() }) { "请输入有效的 HTTP 或 HTTPS 网页地址" }
    val uri = runCatching { URI(candidate) }.getOrNull()
        ?: error("网页地址格式不正确")
    require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
        "浏览器只支持 HTTP 和 HTTPS 网页，不能打开文件、应用跳转或带账号密码的地址"
    }
    return uri.toASCIIString()
}

internal fun isBrowserUrl(value: String?): Boolean = value != null && runCatching { browserUrl(value) }.isSuccess

/** JSON escaping, not source interpolation: model input is always a literal value. */
internal fun browserJsString(value: String): String = JsonPrimitive(value).toString()
    .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

internal fun decodeBrowserJavascriptResult(raw: String?): String {
    require(raw != null && raw != "null") { "网页未返回可读取的内容，请稍后重试" }
    return Json.parseToJsonElement(raw).jsonPrimitive.content
}

/** Revoking permission or a manual takeover invalidates every previously captured permit. */
internal class BrowserPermission {
    var enabled: Boolean = false
        private set
    private var generation = 0L

    fun setEnabled(value: Boolean) {
        generation++
        enabled = value
    }

    fun invalidate() { generation++ }

    fun permit(): Long {
        check(enabled) { "浏览器 AI 操作尚未开启。请打开输入框工具栏的浏览器图标，开启“允许 AI 操作此浏览器”。" }
        return generation
    }

    fun verify(permit: Long) {
        check(enabled && permit == generation) { "浏览器操作已停止或页面已被用户接管，请重新请求操作" }
    }
}
