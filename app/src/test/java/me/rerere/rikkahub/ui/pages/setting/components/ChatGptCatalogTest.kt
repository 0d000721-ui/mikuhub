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

    @Test fun newModelsKeepServerOrderingAndRemovedModelsAreOmitted() {
        val removed = Model(modelId = "removed")
        val first = Model(modelId = "first")
        val second = Model(modelId = "second")
        assertEquals(listOf(first, second), mergeChatGptCatalog(listOf(removed), listOf(first, second)))
    }
}
