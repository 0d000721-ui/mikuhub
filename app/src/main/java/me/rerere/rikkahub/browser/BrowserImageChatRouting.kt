package me.rerere.rikkahub.browser

import kotlin.uuid.Uuid

data class BrowserImageChatTarget(val conversationId: Uuid, val generation: Long)

/** A transfer belongs to the chat that started it, never whichever chat is visible at completion. */
internal class BrowserImageChatRouting {
    private var conversationId: Uuid? = null
    private var generation = 0L

    @Synchronized fun bind(id: Uuid, restart: Boolean = false) {
        if (restart || conversationId != id) {
            generation++
            conversationId = id
        }
    }

    @Synchronized fun capture(): BrowserImageChatTarget? = conversationId?.let { BrowserImageChatTarget(it, generation) }

    @Synchronized fun isCurrent(target: BrowserImageChatTarget): Boolean =
        target.conversationId == conversationId && target.generation == generation

    @Synchronized fun cancelPending() { generation++ }

    @Synchronized fun clear() { generation++; conversationId = null }
}
