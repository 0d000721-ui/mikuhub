package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.*
import org.junit.Test

class TextRenderCacheTest {
    @Test fun reusesFinishedParseWithoutRebuilding() {
        val cache = TextRenderCache<Any>()
        val parsed = Any()
        cache.put("# Hello", parsed)
        assertSame(parsed, cache["# Hello"])
    }

    @Test fun evictsLeastRecentlyReadMessage() {
        val cache = TextRenderCache<Int>(maxEntries = 2)
        cache.put("a", 1)
        cache.put("b", 2)
        assertEquals(1, cache["a"])
        cache.put("c", 3)
        assertNull(cache["b"])
        assertEquals(1, cache["a"])
        assertEquals(3, cache["c"])
    }

    @Test fun boundsTotalTextSizeAndRejectsOversizedEntries() {
        val cache = TextRenderCache<Int>(maxCharacters = 5)
        cache.put("abc", 1)
        cache.put("def", 2)
        assertNull(cache["abc"])
        assertEquals(2, cache["def"])
        cache.put("too large", 3)
        assertNull(cache["too large"])
        assertEquals(2, cache["def"])
    }

    @Test fun replacingValueDoesNotDoubleCountItsSize() {
        val cache = TextRenderCache<Int>(maxCharacters = 4)
        cache.put("ab", 1)
        cache.put("ab", 2)
        cache.put("cd", 3)
        assertEquals(2, cache["ab"])
        assertEquals(3, cache["cd"])
    }
}
