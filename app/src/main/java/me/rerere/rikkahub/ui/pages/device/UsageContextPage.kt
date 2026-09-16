package me.rerere.rikkahub.ui.pages.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.flowOf
import me.rerere.ai.registry.ModelRegistry
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.contextSummary
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.ai.formatContextTokens
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.readStringPreference
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@OptIn(FlowPreview::class)
@Composable
fun UsageContextPage(conversationId: String? = null) {
    val context = LocalContext.current
    val nav = LocalNavController.current
    var id by remember(conversationId) { mutableStateOf<Uuid?>(null) }
    var resolved by remember(conversationId) { mutableStateOf(false) }
    LaunchedEffect(conversationId) {
        id = withContext(Dispatchers.IO) {
            (conversationId ?: context.readStringPreference("lastConversationId"))?.let {
                runCatching { Uuid.parse(it) }.getOrNull()
            }
        }
        resolved = true
    }
    if (!resolved) {
        Column(Modifier.padding(20.dp)) { CircularProgressIndicator() }
        return
    }
    val selectedId = id
    if (selectedId == null) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("还没有当前会话", style = MaterialTheme.typography.titleLarge)
            Text("开始聊天后，聊天页顶部会显示每次请求的上下文；点击即可查看输入、输出与缓存用量。")
            TextButton(onClick = { nav.navigate(Screen.Stats) }) { Text("查看历史用量") }
        }
        return
    }
    val service: ChatService = koinInject()
    val store: SettingsStore = koinInject()
    val repository: ConversationRepository = koinInject()
    val liveSource = remember(selectedId, service) { service.getExistingConversationFlow(selectedId) }
    var savedConversation by remember(selectedId) { mutableStateOf<Conversation?>(null) }
    var savedResolved by remember(selectedId) { mutableStateOf(false) }
    var savedFailed by remember(selectedId) { mutableStateOf(false) }
    LaunchedEffect(selectedId, liveSource) {
        if (liveSource == null) {
            val result = runCatching { withContext(Dispatchers.IO) { repository.getConversationById(selectedId) } }
            savedConversation = result.getOrNull()
            savedFailed = result.isFailure
            savedResolved = true
        }
    }
    // The details page refreshes totals without scanning history for every streamed character.
    val sampled = remember(liveSource, savedConversation) {
        liveSource?.sample(400) ?: flowOf(savedConversation)
    }
    val snapshot by sampled.collectAsStateWithLifecycle(initialValue = liveSource?.value ?: savedConversation)
    val conversation = snapshot
    if (conversation == null) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!savedResolved) CircularProgressIndicator() else {
                Text(if (savedFailed) "会话暂时无法读取" else "该会话已不存在", style = MaterialTheme.typography.titleMedium)
                Text("返回聊天页后，可从需要查看的会话顶部打开上下文详情。")
            }
        }
        return
    }
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val assistant = settings.assistants.firstOrNull { it.id == conversation.assistantId } ?: settings.getCurrentAssistant()
    val summary = remember(conversation, assistant.contextMessageLimit) { conversation.contextSummary(assistant.contextMessageLimit) }
    val request = summary.latestMessage?.requestContext
    val oldUsage = summary.latestMessage?.usage.takeIf { request == null }
    // Old usage records can include multiple server-side requests; they are not context occupancy.
    val inputTokens = request?.inputTokens
    val usage = request?.providerUsage ?: oldUsage
    val oldModel = summary.latestMessage?.modelId?.let { settings.findModelById(it) }
    val requestCapacity = request?.contextCapacity
    val ratio = inputTokens?.let { tokens -> requestCapacity?.takeIf { it > 0 }?.let { tokens.toFloat() / it } }
    val currentModel = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
    val currentCapacity = currentModel?.let { ModelRegistry.MODEL_CONTEXT_LENGTH.getData(it.modelId) }
    var showExplanation by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Text(conversation.title.ifBlank { "当前会话" }, style = MaterialTheme.typography.titleMedium)
            Text("仅统计当前选择的消息分支", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            ContextCard(highlighted = true) {
                Text("最近请求 · 输入上下文", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = inputTokens?.let { "${formatContextTokens(it.toLong())} tokens" }
                        ?: request?.takeIf { it.providerRequestCount == 1 }?.let { "≈ ${formatContextTokens(it.estimatedTextTokens.toLong())} tokens" }
                        ?: "等待请求用量",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    when {
                        inputTokens != null -> "供应商返回的输入用量"
                        request != null && request.providerRequestCount > 1 -> "服务端续接 · 等待本次输入用量"
                        request != null -> "文本估算 · 尚未收到供应商输入用量"
                        else -> "发送消息后自动记录，旧回复若没有用量记录会显示未知。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                (request?.modelName ?: oldModel?.displayName)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (ratio != null) {
                    LinearProgressIndicator(progress = { ratio.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("输入占登记容量 ${"%.1f".format(ratio * 100)}% · 上限 ${formatContextTokens(requestCapacity!!.toLong())} tokens", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(requestCapacity?.let { "登记容量 ${formatContextTokens(it.toLong())} tokens" } ?: "模型容量未知 · 不计算占用百分比", style = MaterialTheme.typography.bodySmall)
                }
                Text("发送后更新 · 输出仍需预留容量", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            ContextCard {
                Text(if (request != null) "最近请求用量 · tokens" else "旧回复用量记录 · tokens", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContextMetric("输入", (inputTokens ?: oldUsage?.promptTokens)?.toLong(), Modifier.weight(1f))
                    ContextMetric("输出", usage?.completionTokens?.toLong(), Modifier.weight(1f))
                    ContextMetric("缓存", usage?.cachedTokens?.toLong(), Modifier.weight(1f))
                }
                if (request != null && request.providerRequestCount > 1) Text("服务端连续请求 · 第 ${request.providerRequestCount} 次", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            ContextCard {
                Text("当前历史保留规则", style = MaterialTheme.typography.titleSmall)
                ContextValueRow("当前分支", "${summary.totalMessages} 条消息")
                ContextValueRow("按现有消息应用规则后", "${summary.retainedMessages} 条保留")
                ContextValueRow("上下文消息限制", if (summary.contextMessageLimit > 0) "${summary.contextMessageLimit} 条" else "不限制")
                currentModel?.let { ContextValueRow("下次使用模型", it.displayName.ifBlank { it.modelId }) }
                ContextValueRow("下次模型登记容量", currentCapacity?.let { "${formatContextTokens(it.toLong())} tokens" } ?: "未知")
            }
        }
        item {
            ContextCard {
                TextButton(onClick = { showExplanation = !showExplanation }) { Text(if (showExplanation) "收起统计说明 ⌃" else "统计说明与累计记录 ⌄") }
                if (showExplanation) {
                    if (request != null) {
                        ContextValueRow("应用准备消息", "${request.inputMessageCount} 条")
                        ContextValueRow("请求前保留历史", "${request.retainedMessageCount} / ${request.conversationMessageCount} 条")
                        ContextValueRow("工具 / 媒体", "${request.toolCount} / ${request.mediaPartCount}")
                    }
                    Text("输入快照对应最近一次请求，新的回复、草稿和工具结果不在上次输入中。服务端连续请求时，准备消息数对应初始请求，用量对应最新一次。", style = MaterialTheme.typography.bodySmall)
                    Text("文本估算不含图片、音视频、工具定义、推理状态及协议开销，以供应商用量为准。模型容量来自内置登记，实际限制以供应商为准。缓存或输出字段未回传时，零值可能表示未报告。", style = MaterialTheme.typography.bodySmall)
                    Text("历史保留数量应用助手的阶梯截断与工具依赖规则，不含未发送草稿；系统提示词、记忆及附件转换会在请求时加入。", style = MaterialTheme.typography.bodySmall)
                    ContextValueRow("累计已记录输入", "${formatContextTokens(summary.recordedPromptTokens)} tokens")
                    ContextValueRow("累计已记录输出", "${formatContextTokens(summary.recordedOutputTokens)} tokens")
                    Text("当前分支 ${summary.recordedUsageMessages} 条回复的累计记录，不代表上下文占用或完整账单。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { nav.navigate(Screen.Stats) }) { Text("查看全部聊天统计") }
                }
            }
        }
    }
}

@Composable
private fun ContextCard(highlighted: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun ContextMetric(label: String, value: Long?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value?.let { formatContextTokens(it) } ?: "未返回", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ContextValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}
