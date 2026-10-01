package me.rerere.rikkahub.ui.pages.setting.components

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import kotlin.uuid.Uuid

/** App-owned activation and discovery continue when the browser outlives its settings page. */
class ChatGptProviderConnector(
    private val settingsStore: SettingsStore,
    private val providers: ProviderManager,
) {
    private val generations = ConcurrentHashMap<Uuid, AtomicLong>()
    private val _loading = MutableStateFlow<Set<Uuid>>(emptySet())
    val loading = _loading.asStateFlow()

    suspend fun activateAndDiscover(providerId: Uuid, accountId: String): Int {
        val generation = generations.computeIfAbsent(providerId) { AtomicLong() }
        val requestId = generation.incrementAndGet()
        _loading.update { it + providerId }
        try {
            settingsStore.update { current ->
                current.copy(providers = current.providers.map { existing ->
                    if (existing.id == providerId && existing is ProviderSetting.OpenAI && generation.get() == requestId) {
                        existing.copy(
                            chatGptAccountId = accountId,
                            apiKey = "",
                            baseUrl = "https://api.openai.com/v1",
                            responsesPath = "/responses",
                            useResponseApi = true,
                            models = if (existing.chatGptAccountId == accountId) existing.models else emptyList(),
                        )
                    } else existing
                })
            }
            val selected = settingsStore.settingsFlow.value.providers.find { it.id == providerId }
                as? ProviderSetting.OpenAI ?: return 0
            if (selected.chatGptAccountId != accountId || generation.get() != requestId) return 0
            val catalog = withTimeout(30_000L) {
                providers.getProviderByType(selected).listModels(selected)
            }
            settingsStore.update { current ->
                current.copy(providers = current.providers.map { existing ->
                    if (existing.id == providerId && existing is ProviderSetting.OpenAI &&
                        existing.chatGptAccountId == accountId && generation.get() == requestId) {
                        existing.copy(models = mergeChatGptCatalog(existing.models, catalog))
                    } else existing
                })
            }
            return catalog.size
        } finally {
            if (generation.get() == requestId) _loading.update { it - providerId }
        }
    }
}

internal fun mergeChatGptCatalog(existing: List<Model>, catalog: List<Model>): List<Model> {
    val configured = existing.associateBy { it.modelId }
    return catalog.map { incoming ->
        configured[incoming.modelId]?.let { it.copy(displayName = incoming.displayName) } ?: incoming
    }
}
