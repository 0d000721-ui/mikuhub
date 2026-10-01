package me.rerere.rikkahub.browser

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Keep chat state and file ownership consistent when the database or post-commit indexing fails. */
internal suspend fun <T> persistBrowserImageMessage(
    previous: T,
    updated: T,
    current: () -> T,
    restore: (T) -> Unit,
    retainsImage: (T) -> Boolean,
    onRetained: (Boolean) -> Unit,
    persist: suspend () -> Unit,
    isPersisted: suspend () -> Boolean,
) = withContext(NonCancellable) {
    onRetained(true)
    try {
        persist()
    } catch (error: Exception) {
        // Search indexing can fail after the conversation transaction has already committed.
        val committed = runCatching { isPersisted() }.getOrNull()
        if (committed != true) {
            val latest = current()
            if (committed == false && latest === updated) {
                restore(previous)
                onRetained(false)
            } else {
                // Never delete a file referenced by another edit or an uncertain DB commit.
                onRetained(committed == null || retainsImage(latest))
            }
            throw error
        }
    }
}
