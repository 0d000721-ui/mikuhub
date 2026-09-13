package me.rerere.rikkahub.data.model

import me.rerere.ai.core.TokenUsage

data class UsageSummary(val prompt: Long, val completion: Long, val total: Long, val cached: Long, val cacheHitRatio: Float?, val contextUsageRatio: Float? = null)

fun List<TokenUsage>.summary(): UsageSummary {
    val prompt = sumOf { it.promptTokens.toLong() }; val completion = sumOf { it.completionTokens.toLong() }; val total = sumOf { it.totalTokens.toLong() }; val cached = sumOf { it.cachedTokens.toLong() }
    return UsageSummary(prompt, completion, total, cached, cached.takeIf { prompt > 0 }?.toFloat()?.div(prompt))
}
