package me.rerere.rikkahub.ui.pages.device

import android.os.Process
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.device.MemoryDiagnostics
import me.rerere.rikkahub.device.ProcessMemoryInfo
import java.util.Locale

@Composable
fun MemoryDiagnosticsPage() {
    val ownPid = remember { Process.myPid() }
    val diagnostics = remember { MemoryDiagnostics() }
    val scope = rememberCoroutineScope()
    var pidText by rememberSaveable { mutableStateOf(ownPid.toString()) }
    var result by remember { mutableStateOf<ProcessMemoryInfo?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var inputError by remember { mutableStateOf(false) }
    var showMaps by rememberSaveable { mutableStateOf(false) }

    suspend fun inspect(pidValue: String) {
        val pid = pidValue.toIntOrNull()?.takeIf { it > 0 }
        if (pid == null) {
            inputError = true
            return
        }
        loading = true
        inputError = false
        error = null
        result = null
        showMaps = false
        try {
            // inspect() reads /proc on Dispatchers.IO and respects the process access boundary.
            result = diagnostics.inspect(pid)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error = when (exception.message) {
                "Process is unavailable" -> "找不到该进程，可能已退出，或系统不允许访问。"
                "Invalid pid" -> "请输入有效的进程 PID。"
                else -> "无法读取该进程：${exception.message ?: "系统限制了访问"}"
            }
        } finally {
            loading = false
        }
    }

    LaunchedEffect(diagnostics) { inspect(pidText) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("内存诊断", style = MaterialTheme.typography.headlineSmall)
                Text("查看进程占用与地址映射", style = MaterialTheme.typography.bodyMedium)
                Text("默认读取本应用。其他进程是否可读取决于 Android 权限；此页不提权、不修改内存。", style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("目标进程", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { pidText = ownPid.toString(); scope.launch { inspect(pidText) } }, enabled = !loading) { Text("本应用") }
                }
                OutlinedTextField(
                    value = pidText,
                    onValueChange = { pidText = it.filter(Char::isDigit).take(10); inputError = false },
                    label = { Text("进程 PID") },
                    supportingText = { Text(if (inputError) "请输入大于 0 的有效 PID" else "本应用 PID：$ownPid") },
                    isError = inputError,
                    enabled = !loading,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { scope.launch { inspect(pidText) } }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                    Text(if (loading) "正在读取…" else "读取内存信息")
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
        result?.let { info ->
            Text("PID ${info.pid} · 采样结果", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MemoryMetric(
                    title = "常驻内存 RSS",
                    value = info.rssKb?.let { String.format(Locale.getDefault(), "%.1f MiB", it / 1024.0) } ?: "不可读取",
                    supporting = "当前驻留在物理内存中",
                    modifier = Modifier.weight(1f),
                )
                MemoryMetric(
                    title = "进程 UID",
                    value = info.uid?.toString() ?: "不可读取",
                    supporting = "系统分配的用户标识",
                    modifier = Modifier.weight(1f),
                )
            }
            if (info.uid == null && info.rssKb == null && info.maps.isEmpty()) {
                Text("系统没有开放该进程的诊断数据，可切回本应用后重试。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("地址映射", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            info.maps.isEmpty() -> "映射不可读取或没有记录"
                            info.maps.size >= 500 -> "已读取 500 条（读取上限）"
                            else -> "已读取 ${info.maps.size} 条映射"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("映射描述进程使用的内存区域，不包含内存中的实际内容。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (info.maps.isNotEmpty()) {
                        TextButton(onClick = { showMaps = !showMaps }) { Text(if (showMaps) "收起映射" else "查看映射摘要") }
                        if (showMaps) {
                            val displayed = remember(info.maps) { info.maps.take(40).joinToString("\n") }
                            Text("展示前 ${minOf(info.maps.size, 40)} 条，左右滑动可查看完整路径。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer {
                                Text(displayed, modifier = Modifier.horizontalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryMetric(title: String, value: String, supporting: String, modifier: Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
