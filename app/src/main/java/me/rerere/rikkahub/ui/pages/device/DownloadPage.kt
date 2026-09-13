package me.rerere.rikkahub.ui.pages.device

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.device.DownloadInstallManager
import org.koin.compose.koinInject

@Composable
fun DownloadPage(manager: DownloadInstallManager = koinInject()) {
    var url by remember { mutableStateOf("") }; var id by remember { mutableStateOf<Long?>(null) }; var progress by remember { mutableStateOf(0f) }; var status by remember { mutableStateOf("未开始") }; val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        OutlinedTextField(url, { url = it }, label = { Text("HTTPS APK 地址") })
        Button(onClick = { val newId = runCatching { manager.enqueueApk(url, "download.apk") }.getOrNull(); id = newId; scope.launch { while (id != null) { manager.progress(id!!)?.let { p -> status = p.status.toString(); progress = if (p.total > 0) p.downloaded.toFloat() / p.total else 0f }; delay(500) } } }) { Text("开始下载") }
        Text(status); LinearProgressIndicator(progress = { progress })
    }
}
