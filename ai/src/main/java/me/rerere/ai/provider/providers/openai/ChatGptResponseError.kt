package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.util.HttpException

/** Keep actionable server fields without serializing an arbitrary response body into diagnostics. */
internal fun chatGptResponseError(payload: JsonElement?, statusCode: Int? = null): HttpException {
    val root = payload as? JsonObject
    val error = root?.get("error") as? JsonObject ?: root
    val code = chatGptResponseErrorCode(payload)
    val param = error?.get("param").stringValue()?.takeIf {
        it.matches(Regex("[a-zA-Z0-9_.\\[\\]-]{1,200}"))
    }
    val reason = (error?.get("message").stringValue()
        ?: error?.get("detail").stringValue()
        ?: root?.get("detail").stringValue())
        ?.let(::redactChatGptErrorReason)?.trim()?.take(1024)?.replace(Regex("[\\p{Cntrl}]"), " ")?.takeIf(String::isNotBlank)
    val prefix = if (statusCode == null) "ChatGPT 请求失败" else "ChatGPT 请求失败（HTTP $statusCode）"
    val detail = listOfNotNull(code, param?.let { "参数：$it" }, reason).joinToString("；")
    return HttpException(if (detail.isEmpty()) prefix else "$prefix：$detail")
}

internal fun chatGptResponseErrorCode(payload: JsonElement?): String? {
    val root = payload as? JsonObject
    val error = root?.get("error") as? JsonObject ?: root
    return error?.get("code").stringValue()?.takeIf { it.matches(Regex("[a-zA-Z0-9_]{1,100}")) }
}

private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Redact sensitive text before truncation so long credentials cannot hide a useful rejection reason. */
private fun redactChatGptErrorReason(reason: String): String = reason
    .replace(Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)) { match ->
        match.value.substringBefore('?').substringBefore('#')
    }
    .replace(Regex("\\bBearer\\s+[^\\s,;<>\"']+", RegexOption.IGNORE_CASE), "Bearer [已隐藏]")
    .replace(
        Regex("(?<![a-zA-Z0-9_-])eyJ[a-zA-Z0-9_-]*\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+(?![a-zA-Z0-9_-])"),
        "[已隐藏]",
    )
    .replace(Regex("(?<![a-zA-Z0-9_-])sk-[a-zA-Z0-9_-]+"), "[已隐藏]")
