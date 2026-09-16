package me.rerere.rikkahub.ui.components.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import me.rerere.ai.ui.RequestContextUsage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.latestContextMessage
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ContextUsageIndicator(
    conversation: Conversation,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reply = conversation.latestContextMessage()
    val request = reply?.requestContext
    val current = loading && reply?.finishedAt == null && request != null
    val input = request?.inputTokens ?: if (request == null) reply?.usage?.promptTokens?.takeIf { it > 0 } else null
    ContextUsageChip(request, input, current, conversation.messageNodes.size, onClick, modifier)
}

/** This leaf depends only on request metadata, so streamed message text cannot invalidate it. */
@Composable
private fun ContextUsageChip(
    request: RequestContextUsage?,
    input: Int?,
    current: Boolean,
    messageCount: Int,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val percent = request?.inputRatio?.let { "${(it * 100).toInt()}%" }
    // Streamed text is deliberately excluded from the memoization keys.
    val label = remember(request, input, current) {
        when {
            percent != null -> if (current) percent else "上 $percent"
            input != null && request == null -> "旧 ${compactContextTokens(input)}"
            input != null -> compactContextTokens(input)
            request != null && request.providerRequestCount > 1 -> "待返回"
            request != null -> "≈${compactContextTokens(request.estimatedTextTokens)}"
            else -> "上下文"
        }
    }
    val description = remember(request, input, current, messageCount) {
        buildString {
            append("上下文：")
            when {
                input != null && request == null -> append("旧记录输入 ${formatContextTokens(input.toLong())} tokens")
                input != null -> append("${if (current) "本次" else "上次"}输入 ${formatContextTokens(input.toLong())} tokens")
                request != null && request.providerRequestCount > 1 -> append("服务端续接，等待本次用量")
                request != null -> append("文本估算约 ${formatContextTokens(request.estimatedTextTokens.toLong())} tokens")
                else -> append("$messageCount 条消息，等待请求用量")
            }
            if (percent != null) append("，占登记容量 $percent")
            else if (request?.contextCapacity == null) append("，容量未知")
            request?.modelName?.let { append("，模型 $it") }
            append("。点击查看详情")
        }
    }
    ChatStatusChip(
        icon = HugeIcons.Brain02,
        label = label,
        description = description,
        onClick = onClick,
        modifier = modifier.testTag("chat_context_status"),
        active = current,
        error = (request?.inputRatio ?: 0f) >= 0.9f,
        progress = request?.inputRatio,
    )
}

internal fun formatContextTokens(value: Long): String = NumberFormat.getIntegerInstance().format(value)

private fun compactContextTokens(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.ROOT, "%.1fk", value / 1_000.0)
    else -> value.toString()
}
