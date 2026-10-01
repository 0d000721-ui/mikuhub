package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `upgrades expose a stable Codex account entry without credentials or fabricated models`() {
        val provider = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.OpenAI>()
            .single { it.name == "Codex / ChatGPT" }
        assertEquals("a78b8470-956b-4dc6-8fe9-84f8ea8ed3d1", provider.id.toString())
        assertTrue(provider.builtIn)
        assertEquals("https://api.openai.com/v1", provider.baseUrl)
        assertEquals("/responses", provider.responsesPath)
        assertTrue(provider.useResponseApi)
        assertTrue(provider.apiKey.isEmpty())
        assertTrue(provider.models.isEmpty())
    }

    @Test
    fun `default providers should include vercel ai gateway with expected balance config`() {
        val vercelProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "Vercel AI Gateway" }

        assertEquals(1, vercelProviders.size)

        val provider = vercelProviders.single()
        assertEquals("https://ai-gateway.vercel.sh/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
        assertTrue(provider.balanceOption.enabled)
        assertEquals("/credits", provider.balanceOption.apiPath)
        assertEquals("balance", provider.balanceOption.resultPath)
    }
}
