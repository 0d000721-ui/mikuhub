package me.rerere.rikkahub.data.model

import me.rerere.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageSummaryTest {
    @Test fun aggregatesCacheHitRatio() {
        val summary = listOf(TokenUsage(promptTokens = 100, completionTokens = 20, totalTokens = 120, cachedTokens = 25)).summary()
        assertEquals(120L, summary.total)
        assertEquals(0.25f, summary.cacheHitRatio!!, 0.001f)
    }
}
