package me.rerere.rikkahub.ui.pages.device

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Internet
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMode
import me.rerere.rikkahub.device.DownloadInstallManager
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.compose.koinInject

@Composable
fun CustomFeaturesPage(settingsStore: SettingsStore = koinInject(), downloads: DownloadInstallManager = koinInject()) {
    val assistant = LocalSettings.current.getCurrentAssistant()
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val downloadTasks by downloads.downloads.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeDownloads = downloadTasks.filter { it.isActive }
    val downloadSummary = when (activeDownloads.size) {
        0 -> "进度、文件与安装"
        1 -> activeDownloads.first().let { task -> task.percent?.let { "正在下载 · $it%" } ?: task.statusLabel }
        else -> "${activeDownloads.size} 个任务正在下载"
    }
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }

    fun updateAssistant(transform: (Assistant) -> Assistant) {
        scope.launch {
            settingsStore.update { current ->
                current.copy(assistants = current.assistants.map {
                    if (it.id == assistant.id) transform(it) else it
                })
            }
        }
    }

    DevicePageScaffold("扩展中心") {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item("quickAccess") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FeatureShortcut(
                        title = "下载管理", description = downloadSummary, icon = HugeIcons.Download04,
                        modifier = Modifier.weight(1f), onClick = { nav.navigate(Screen.DeviceDownload) },
                    )
                    FeatureShortcut(
                        title = "上下文用量", description = "输入、输出与缓存", icon = HugeIcons.Brain02,
                        modifier = Modifier.weight(1f), onClick = { nav.navigate(Screen.DeviceUsage()) },
                    )
                }
            }
            item("assistant") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("当前助手", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(assistant.name.ifBlank { "默认助手" }, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { nav.navigate(Screen.AssistantBasic(assistant.id.toString())) }) { Text("设置") }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistantMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = assistant.mode == mode,
                                    onClick = { updateAssistant { it.copy(mode = mode) } },
                                    label = { Text(when (mode) {
                                        AssistantMode.ROLEPLAY -> "角色扮演"
                                        AssistantMode.NORMAL -> "常规"
                                        AssistantMode.WORK -> "工作"
                                    }) },
                                )
                            }
                        }
                        Text(when (assistant.mode) {
                            AssistantMode.ROLEPLAY -> "专注角色与剧情，隐藏思考过程。"
                            AssistantMode.NORMAL -> "自由对话，使用已配置的工具与记忆。"
                            AssistantMode.WORK -> "围绕任务执行，简洁汇报结果。"
                        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("思考强度", style = MaterialTheme.typography.titleSmall)
                            ReasoningButton(reasoningLevel = assistant.reasoningLevel, onUpdateReasoningLevel = { level -> updateAssistant { it.copy(reasoningLevel = level) } })
                        }
                        if (assistant.reasoningLevel == ReasoningLevel.ULTRA) {
                            Text("Ultra 请求 64,000 Token 思考预算，具体支持由模型决定。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item("tools") {
                CardGroup {
                    item(
                        onClick = { nav.navigate(Screen.AgentBrowser) },
                        leadingContent = { FeatureIcon(HugeIcons.Internet) },
                        headlineContent = { Text("浏览器") },
                        supportingContent = { Text("浏览网页、查找下载入口，可允许 AI 操作") },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                    item(
                        onClick = { nav.navigate(Screen.DeviceControl) },
                        leadingContent = { FeatureIcon(HugeIcons.Settings03) },
                        headlineContent = { Text("设备控制") },
                        supportingContent = { Text("Shizuku · Magisk / Root") },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                    item(
                        onClick = { nav.navigate(Screen.SettingTheme) },
                        leadingContent = { FeatureIcon(HugeIcons.Sun01) },
                        headlineContent = { Text("主题与配色") },
                        supportingContent = { Text("初音青绿与自定义主题") },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                }
            }
            item("diagnosticsToggle") {
                TextButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded }, modifier = Modifier.fillMaxWidth()) {
                    Text("诊断与记录", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Icon(if (diagnosticsExpanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01, null)
                }
            }
            if (diagnosticsExpanded) {
                item("diagnostics") {
                    CardGroup {
                        item(onClick = { nav.navigate(Screen.DevicePerformance) }, leadingContent = { FeatureIcon(HugeIcons.LookTop) }, headlineContent = { Text("设备状态") }, supportingContent = { Text("刷新率、电量、温度与 CPU") })
                        item(onClick = { nav.navigate(Screen.DeviceMemory) }, leadingContent = { FeatureIcon(HugeIcons.Database02) }, headlineContent = { Text("内存诊断") }, supportingContent = { Text("查看可访问进程的内存信息") })
                        item(onClick = { nav.navigate(Screen.DeviceAudit) }, leadingContent = { FeatureIcon(HugeIcons.Settings03) }, headlineContent = { Text("操作记录") }, supportingContent = { Text("设备命令、执行结果与错误") })
                        item(onClick = { nav.navigate(Screen.Stats) }, leadingContent = { FeatureIcon(HugeIcons.Brain02) }, headlineContent = { Text("历史用量统计") }, supportingContent = { Text("跨会话的 Token 使用记录") })
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureShortcut(title: String, description: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, modifier = Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun FeatureIcon(icon: ImageVector) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Icon(icon, null, modifier = Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
