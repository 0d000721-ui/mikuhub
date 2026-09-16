package me.rerere.rikkahub.ui.pages.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.device.PerformanceMonitor
import me.rerere.rikkahub.device.PerformanceSnapshot
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PerformancePage() {
    val context = LocalContext.current
    val monitor = remember(context) { PerformanceMonitor(context) }
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<PerformanceSnapshot?>(null) }
    var sampledAt by remember { mutableStateOf<String?>(null) }
    var sampling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun sample() {
        sampling = true
        error = null
        try {
            snapshot = monitor.sample()
            sampledAt = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error = exception.message ?: "暂时无法读取设备数据，请稍后重试。"
        } finally {
            sampling = false
        }
    }

    LaunchedEffect(monitor) { sample() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("设备状态", style = MaterialTheme.typography.labelLarge)
                Text("性能快照", style = MaterialTheme.typography.headlineSmall)
                Text(sampledAt?.let { "最近采样 $it" } ?: "正在读取系统允许访问的数据", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (sampling) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PerformanceMetric(
                label = "屏幕刷新率",
                value = snapshot?.refreshRateHz?.takeIf { it > 0f }?.let { "${it.format(0)} Hz" },
                supporting = "当前显示模式",
                modifier = Modifier.weight(1f),
                loading = sampling && snapshot == null,
            )
            PerformanceMetric(
                label = "电池电量",
                value = snapshot?.batteryPercent?.let { "$it%" },
                supporting = "系统报告的剩余电量",
                modifier = Modifier.weight(1f),
                loading = sampling && snapshot == null,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PerformanceMetric(
                label = "CPU 0 频率",
                value = snapshot?.cpuFrequency?.toFloatOrNull()?.let { "${(it / 1000).format(0)} MHz" },
                supporting = "首个核心的瞬时频率",
                modifier = Modifier.weight(1f),
                loading = sampling && snapshot == null,
            )
            PerformanceMetric(
                label = "传感器温度",
                value = snapshot?.temperatureC?.let { "${it.format(1)} °C" },
                supporting = "系统首个温度传感器",
                modifier = Modifier.weight(1f),
                loading = sampling && snapshot == null,
            )
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { scope.launch { sample() } }, enabled = !sampling, modifier = Modifier.fillMaxWidth()) {
            Text(if (sampling) "正在采样…" else "刷新快照")
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("如何理解这些数据", style = MaterialTheme.typography.titleSmall)
                Text("屏幕刷新率不是应用实际帧率。温度传感器的位置由设备决定，不一定代表 CPU 温度。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("“不可读取”表示设备未提供该数据或限制了访问。采样只读取状态，不会修改性能设置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PerformanceMetric(label: String, value: String?, supporting: String, modifier: Modifier, loading: Boolean) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value ?: if (loading) "读取中" else "不可读取", style = MaterialTheme.typography.titleLarge)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Float.format(decimals: Int): String = String.format(Locale.getDefault(), "%.${decimals}f", this)
