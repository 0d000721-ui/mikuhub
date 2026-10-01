package me.rerere.rikkahub.ui.pages.setting.components

internal class ChatGptCatalogRefreshPolicy(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val ttlMillis: Long = 15 * 60_000L,
    private val retryMillis: Long = 30_000L,
) {
    private val success = mutableMapOf<String, Long>()
    private val failures = mutableMapOf<String, Long>()

    @Synchronized fun shouldRefresh(key: String, force: Boolean = false): Boolean {
        if (force) return true
        val now = clock()
        if (success[key]?.let { now - it in 0 until ttlMillis } == true) return false
        if (failures[key]?.let { now - it in 0 until retryMillis } == true) return false
        return true
    }
    @Synchronized fun succeeded(key: String) { success[key] = clock(); failures.remove(key) }
    @Synchronized fun failed(key: String) { failures[key] = clock() }
}

internal fun catalogResponseIsCurrent(requestAccount: String, requestGeneration: Long, liveAccount: String?, liveGeneration: Long, connected: Boolean): Boolean =
    connected && requestAccount == liveAccount && requestGeneration == liveGeneration
