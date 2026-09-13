package me.rerere.rikkahub.ui.pages.device

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.device.MemoryDiagnostics

@Composable
fun MemoryDiagnosticsPage() {
    val scope = rememberCoroutineScope(); var pid by remember { mutableStateOf("") }; var result by remember { mutableStateOf("仅支持自有或 debuggable 目标的只读诊断") }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("内存诊断（只读）")
        OutlinedTextField(pid, { pid = it }, label = { Text("PID") })
        Button(onClick = { scope.launch { result = runCatching { MemoryDiagnostics().inspect(pid.toInt()) }.fold({ "PID ${it.pid}\nUID ${it.uid}\nRSS ${it.rssKb} KB\n映射 ${it.maps.size} 条" }, { "失败：${it.message}" }) } }) { Text("读取内存信息") }
        Text(result)
    }
}
