package me.rerere.rikkahub.ui.pages.device

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.device.DeviceAuditStore
import org.koin.compose.koinInject

@Composable
fun AuditPage(store: DeviceAuditStore = koinInject()) {
    var entries by remember { mutableStateOf(store.read()) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = { store.clear(); entries = emptyList() }) { Text("清空设备审计日志") }
        LazyColumn {
            items(entries.reversed()) { entry ->
                Text("${java.util.Date(entry.timestamp)}\n${entry.transport} / ${entry.risk} / ${if (entry.allowed) "允许" else "拒绝"}\n${entry.command}\n${entry.output}${entry.error?.let { "\n错误：$it" }.orEmpty()}", Modifier.padding(vertical = 8.dp))
            }
        }
    }
}
