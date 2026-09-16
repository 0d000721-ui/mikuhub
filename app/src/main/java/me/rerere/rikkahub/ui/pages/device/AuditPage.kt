package me.rerere.rikkahub.ui.pages.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.device.DeviceAuditStore
import me.rerere.rikkahub.device.DeviceCommandRisk
import me.rerere.rikkahub.device.DeviceTransport
import me.rerere.rikkahub.device.PersistedDeviceAudit
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date

@Composable
fun AuditPage(store: DeviceAuditStore = koinInject()) {
    var entries by remember { mutableStateOf<List<PersistedDeviceAudit>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var expandedEntry by remember { mutableStateOf<PersistedDeviceAudit?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load(clear: Boolean = false) {
        loading = true
        error = null
        try {
            entries = withContext(Dispatchers.IO) {
                if (clear) store.clear()
                store.read().asReversed()
            }
            if (clear) {
                expandedEntry = null
                if (entries.isNotEmpty()) error = "部分记录仍然存在，请刷新后重试。"
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error = exception.message ?: "读取记录失败，请重试。"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(store) { load() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("操作记录", style = MaterialTheme.typography.headlineSmall)
                    Text(if (loading && entries.isEmpty()) "正在读取本机记录" else "${entries.size} 条记录 · 仅保存在本机", style = MaterialTheme.typography.bodyMedium)
                    Text("查看设备命令、执行结果和拒绝原因，最多保留最近 500 条。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("最近操作", style = MaterialTheme.typography.titleSmall)
                Row {
                    TextButton(onClick = { scope.launch { load() } }, enabled = !loading) { Text("刷新") }
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = { scope.launch { load(clear = true) } }, enabled = !loading) {
                            Text("清空", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message -> item { Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
        if (!loading && entries.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("还没有设备操作", style = MaterialTheme.typography.titleMedium)
                        Text("执行或拒绝设备命令后，可在这里查看发生了什么。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        itemsIndexed(entries) { _, entry ->
            AuditEntryCard(entry = entry, expanded = expandedEntry == entry, onToggle = {
                expandedEntry = if (expandedEntry == entry) null else entry
            })
        }
    }
}

@Composable
private fun AuditEntryCard(entry: PersistedDeviceAudit, expanded: Boolean, onToggle: () -> Unit) {
    val timestamp = remember(entry.timestamp) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestamp))
    }
    val status = when {
        !entry.allowed -> "未执行"
        entry.error != null -> "执行异常"
        else -> "已执行"
    }
    val transport = when (entry.transport) {
        DeviceTransport.ADB -> "ADB"
        DeviceTransport.SHIZUKU -> "Shizuku"
        DeviceTransport.ROOT -> "Root"
    }
    val risk = when (entry.risk) {
        DeviceCommandRisk.READ_ONLY -> "只读操作"
        DeviceCommandRisk.MODIFIES_STATE -> "修改设备状态"
        DeviceCommandRisk.DESTRUCTIVE -> "破坏性操作"
        DeviceCommandRisk.KERNEL_CRITICAL -> "系统关键操作"
        DeviceCommandRisk.BLOCKED -> "禁止执行"
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(status, style = MaterialTheme.typography.labelLarge, color = if (entry.allowed && entry.error == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text("$transport · $risk", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SelectionContainer {
                Text(entry.command, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            }
            Text(timestamp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onToggle) { Text(if (expanded) "收起详情" else "查看执行详情") }
            if (expanded) {
                if (!entry.error.isNullOrBlank()) {
                    Text("错误或拒绝原因", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                    SelectionContainer { Text(entry.error.take(4_096), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                Text("命令输出", style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(entry.output.ifBlank { "没有输出" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
