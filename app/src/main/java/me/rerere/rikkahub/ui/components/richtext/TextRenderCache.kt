package me.rerere.rikkahub.ui.components.richtext

import kotlinx.coroutines.Dispatchers

internal val MarkdownParseDispatcher = Dispatchers.Default.limitedParallelism(2)

/** Small LRU for finished messages; bounded by both entry count and source text size. */
internal class TextRenderCache<T : Any>(
    private val maxEntries: Int = 16,
    private val maxCharacters: Int = 128_000,
) {
    private val entries = LinkedHashMap<String, T>(16, 0.75f, true)
    private var characters = 0

    init { require(maxEntries > 0 && maxCharacters > 0) }

    @Synchronized
    operator fun get(text: String): T? = entries[text]

    @Synchronized
    fun put(text: String, value: T) {
        val weight = text.length.coerceAtLeast(1)
        if (weight > maxCharacters) return
        if (entries.put(text, value) == null) characters += weight
        val iterator = entries.entries.iterator()
        while (entries.size > maxEntries || characters > maxCharacters) {
            val eldest = iterator.next()
            characters -= eldest.key.length.coerceAtLeast(1)
            iterator.remove()
        }
    }
}
