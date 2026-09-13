package me.rerere.rikkahub.ui.pages.device

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.device.PerformanceMonitor

@Composable
fun PerformancePage() {
    val context = LocalContext.current
    val monitor = remember(context) { PerformanceMonitor(context) }
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("尚未采样") }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(text)
        Button(onClick = { scope.launch { val s = monitor.sample(); text = "刷新率：${s.refreshRateHz} Hz\nCPU：${s.cpuFrequency ?: "不可读"}\n电池：${s.batteryPercent ?: "不可读"}%\n温度：${s.temperatureC ?: "不可读"}°C\n功耗：${s.powerWatts ?: "设备未提供"}" } }) { Text("采样性能") }
    }
}
