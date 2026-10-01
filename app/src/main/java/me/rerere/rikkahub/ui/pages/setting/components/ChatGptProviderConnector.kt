package me.rerere.rikkahub.ui.pages.setting.components

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.chatgpt.ChatGptAccountManager
import kotlin.uuid.Uuid

/** App-owned activation and discovery continue when the browser outlives its settings page. */
class ChatGptProviderConnector(
    private val settingsStore: SettingsStore,
    private val providers: ProviderManager,
    private val appScope: AppScope,
    private val accounts: ChatGptAccountManager,
) {
    private data class RefreshRequest(val accountId: String, val generation: Long, val allowDisabled: AtomicBoolean, val job: Deferred<Int>)
    private data class CatalogOwner(val providerId: Uuid, val accountId: String, val enabled: Boolean)
    private val generations = ConcurrentHashMap<Uuid, AtomicLong>()
    private val activationGenerations = ConcurrentHashMap<Uuid, AtomicLong>()
    private val requestMutex = Mutex()
    private val requests = mutableMapOf<Uuid, RefreshRequest>()
    private val refreshPolicy = ChatGptCatalogRefreshPolicy()
    private var observer: Job? = null
    private val _loading = MutableStateFlow<Set<Uuid>>(emptySet())
    val loading = _loading.asStateFlow()
    private val _catalogStatus = MutableStateFlow<Map<Uuid, ChatGptCatalogStatus>>(emptyMap())
    val catalogStatus = _catalogStatus.asStateFlow()

    /** Observe restored settings and the asynchronously loaded account list once per process. */
    fun start() {
        if (observer != null) return
        observer = appScope.launch {
            combine(settingsStore.settingsFlow, accounts.accounts) { settings, knownAccounts ->
                if (settings.init) emptyList() else settings.providers.mapNotNull { provider ->
                    val openAI = provider as? ProviderSetting.OpenAI ?: return@mapNotNull null
                    val account = knownAccounts.find { it.id == openAI.chatGptAccountId }
                    if (account?.connected == true && account.planEnabled) CatalogOwner(openAI.id, account.id, openAI.enabled) else null
                }
            }.distinctUntilChanged().collect { eligible ->
                requestMutex.withLock {
                    requests.forEach { (id, request) ->
                        if (eligible.none { it.providerId == id && it.accountId == request.accountId && (it.enabled || request.allowDisabled.get()) }) request.job.cancel()
                    }
                }
                eligible.filter { it.enabled }.forEach { requestRefresh(it.providerId) }
            }
        }
    }

    /** Automatic refresh is owned by the application, and never changes the selected account. */
    fun requestRefresh(providerId: Uuid): Job = appScope.launch {
        try { refreshIfStale(providerId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The status row offers a manual retry; keep the existing catalog. */ }
    }

    suspend fun activateAndDiscover(providerId: Uuid, accountId: String): Int {
        // A startup observer may begin discovery while the settings write is still persisting.
        // Its refresh generation must not invalidate the explicit account selection.
        val activation = activationGenerations.computeIfAbsent(providerId) { AtomicLong() }
        val activationId = activation.incrementAndGet()
        generations.computeIfAbsent(providerId) { AtomicLong() }.incrementAndGet()
        requestMutex.withLock {
            requests.remove(providerId)?.job?.cancel()
            _loading.update { it - providerId }
        }
        settingsStore.update { current ->
            current.copy(providers = current.providers.map { existing ->
                if (existing.id == providerId && existing is ProviderSetting.OpenAI && activation.get() == activationId) {
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
        val selected = settingsStore.settingsFlow.value.providers.find { it.id == providerId } as? ProviderSetting.OpenAI
        if (selected?.chatGptAccountId != accountId || activation.get() != activationId) throw CancellationException("账户选择已变更")
        return refreshIfStale(providerId, force = true, expectedAccount = accountId) ?: throw CancellationException("账户连接已变更")
    }

    suspend fun refreshIfStale(providerId: Uuid, force: Boolean = false): Int? =
        refreshIfStale(providerId, force, expectedAccount = null)

    private suspend fun refreshIfStale(providerId: Uuid, force: Boolean, expectedAccount: String?): Int? {
        val request = requestMutex.withLock {
            val selected = settingsStore.settingsFlow.value.providers.find { it.id == providerId } as? ProviderSetting.OpenAI
                ?: return null
            val accountId = selected.chatGptAccountId?.takeIf { it.isNotBlank() } ?: return null
            if (expectedAccount != null && accountId != expectedAccount) throw CancellationException("账户选择已变更")
            val account = accounts.accounts.value.find { it.id == accountId }
            if ((!selected.enabled && !force) || account?.connected != true || !account.planEnabled) return null
            requests[providerId]?.takeIf { it.accountId == accountId && it.job.isActive }?.let {
                // A manual caller can reuse an automatic fetch while retaining its explicit authority.
                if (force) it.allowDisabled.set(true)
                return@withLock it
            }
            val cacheKey = "$providerId:$accountId"
            if (!refreshPolicy.shouldRefresh(cacheKey, force)) return null
            requests.remove(providerId)?.job?.cancel()
            val generation = generations.computeIfAbsent(providerId) { AtomicLong() }.incrementAndGet()
            val allowDisabled = AtomicBoolean(force)
            val deferred = appScope.async(start = CoroutineStart.LAZY) { discover(providerId, selected, generation, cacheKey, allowDisabled) }
            RefreshRequest(accountId, generation, allowDisabled, deferred).also {
                requests[providerId] = it
                _loading.update { loading -> loading + providerId }
                deferred.start()
            }
        }
        return request.job.await()
    }

    private suspend fun discover(providerId: Uuid, selected: ProviderSetting.OpenAI, generation: Long, cacheKey: String, allowDisabled: AtomicBoolean): Int {
        val accountId = selected.chatGptAccountId!!
        try {
            var accountJob: Job? = null
            val catalog = withTimeout(30_000L) {
                // Keep only the request lifecycle reference. Never save the resolved token-bearing copy.
                val resolved = accounts.resolveProvider(selected)
                accountJob = resolved.chatGptRequestJob
                providers.getProviderByType(resolved).listModels(resolved)
            }
            currentCoroutineContext().ensureActive()
            accountJob?.ensureActive()
            var applied = false
            settingsStore.update { current ->
                accountJob?.ensureActive()
                val account = accounts.accounts.value.find { it.id == accountId }
                current.copy(providers = current.providers.map { existing ->
                    if (existing.id == providerId && existing is ProviderSetting.OpenAI &&
                        catalogResponseIsCurrent(accountId, generation, existing.chatGptAccountId,
                            generations[providerId]?.get() ?: -1, account?.connected == true && account.planEnabled) && (existing.enabled || allowDisabled.get())) {
                        applied = true
                        existing.copy(models = mergeChatGptCatalog(existing.models, catalog))
                    } else existing
                })
            }
            if (!applied) throw CancellationException("账户连接已变更")
            // Saving settings suspends. Sign-out or a new activation during that write must not
            // cache a fresh success or report the old account as successfully refreshed.
            currentCoroutineContext().ensureActive()
            accountJob?.ensureActive()
            val live = settingsStore.settingsFlow.value.providers.find { it.id == providerId } as? ProviderSetting.OpenAI
            val account = accounts.accounts.value.find { it.id == accountId }
            if (!catalogResponseIsCurrent(accountId, generation, live?.chatGptAccountId,
                    generations[providerId]?.get() ?: -1, account?.connected == true && account.planEnabled) ||
                (live?.enabled != true && !allowDisabled.get())) throw CancellationException("账户连接已变更")
            refreshPolicy.succeeded(cacheKey)
            _catalogStatus.update { it + (providerId to ChatGptCatalogStatus(accountId, System.currentTimeMillis())) }
            return catalog.size
        } catch (_: TimeoutCancellationException) {
            recordFailure(providerId, accountId, generation, cacheKey)
            error("读取模型超时，请重试")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordFailure(providerId, accountId, generation, cacheKey)
            throw error
        } finally {
            withContext(NonCancellable) {
                requestMutex.withLock {
                    if (requests[providerId]?.generation == generation) {
                        requests.remove(providerId)
                        _loading.update { it - providerId }
                    }
                }
            }
        }
    }

    private fun recordFailure(providerId: Uuid, accountId: String, generation: Long, cacheKey: String) {
        refreshPolicy.failed(cacheKey)
        if (generations[providerId]?.get() == generation) {
            _catalogStatus.update {
                val previous = it[providerId]?.takeIf { status -> status.accountId == accountId }
                it + (providerId to ChatGptCatalogStatus(accountId, previous?.lastSuccessfulAt, "刷新失败，请重试"))
            }
        }
    }
}

data class ChatGptCatalogStatus(val accountId: String, val lastSuccessfulAt: Long? = null, val error: String? = null)

internal fun mergeChatGptCatalog(existing: List<Model>, catalog: List<Model>): List<Model> {
    val configured = existing.associateBy { it.modelId }
    val listedIds = catalog.mapTo(mutableSetOf()) { it.modelId }
    val refreshed = catalog.distinctBy { it.modelId }.map { incoming ->
        configured[incoming.modelId]?.let { it.copy(displayName = incoming.displayName) } ?: incoming
    }
    // Discovery can lag behind inference availability. Refresh adds current server choices,
    // but must not erase models a user configured explicitly. Account switches clear these
    // separately in activateAndDiscover, so choices never leak into another account.
    return refreshed + existing.distinctBy { it.modelId }.filterNot { it.modelId in listedIds }
}
