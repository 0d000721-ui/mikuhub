package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun AgentRequestCard(
    title: String,
    status: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    warning: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(status, style = MaterialTheme.typography.labelMedium,
                    color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
internal fun AgentChoiceRow(
    number: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    description: String = "",
    multiple: Boolean = false,
    recommended: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = shape,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clip(shape).then(
            if (multiple) Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox) { onClick() }
            else Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        ),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("$number", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (recommended) Text("推荐", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (multiple) Checkbox(selected, onCheckedChange = null, enabled = enabled)
            else RadioButton(selected, onClick = null, enabled = enabled)
        }
    }
}

@Composable
internal fun AgentCodePreview(code: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
        SelectionContainer {
            Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(12.dp))
        }
    }
}

@Composable
internal fun ExecutionApprovalCard(
    requestKey: String,
    title: String,
    subtitle: String,
    command: String,
    onApprove: () -> Unit,
    onReject: (String) -> Unit,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
    status: String = "等待你的确认",
    approveLabel: String = "允许本次执行",
    allowFeedback: Boolean = true,
    details: @Composable ColumnScope.() -> Unit = {},
) {
    var choice by rememberSaveable(requestKey) { mutableIntStateOf(0) }
    var reason by rememberSaveable(requestKey) { mutableStateOf("") }
    // The service also rejects stale or repeated responses; latch taps while it updates the UI.
    var submitted by remember(requestKey) { mutableStateOf(false) }
    AgentRequestCard(title, if (submitted) "正在提交…" else status, modifier, subtitle, warning) {
        AgentCodePreview(command)
        details()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentChoiceRow(1, approveLabel, choice == 0, { choice = 0 }, enabled = !submitted)
            AgentChoiceRow(2, "拒绝本次执行", choice == 1, { choice = 1 },
                description = if (allowFeedback) "可以补充说明，让 AI 调整做法" else "停止这次操作",
                enabled = !submitted)
        }
        if (choice == 1 && allowFeedback) OutlinedTextField(
            value = reason, onValueChange = { reason = it }, label = { Text("告诉 AI 如何调整（可选）") },
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, enabled = !submitted,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { submitted = true; onReject("") }, enabled = !submitted) { Text("取消") }
            Button(onClick = {
                if (!submitted) {
                    submitted = true
                    if (choice == 0) onApprove() else onReject(reason.trim())
                }
            }, enabled = !submitted) { Text(if (submitted) "正在提交…" else if (choice == 0) "确认" else "提交拒绝") }
        }
    }
}
