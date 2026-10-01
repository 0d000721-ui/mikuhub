package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.util.Locale
import kotlin.uuid.Uuid

enum class OpenAiSpeed(val wireValue: String, val label: String) {
    STANDARD("default", "Standard"), FAST("fast", "Fast"), ULTRAFAST("ultrafast", "Ultrafast");

    companion object {
        fun fromWire(value: String?): OpenAiSpeed? = when (value?.lowercase(Locale.ROOT)) {
            "default" -> STANDARD
            "fast", "priority" -> FAST
            "ultrafast" -> ULTRAFAST
            else -> null
        }
    }
}

private val fastModel = Regex("^(gpt-6\\.1-sol|gpt-6-astra|gpt-6-sol|gpt-6-luna)(?:-\\d{4}-\\d{2}-\\d{2})?$")
private val ultrafastModel = Regex("^gpt-6-astra(?:-\\d{4}-\\d{2}-\\d{2})?$")

internal fun availableOpenAiSpeeds(model: Model, provider: ProviderSetting?): List<OpenAiSpeed> {
    val openAI = provider as? ProviderSetting.OpenAI ?: return emptyList()
    val official = openAI.chatGptAccountId != null || runCatching {
        val url = URI(openAI.baseUrl)
        url.scheme.equals("https", true) && url.host.equals("api.openai.com", true) &&
            url.userInfo == null && url.port in listOf(-1, 443)
    }.getOrDefault(false)
    if (!official || !fastModel.matches(model.modelId)) return emptyList()
    return if (ultrafastModel.matches(model.modelId)) OpenAiSpeed.entries else listOf(OpenAiSpeed.STANDARD, OpenAiSpeed.FAST)
}

/** Model bodies are merged after assistant bodies by GenerationLoop; scalar values use the last entry. */
internal fun effectiveOpenAiServiceTier(modelBodies: List<CustomBody>, assistantBodies: List<CustomBody>): String? {
    val value = (modelBodies.lastOrNull { it.key == "service_tier" }
        ?: assistantBodies.lastOrNull { it.key == "service_tier" })?.value ?: return null
    return if (value is JsonPrimitive && value.isString) value.content.lowercase(Locale.ROOT) else value.toString()
}

internal fun withOpenAiSpeed(model: Model, speed: OpenAiSpeed): Model = model.copy(
    customBodies = model.customBodies.filterNot { it.key == "service_tier" } +
        CustomBody("service_tier", JsonPrimitive(speed.wireValue)),
)

/** Apply to the current saved model, so a stale picker cannot overwrite other model edits. */
internal fun updateOpenAiModelSpeed(providers: List<ProviderSetting>, modelId: Uuid, speed: OpenAiSpeed): List<ProviderSetting> =
    providers.map { provider ->
        val model = provider.models.find { it.id == modelId }
        if (model != null && speed in availableOpenAiSpeeds(model, model.providerOverwrite ?: provider)) {
            provider.editModel(withOpenAiSpeed(model, speed))
        } else provider
    }
