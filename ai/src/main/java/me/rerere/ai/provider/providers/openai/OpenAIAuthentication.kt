package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.KeyRoulette
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.await
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import me.rerere.ai.ui.StreamChunk
import okhttp3.Call
import okhttp3.Response

internal fun normalizeChatGptProvider(setting: ProviderSetting.OpenAI): ProviderSetting.OpenAI =
    if (setting.chatGptAccountId == null) setting else setting.copy(
        baseUrl = "https://api.openai.com/v1",
        responsesPath = "/responses",
        useResponseApi = true,
    )

/** OAuth credentials are short lived and must bypass the API key roulette's on-disk cache. */
internal fun openAIBearer(setting: ProviderSetting.OpenAI, roulette: KeyRoulette): String =
    if (setting.chatGptAccountId != null) {
        setting.apiKey.takeIf { it.isNotBlank() } ?: error("请先登录 ChatGPT 账号")
    } else roulette.next(setting.apiKey, setting.id.toString())

internal fun requireApiKeyAuthentication(setting: ProviderSetting.OpenAI, operation: String) {
    require(setting.chatGptAccountId == null) {
        "ChatGPT 订阅接入只支持模型列表和 Responses 对话；$operation 需要单独的 API Key 服务商"
    }
}

internal const val CHATGPT_IMAGE_GENERATION_UNSUPPORTED =
    "ChatGPT 账号的 Responses 接入暂不支持图像生成，请使用 ChatGPT 网页或支持生图的 API Key 服务商。"

/** Apply account-preview restrictions after merging user-defined request parameters. */
internal fun normalizeChatGptResponseBody(body: JsonObject): JsonObject {
    require((body["tools"] as? JsonArray).orEmpty().none { tool ->
        (tool.jsonObjectOrNull?.get("type") as? JsonPrimitive)?.contentOrNull == "image_generation"
    }) { CHATGPT_IMAGE_GENERATION_UNSUPPORTED }
    val values = body.toMutableMap()
    values["store"] = JsonPrimitive(false)
    values["stream"] = JsonPrimitive(true)
    // These fields are unsupported throughout the SIWC preview, independent of reasoning effort.
    listOf("max_output_tokens", "temperature", "top_p", "top_logprobs").forEach(values::remove)
    val model = body["model"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
    val requiresReasoning = model == "gpt-6-astra" || model.startsWith("gpt-6-astra-") ||
        model == "gpt-6.1-sol" || model.startsWith("gpt-6.1-sol-")
    var reasoning = body["reasoning"]?.jsonObjectOrNull
    if (requiresReasoning && reasoning?.get("effort")?.jsonPrimitive?.contentOrNull == "none") {
        reasoning = JsonObject(reasoning - "effort")
        values["reasoning"] = reasoning
    }
    return JsonObject(values)
}

/** Link only this operation to its account session; canceling it leaves the caller and other accounts intact. */
internal suspend fun <T> withChatGptRequest(setting: ProviderSetting.OpenAI, block: suspend () -> T): T {
    val session = setting.chatGptRequestJob.takeIf { setting.chatGptAccountId != null } ?: return block()
    return coroutineScope {
        session.ensureActive()
        val requestJob = currentCoroutineContext()[Job]!!
        // This independent child is canceled immediately by the account. The operation itself stays a caller child.
        val sessionWatcher = SupervisorJob(session)
        val cancellation = sessionWatcher.invokeOnCompletion { cause ->
            if (cause != null) requestJob.cancel(CancellationException("ChatGPT 登录会话已结束"))
        }
        try {
            currentCoroutineContext().ensureActive()
            block()
        } finally {
            cancellation.dispose()
            sessionWatcher.cancel()
        }
    }
}

internal fun Flow<StreamChunk>.guardChatGptSession(setting: ProviderSetting.OpenAI): Flow<StreamChunk> {
    if (setting.chatGptAccountId == null || setting.chatGptRequestJob == null) return this
    val source = this
    return channelFlow {
        withChatGptRequest(setting) { source.collect { send(it) } }
    }
}

/** Cancel transport during both header wait and synchronous response-body reads. */
internal suspend fun <T> withOpenAIResponse(setting: ProviderSetting.OpenAI, call: Call, block: (Response) -> T): T {
    if (setting.chatGptAccountId == null) return call.await().use(block)
    return withChatGptRequest(setting) {
        val transportWatcher = Job(currentCoroutineContext()[Job])
        val cancellation = transportWatcher.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
        try {
            call.await().use(block)
        } finally {
            cancellation.dispose()
            transportWatcher.cancel()
        }
    }
}
