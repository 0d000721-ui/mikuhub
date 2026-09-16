package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.ui.AgentChoiceRow
import me.rerere.rikkahub.ui.components.ui.AgentCodePreview
import me.rerere.rikkahub.ui.components.ui.AgentRequestCard
import me.rerere.rikkahub.ui.components.ui.ExecutionApprovalCard
import me.rerere.rikkahub.utils.JsonInstantPretty

@Composable
internal fun AgentInteractionCard(
    tool: UIMessagePart.Tool,
    onToolApproval: ((String, Boolean, String) -> Unit)?,
    onToolAnswer: ((String, String) -> Unit)?,
) {
    if (tool.toolName == "ask_user") {
        AgentQuestionCard(tool, onToolAnswer, onToolApproval)
        return
    }
    val arguments = remember(tool.input) { tool.inputAsJson() }
    val formattedInput = remember(tool.input) {
        runCatching { JsonInstantPretty.encodeToString(Json.parseToJsonElement(tool.input)) }.getOrDefault(tool.input)
    }
    fun argument(name: String) = ((arguments as? JsonObject)?.get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val command = argument("command").ifBlank { argument("cmd") }
    val purpose = argument("justification").ifBlank { argument("explanation") }.ifBlank { argument("reason") }
    if (onToolApproval != null && tool.isPending) {
        ExecutionApprovalCard(
            requestKey = tool.toolCallId + tool.input,
            title = when (tool.toolName) {
                "download_start", "browser_download" -> "允许下载这个文件？"
                "download_cancel" -> "取消这个下载任务？"
                "browser_navigate" -> "允许打开这个网页？"
                "browser_click" -> "允许点击这个网页元素？"
                "browser_fill" -> "允许填写这个网页字段？"
                else -> if (command.isNotBlank()) "允许执行这条指令？" else "允许 AI 使用这个工具？"
            },
            subtitle = tool.toolName,
            command = command.ifBlank { formattedInput },
            onApprove = { onToolApproval(tool.toolCallId, true, "") },
            onReject = { onToolApproval(tool.toolCallId, false, it) },
            details = {
                if (purpose.isNotBlank()) Text(purpose, style = MaterialTheme.typography.bodyMedium)
                if (command.isNotBlank()) {
                    if (argument("cwd").isNotBlank()) Text("工作目录：${argument("cwd")}", style = MaterialTheme.typography.bodySmall)
                    var showArguments by rememberSaveable(tool.toolCallId) { mutableStateOf(false) }
                    TextButton(onClick = { showArguments = !showArguments }) { Text(if (showArguments) "收起完整参数" else "查看完整参数") }
                    if (showArguments) AgentCodePreview(formattedInput)
                }
            },
        )
    } else {
        AgentRequestCard("等待执行确认", "只读记录", subtitle = tool.toolName) {
            AgentCodePreview(command.ifBlank { formattedInput })
        }
    }
}

@Composable
private fun AgentQuestionCard(
    tool: UIMessagePart.Tool,
    onToolAnswer: ((String, String) -> Unit)?,
    onToolApproval: ((String, Boolean, String) -> Unit)?,
) {
    val parsed = remember(tool.input) { parseAgentQuestions(tool.input) }
    val questions = parsed.getOrDefault(emptyList())
    val state = tool.approvalState
    val pending = tool.isPending
    var draftJson by rememberSaveable(tool.toolCallId, tool.input) { mutableStateOf("{}") }
    val drafts = remember(draftJson) {
        runCatching { Json.decodeFromString<Map<String, AgentQuestionDraft>>(draftJson) }.getOrDefault(emptyMap())
    }
    var questionIndex by rememberSaveable(tool.toolCallId, tool.input) { mutableIntStateOf(0) }
    var submitted by remember(tool.toolCallId, tool.approvalState) { mutableStateOf(false) }
    var expanded by rememberSaveable(tool.toolCallId) { mutableStateOf(false) }
    val interactive = pending && onToolAnswer != null && !submitted
    val status = when {
        state is ToolApprovalState.Answered -> "已回答"
        state is ToolApprovalState.Denied -> "已跳过"
        submitted -> "正在提交…"
        pending -> "等待你的回答"
        state is ToolApprovalState.Auto && !tool.isExecuted -> "正在整理问题…"
        else -> "问题记录"
    }
    AgentRequestCard(
        title = if (questions.size <= 1) "AI 需要你补充信息" else "AI 有 ${questions.size} 个问题",
        status = status,
    ) {
        if (questions.isEmpty()) {
            Text(if (pending) "${parsed.exceptionOrNull()?.message ?: "问题格式无效"}，可以跳过并让 AI 重新提问。" else "${tool.toolName}…",
                style = MaterialTheme.typography.bodyMedium)
            if (pending) AgentCodePreview(tool.input)
        } else if (pending) {
            val index = questionIndex.coerceIn(0, questions.lastIndex)
            val question = questions[index]
            val draft = drafts[question.id] ?: AgentQuestionDraft()
            fun update(value: AgentQuestionDraft) { draftJson = Json.encodeToString(drafts + (question.id to value)) }
            Text("问题 ${index + 1} / ${questions.size}" + question.header.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(question.question, style = MaterialTheme.typography.titleSmall)
            Column(
                modifier = if (question.multiple) Modifier else Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                question.options.forEachIndexed { optionIndex, option ->
                    AgentChoiceRow(
                        number = optionIndex + 1,
                        label = option.label,
                        description = option.description,
                        recommended = question.recommendedOption == option.label,
                        multiple = question.multiple,
                        selected = option.label in draft.selected && (question.multiple || !draft.custom),
                        enabled = interactive,
                        onClick = {
                            update(if (question.multiple) draft.copy(selected = if (option.label in draft.selected) draft.selected - option.label else draft.selected + option.label)
                            else draft.copy(selected = listOf(option.label), custom = false))
                        },
                    )
                }
                if (question.options.isNotEmpty()) AgentChoiceRow(
                    number = question.options.size + 1,
                    label = if (question.multiple) "补充自己的回答" else "填写其他答案",
                    selected = draft.custom,
                    onClick = { update(draft.copy(custom = if (question.multiple) !draft.custom else true)) },
                    multiple = question.multiple,
                    enabled = interactive,
                )
            }
            if (question.options.isEmpty() || draft.custom) OutlinedTextField(
                value = draft.text, onValueChange = { update(draft.copy(text = it)) },
                modifier = Modifier.fillMaxWidth(), label = { Text("你的回答") },
                placeholder = { Text("输入你的想法或要求…") }, minLines = 2, maxLines = 5, enabled = interactive,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (questions.size > 1) Text("已回答 ${questions.count { it.answer(drafts[it.id] ?: AgentQuestionDraft()).isNotBlank() }} / ${questions.size}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { questionIndex = index - 1 }, enabled = interactive && index > 0) { Text("上一题") }
                if (index < questions.lastIndex) {
                    Button(onClick = { questionIndex = index + 1 }, enabled = interactive && question.answer(draft).isNotBlank()) { Text("下一题") }
                } else {
                    val payload = buildAgentAnswers(questions, drafts)
                    Button(onClick = {
                        if (!submitted && payload != null) { submitted = true; onToolAnswer?.invoke(tool.toolCallId, payload) }
                    }, enabled = interactive && payload != null) { Text(if (submitted) "正在提交…" else "提交回答") }
                }
            }
        } else {
            val answers = remember(state) { readAgentAnswers((state as? ToolApprovalState.Answered)?.answer.orEmpty()) }
            val visible = if (expanded) questions else questions.take(1)
            visible.forEach { question ->
                Text(question.question, style = MaterialTheme.typography.bodyMedium)
                if (state is ToolApprovalState.Answered) Text(answers[question.id] ?: state.answer,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (questions.size > 1) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起回答" else "查看全部 ${questions.size} 个回答") }
            if (state is ToolApprovalState.Denied && state.reason.isNotBlank()) Text(state.reason,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (pending && onToolApproval != null) TextButton(
            onClick = {
                if (!submitted) {
                    submitted = true
                    onToolApproval(tool.toolCallId, false, if (questions.isEmpty()) "问题格式无效，请重新提问。" else "用户跳过了这组问题，没有提供答案。")
                }
            }, enabled = !submitted, modifier = Modifier.align(Alignment.End),
        ) { Text("跳过这组问题") }
    }
}
