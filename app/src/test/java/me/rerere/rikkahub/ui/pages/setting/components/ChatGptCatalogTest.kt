package me.rerere.rikkahub.ui.pages.setting.components

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatGptCatalogTest {
    @Test fun refreshingCatalogPreservesUserToolsHeadersAndModelConfiguration() {
        val configured = Model(
            modelId = "account-model",
            displayName = "Old name",
            tools = setOf(BuiltInTools.Search),
            customHeaders = listOf(CustomHeader("X-Preference", "saved")),
            customBodies = listOf(CustomBody("example", JsonPrimitive("saved"))),
        )
        val discovered = Model(modelId = "account-model", displayName = "New name")
        assertEquals(configured.copy(displayName = "New name"), mergeChatGptCatalog(listOf(configured), listOf(discovered)).single())
    }

    @Test fun newModelsKeepServerOrderingAndUserConfiguredModelsAreRetained() {
        val manual = Model(modelId = "gpt-6.1-sol", displayName = "My Sol")
        val first = Model(modelId = "first")
        val second = Model(modelId = "second")
        assertEquals(listOf(first, second, manual), mergeChatGptCatalog(listOf(manual), listOf(first, second)))
    }

    @Test fun anEmptyCatalogDoesNotEraseConfiguredModels() {
        val manual = Model(modelId = "gpt-6.1-sol")
        assertEquals(listOf(manual), mergeChatGptCatalog(listOf(manual), emptyList()))
    }

    @Test fun aPreviouslyManualModelIsNotDuplicatedWhenTheCatalogCatchesUp() {
        val manual = Model(modelId = "gpt-6.1-sol", customBodies = listOf(CustomBody("service_tier", JsonPrimitive("default"))))
        val discovered = Model(modelId = manual.modelId, displayName = "GPT-6.1 Sol")
        val first = mergeChatGptCatalog(listOf(manual), listOf(discovered))
        assertEquals(listOf(manual.copy(displayName = discovered.displayName)), first)
        assertEquals(first, mergeChatGptCatalog(first, listOf(discovered)))
    }
}
