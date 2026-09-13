package me.rerere.rikkahub.ui.pages.device

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.device.DebugCertificateStore
import org.koin.compose.koinInject

@Composable
fun TrafficDebugPage(store: DebugCertificateStore = koinInject()) {
    var info by remember { mutableStateOf(store.info()) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("本地流量调试")
        Text("仅在你主动启动 VPN 后采集；不会绕过证书固定。")
        Button(onClick = { info = store.generate() }) { Text("生成调试证书") }
        info?.let { Text("SHA-256：${it.fingerprintSha256}") }
        Button(onClick = { store.revoke(); info = null }) { Text("撤销调试证书") }
    }
}
