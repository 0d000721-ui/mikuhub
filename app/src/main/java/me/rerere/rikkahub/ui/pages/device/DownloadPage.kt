package me.rerere.rikkahub.ui.pages.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Package
import me.rerere.rikkahub.device.DownloadInstallManager
import me.rerere.rikkahub.device.DownloadStatus
import me.rerere.rikkahub.device.DownloadTask
import me.rerere.rikkahub.device.InstallLaunchResult
import me.rerere.rikkahub.device.ApkInstallManager
import me.rerere.rikkahub.device.ApkInstallation
import me.rerere.rikkahub.device.ApkInstallStatus
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject
import java.net.URI

@Composable
fun DownloadPage(
    manager: DownloadInstallManager = koinInject(),
    installer: ApkInstallManager = koinInject(),
    appScope: AppScope = koinInject(),
) {
    val downloads by manager.downloads.collectAsStateWithLifecycle(initialValue = emptyList())
    val installations by installer.states.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    LaunchedEffect(installer) { installer.snapshot() }
    val managerIssue by manager.issue.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    var fileName by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val active = downloads.count { it.isActive }

    fun act(background: Boolean = false, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        notice = null
        (if (background) appScope else scope).launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                error = exception.message ?: "操作未完成，请稍后重试"
            } finally {
                busy = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("下载中心", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (active > 0) "$active 个任务进行中 · 离开页面仍会继续下载" else "文件直接保存到手机，支持文档、图片、压缩包和 APK",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { nav.navigate(Screen.DeviceControl) }) { Text("配置 Shizuku / Root 静默安装权限") }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("新建下载", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; error = null },
                        label = { Text("文件下载链接") },
                        placeholder = { Text("https://example.com/file.zip") },
                        leadingIcon = { Icon(HugeIcons.Link01, null) },
                        supportingText = { Text("输入 HTTP(S) 文件直链，支持 Wi-Fi 和移动网络") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text("文件名（可选）") },
                        placeholder = { Text("留空使用服务器文件名") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            act {
                                val id = manager.enqueueDownload(url, fileName.takeIf { it.isNotBlank() })
                                url = ""
                                fileName = ""
                                notice = "任务 #$id 已创建，系统会继续后台下载"
                            }
                        },
                        enabled = !busy && url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(HugeIcons.Download01, null, Modifier.size(20.dp))
                        Text(if (busy) "正在处理…" else "下载到手机", Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        val message = error ?: managerIssue ?: notice
        if (message != null) {
            item {
                Surface(
                    color = if (error != null || managerIssue != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(message, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (downloads.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(HugeIcons.Download01, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("还没有下载任务", style = MaterialTheme.typography.titleMedium)
                    Text("粘贴链接开始下载；应用更新也会显示在这里", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            item {
                Text("下载任务 · ${downloads.size}", style = MaterialTheme.typography.titleMedium)
            }
            items(downloads.sortedBy { if (it.isActive) 0 else 1 }, key = { it.id }) { task ->
                DownloadTaskCard(
                    task = task,
                    installation = installations[task.id],
                    enabled = !busy,
                    onSilentInstall = { act(background = true) {
                        val result = installer.install(task.id)
                        if (result.status == ApkInstallStatus.SUCCEEDED) notice = result.detail else error = result.detail
                    } },
                    onStopInstall = { installer.stop(task.id) },
                    onCancel = { act { manager.cancel(task.id) } },
                    onRetry = { act { manager.retry(task.id) } },
                    onOpen = {
                        act {
                            notice = if (task.isApk) when (manager.installDownloaded(task.id)) {
                                InstallLaunchResult.NEEDS_PERMISSION -> "开启“允许来自此来源”，返回后再次点击安装"
                                InstallLaunchResult.INSTALLER_OPENED -> "已打开系统安装界面"
                            } else {
                                manager.openDownloaded(task.id)
                                "已打开下载到手机的文件"
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    installation: ApkInstallation?,
    enabled: Boolean,
    onSilentInstall: () -> Unit,
    onStopInstall: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val source = remember(task.url) { runCatching { URI(task.url).host }.getOrNull() ?: "下载链接" }
    val statusColor = when (task.status) {
        DownloadStatus.FAILED -> colors.error
        DownloadStatus.READY, DownloadStatus.DOWNLOADING -> colors.primary
        else -> colors.onSurfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = colors.primaryContainer, shape = RoundedCornerShape(14.dp)) {
                    Icon(if (task.isApk) HugeIcons.Package else HugeIcons.File02, null, Modifier.padding(12.dp).size(24.dp), tint = colors.onPrimaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(task.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("$source · #${task.id}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(task.statusLabel, style = MaterialTheme.typography.labelLarge, color = statusColor)
                if (task.isActive || task.status == DownloadStatus.READY) {
                    Text(task.percent?.let { "$it%" } ?: "总大小未知", style = MaterialTheme.typography.titleMedium, color = statusColor)
                }
            }
            if (task.isActive || task.status == DownloadStatus.READY) {
                val fraction = task.progressFraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(task.sizeLabel, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            task.detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = statusColor)
            }
            installation?.let { install ->
                Surface(
                    color = if (install.status == ApkInstallStatus.FAILED) colors.errorContainer else colors.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (install.isActive) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(install.detail, style = MaterialTheme.typography.bodySmall)
                        if (install.isActive) TextButton(onClick = onStopInstall) { Text("停止安装等待") }
                    }
                }
            }
            if (task.isApk && (task.isActive || task.status == DownloadStatus.READY)) {
                FilledTonalButton(onClick = onSilentInstall, enabled = enabled && installation?.isActive != true,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (task.isActive) "下载完成后静默安装" else "静默安装 APK")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when {
                    task.isActive -> TextButton(onClick = onCancel, enabled = enabled) { Text("取消下载") }
                    task.status == DownloadStatus.READY -> TextButton(onClick = onOpen, enabled = enabled) { Text(if (task.isApk) "使用系统安装器" else "打开文件") }
                    task.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED, DownloadStatus.MISSING) ->
                        FilledTonalButton(onClick = onRetry, enabled = enabled) { Text("重新下载") }
                }
            }
        }
    }
}
