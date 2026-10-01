package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.util.HttpException

/** Internal signal for a rejected wire spelling, never a general transport retry. */
internal class ChatGptFastTierRejected(val rejection: HttpException) : RuntimeException(rejection)

internal fun isChatGptFastTierSpellingRejection(
    isChatGptAccount: Boolean,
    requestBody: JsonObject,
    statusCode: Int,
    payload: JsonElement?,
): Boolean {
    if (!isChatGptAccount || statusCode != 400 || requestBody["service_tier"] != JsonPrimitive("fast")) return false
    val root = payload as? JsonObject ?: return false
    val error = root["error"] as? JsonObject ?: root
    val reason = (error["message"] ?: error["detail"] ?: root["detail"]) as? JsonPrimitive ?: return false
    return reason.isString && reason.content.trim() == "Unsupported service_tier: fast"
}

/** `priority` is the same fast tier. Never fall back to default or replay an accepted stream. */
internal fun withChatGptFastTierCompatibility(
    requestBody: JsonObject,
    request: (JsonObject) -> Flow<StreamChunk>,
): Flow<StreamChunk> = flow {
    var emitted = false
    emitAll(request(requestBody).onEach { emitted = true }.catch { failure ->
        if (failure !is ChatGptFastTierRejected) throw failure
        if (emitted || requestBody["service_tier"] != JsonPrimitive("fast")) throw failure.rejection
        currentCoroutineContext().ensureActive()
        val priorityBody = JsonObject(requestBody + ("service_tier" to JsonPrimitive("priority")))
        emitAll(request(priorityBody).catch { priorityFailure ->
            // The replacement request is never eligible for another compatibility attempt.
            throw if (priorityFailure is ChatGptFastTierRejected) priorityFailure.rejection else priorityFailure
        })
    })
}
