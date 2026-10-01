package me.rerere.rikkahub.ui.components.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test

class OpenAiSpeedTest {
    @Test fun officialModelsOfferOnlyVerifiedTiers() {
        val official = openAI()
        assertEquals(OpenAiSpeed.entries, availableOpenAiSpeeds(Model(modelId = "gpt-6-astra"), official))
        for (id in listOf("gpt-6.1-sol", "gpt-6-sol", "gpt-6-luna", "gpt-6-astra-2026-09-22")) {
            assertTrue(availableOpenAiSpeeds(Model(modelId = id), official).contains(OpenAiSpeed.FAST))
        }
        assertFalse(availableOpenAiSpeeds(Model(modelId = "gpt-6.1-sol"), official).contains(OpenAiSpeed.ULTRAFAST))
        assertTrue(availableOpenAiSpeeds(Model(modelId = "gpt-5.6-sol"), official).isEmpty())
        assertTrue(availableOpenAiSpeeds(Model(modelId = "gpt-6-astra-fake-fast"), official).isEmpty())
    }

    @Test fun thirdPartyAndImpersonatingHostsDoNotOfferOfficialSpeeds() {
        for (url in listOf("https://example.com/v1", "https://api.openai.com.example.com/v1", "http://api.openai.com/v1", "https://api.openai.com@evil.example/v1")) {
            assertTrue(availableOpenAiSpeeds(Model(modelId = "gpt-6-astra"), openAI(baseUrl = url)).isEmpty())
        }
        assertFalse(availableOpenAiSpeeds(Model(modelId = "gpt-6-astra"), openAI(chatGptAccountId = "local-reference", baseUrl = "https://example.com/v1")).isEmpty())
    }

    @Test fun explicitStandardOverridesAssistantFastAndRemovesDuplicateModelKeys() {
        val original = Model(modelId = "gpt-6-astra", customBodies = listOf(body("service_tier", "fast"), body("temperature", "saved"), body("service_tier", "ultrafast")))
        val updated = withOpenAiSpeed(original, OpenAiSpeed.STANDARD)
        assertEquals("default", effectiveOpenAiServiceTier(updated.customBodies, listOf(body("service_tier", "fast"))))
        assertEquals(1, updated.customBodies.count { it.key == "service_tier" })
        assertEquals(body("temperature", "saved"), updated.customBodies.first())
    }

    @Test fun readingMatchesActualBodyMergePrecedenceAndLegacyPriority() {
        assertEquals("fast", effectiveOpenAiServiceTier(emptyList(), listOf(body("service_tier", "fast"))))
        assertEquals("default", effectiveOpenAiServiceTier(listOf(body("service_tier", "default")), listOf(body("service_tier", "fast"))))
        assertEquals(OpenAiSpeed.FAST, OpenAiSpeed.fromWire("priority"))
        assertNull(OpenAiSpeed.fromWire("auto"))
    }

    @Test fun selectingSpeedPreservesConfiguredModelAndCatalogIdentity() {
        val model = Model(modelId = "gpt-6-astra", displayName = "Saved", tools = setOf(BuiltInTools.Search), customHeaders = listOf(CustomHeader("x", "saved")), customBodies = listOf(body("other", "saved")))
        val provider = openAI(models = listOf(model))
        val updated = updateOpenAiModelSpeed(listOf(provider), model.id, OpenAiSpeed.ULTRAFAST).single().models.single()
        assertEquals(model.copy(customBodies = model.customBodies + body("service_tier", "ultrafast")), updated)
    }

    @Test fun effectiveProviderOverrideControlsAvailabilityAndUnsupportedUpdatesAreIgnored() {
        val model = Model(modelId = "gpt-6-astra", providerOverwrite = openAI(baseUrl = "https://example.com/v1"))
        val provider = openAI(models = listOf(model))
        assertEquals(listOf(provider), updateOpenAiModelSpeed(listOf(provider), model.id, OpenAiSpeed.FAST))
        val sol = Model(modelId = "gpt-6.1-sol")
        val solProvider = openAI(models = listOf(sol))
        assertEquals(listOf(solProvider), updateOpenAiModelSpeed(listOf(solProvider), sol.id, OpenAiSpeed.ULTRAFAST))
    }

    @Test fun speedSelectionSurvivesSavedModelRoundTrip() {
        val configured = withOpenAiSpeed(Model(modelId = "gpt-6-astra"), OpenAiSpeed.STANDARD)
        val restored = Json.decodeFromString<Model>(Json.encodeToString(configured))
        assertEquals(configured, restored)
        assertEquals("default", effectiveOpenAiServiceTier(restored.customBodies, listOf(body("service_tier", "fast"))))
    }

    // Decode through the production serializer so this test also verifies the saved provider shape.
    private fun openAI(models: List<Model> = emptyList(), baseUrl: String = "https://api.openai.com/v1", chatGptAccountId: String? = null): ProviderSetting.OpenAI =
        Json.decodeFromString(buildJsonObject {
            put("baseUrl", baseUrl)
            put("models", Json.parseToJsonElement(Json.encodeToString(models)))
            if (chatGptAccountId != null) put("chatGptAccountId", chatGptAccountId)
        }.toString())
    private fun body(key: String, value: String) = CustomBody(key, JsonPrimitive(value))
}
