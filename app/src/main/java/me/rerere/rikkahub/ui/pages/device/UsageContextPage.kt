package me.rerere.rikkahub.ui.pages.device

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.UsageSummary

@Composable
fun UsageContextPage(summary: UsageSummary = UsageSummary(0, 0, 0, 0, null), contextLength: Int? = null) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Token 总量：${summary.total}")
        Text("输入：${summary.prompt}，输出：${summary.completion}")
        Text("缓存命中：${summary.cached}，命中率：${summary.cacheHitRatio?.let { "%.1f%%".format(it * 100) } ?: "N/A"}")
        Text("上下文上限：${contextLength ?: "未知"}")
        Text("当前占用：${contextLength?.let { "%.1f%%".format(summary.total.toFloat() / it * 100) } ?: "未知"}")
    }
}
