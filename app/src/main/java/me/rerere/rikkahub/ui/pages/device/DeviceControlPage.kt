package me.rerere.rikkahub.ui.pages.device

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ModelAbility
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.device.DeviceAccessSession
import me.rerere.rikkahub.device.DeviceAuthorization
import me.rerere.rikkahub.device.DeviceCommandController
import me.rerere.rikkahub.device.DeviceTransport
import me.rerere.rikkahub.device.RootDeviceCommandRunner
import me.rerere.rikkahub.device.RootState
import me.rerere.rikkahub.device.ShizukuManager
import me.rerere.rikkahub.device.ShizukuState
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.compose.koinInject

@Composable
fun DeviceControlPage(
    shizuku: ShizukuManager = koinInject(),
    session: DeviceAccessSession = koinInject(),
    settingsStore: SettingsStore = koinInject(),
    controller: DeviceCommandController = koinInject(),
    root: RootDeviceCommandRunner = koinInject(),
) {
    val context = LocalContext.current
    val state by shizuku.state.collectAsStateWithLifecycle()
    val message by shizuku.message.collectAsStateWithLifecycle()
    val sessionState by session.state.collectAsStateWithLifecycle()
    val rootStatus by root.status.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val assistant = settings.getCurrentAssistant()
    val toolsEnabled = LocalToolOption.DeviceCommands in assistant.localTools
    val modelSupportsTools = settings.getCurrentChatModel()?.abilities?.contains(ModelAbility.TOOL) == true
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var launchError by remember { mutableStateOf<String?>(null) }
    var diagnostic by remember { mutableStateOf<String?>(null) }
    var diagnosticSuccess by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var rootTesting by remember { mutableStateOf(false) }
    var rootResult by remember { mutableStateOf<String?>(null) }
    var rootSuccess by remember { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val sessionAvailable = !sessionState.stopped && sessionState.authorization != DeviceAuthorization.REVOKED
    val useRoot = sessionState.preferredTransport == DeviceTransport.ROOT
    val transportReady = if (useRoot) rootStatus.state == RootState.AUTHORIZED else state == ShizukuState.AUTHORIZED
    val busy = testing || rootTesting

    DisposableEffect(lifecycleOwner, shizuku) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) shizuku.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        shizuku.refresh()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openShizuku() {
        launchError = try {
            val intent = context.packageManager.getLaunchIntentForPackage(ShizukuManager.MANAGER_PACKAGE)
            if (intent == null) {
                "未找到 Shizuku，请先安装并启动服务。"
            } else {
                context.startActivity(intent)
                null
            }
        } catch (error: Exception) {
            "无法打开 Shizuku：${error.message ?: error.javaClass.simpleName}"
        }
    }

    fun testShizuku() {
        scope.launch {
            testing = true
            diagnostic = null
            try {
                val result = controller.execute("id", "验证 Shizuku 命令通道的实际运行身份", "shizuku")
                diagnosticSuccess = result["success"]?.jsonPrimitive?.booleanOrNull == true
                diagnostic = result["error"]?.jsonPrimitive?.content ?: result["output"]?.jsonPrimitive?.content
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnosticSuccess = false
                diagnostic = error.message ?: "验证失败，请重试。"
            } finally {
                testing = false
            }
        }
    }

    fun testRoot() {
        scope.launch {
            rootTesting = true
            rootResult = null
            try {
                val result = controller.execute(
                    command = "id",
                    explanation = "向 Magisk / Root 管理器请求授权并验证当前身份",
                    requestedTransport = "root",
                    impact = "仅输出 UID 和用户组；成功后将本次会话设备通道设为 Root",
                    riskExplanation = "本次测试不修改文件或系统设置；后续 Root 操作仍按命令单独确认",
                )
                rootSuccess = result["success"]?.jsonPrimitive?.booleanOrNull == true
                rootResult = result["error"]?.jsonPrimitive?.content ?: result["output"]?.jsonPrimitive?.content
                if (rootSuccess) session.selectTransport(DeviceTransport.ROOT)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                rootSuccess = false
                rootResult = error.message ?: "Root 验证失败，请重试。"
            } finally {
                rootTesting = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("设备助手", style = MaterialTheme.typography.labelLarge)
                Text(
                    when {
                        !sessionAvailable -> "设备操作已暂停"
                        !toolsEnabled -> "开启助手的设备工具"
                        !modelSupportsTools -> "需要支持工具的模型"
                        !transportReady -> "完成通道授权后即可使用"
                        else -> "已准备好接收设备任务"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "${assistant.name.ifBlank { "默认助手" }} · ${if (useRoot) "Magisk / Root" else "Shizuku"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        DeviceControlCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("允许助手操作设备", style = MaterialTheme.typography.titleMedium)
                    Text("对当前助手生效，开启后可在聊天中提出设备任务。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = toolsEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settingsStore.update { current ->
                                current.copy(assistants = current.assistants.map {
                                    if (it.id != assistant.id) it else it.copy(localTools =
                                        if (enabled) (it.localTools + LocalToolOption.DeviceCommands).distinct()
                                        else it.localTools - LocalToolOption.DeviceCommands)
                                })
                            }
                        }
                    },
                )
            }
            if (toolsEnabled && !modelSupportsTools) {
                Text("当前模型未启用工具调用。请在模型设置中开启，或切换到支持工具调用的模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        DeviceControlCard {
            Text("连接方式", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !useRoot, onClick = { session.selectTransport(DeviceTransport.SHIZUKU) }, enabled = !busy, label = { Text("Shizuku") })
                FilterChip(selected = useRoot, onClick = { session.selectTransport(DeviceTransport.ROOT) }, enabled = !busy, label = { Text("Magisk / Root") })
            }
            HorizontalDivider()
            if (useRoot) {
                Text(
                    when (rootStatus.state) {
                        RootState.NOT_CHECKED -> "尚未验证 Root"
                        RootState.CHECKING -> "正在等待 Root 授权"
                        RootState.AUTHORIZED -> "Root 上次验证通过"
                        RootState.UNAVAILABLE -> "未找到可用的 Root"
                        RootState.DENIED -> "Root 未获授权"
                        RootState.ERROR -> "Root 验证失败"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("在 Magisk 超级用户弹窗中允许本应用。验证只读取身份，后续每次 Root 操作仍需单独确认。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rootStatus.state in setOf(RootState.DENIED, RootState.ERROR, RootState.UNAVAILABLE)) {
                    Text(rootStatus.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = ::testRoot, enabled = !busy && sessionAvailable, modifier = Modifier.fillMaxWidth()) {
                    Text(if (rootTesting) "等待授权并验证…" else if (rootStatus.state == RootState.AUTHORIZED) "重新验证 Root" else "授权并验证 Root")
                }
                rootResult?.let {
                    Text(if (rootSuccess) "身份验证通过，本次会话已使用 Root。" else it, style = MaterialTheme.typography.bodySmall, color = if (rootSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            } else {
                Text(
                    when (state) {
                        ShizukuState.UNAVAILABLE -> "Shizuku 尚未连接"
                        ShizukuState.UNAUTHORIZED -> "Shizuku 等待授权"
                        ShizukuState.AUTHORIZED -> "Shizuku 已授权"
                        ShizukuState.DENIED -> "请在 Shizuku 中允许本应用"
                        ShizukuState.UNSUPPORTED -> "请更新 Shizuku"
                        ShizukuState.ERROR -> "Shizuku 连接异常"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("通过无线调试或 Root 启动 Shizuku，连接后即可执行本机命令。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                when (state) {
                    ShizukuState.AUTHORIZED -> Button(onClick = ::testShizuku, enabled = !busy && sessionAvailable, modifier = Modifier.fillMaxWidth()) { Text(if (testing) "正在验证…" else "验证连接") }
                    ShizukuState.UNAUTHORIZED -> Button(onClick = { launchError = null; shizuku.requestPermission(1001) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("允许 Shizuku 授权") }
                    else -> Button(onClick = ::openShizuku, modifier = Modifier.fillMaxWidth()) { Text("打开 Shizuku") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state == ShizukuState.AUTHORIZED || state == ShizukuState.UNAUTHORIZED) {
                        TextButton(onClick = ::openShizuku) { Text("打开 Shizuku") }
                    }
                    TextButton(onClick = { launchError = null; shizuku.refresh() }) { Text("刷新状态") }
                }
                diagnostic?.let {
                    Text(if (diagnosticSuccess) "连接验证通过，可以执行设备命令。" else it, style = MaterialTheme.typography.bodySmall, color = if (diagnosticSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("请完成授权弹窗，或在下方停止操作。", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!sessionAvailable) {
                Text("请在下方恢复会话后再验证连接。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text("所选通道仅在本次应用运行期间生效，重启后恢复 Shizuku。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        DeviceControlCard {
            Text("本次会话", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    sessionState.stopped -> "设备操作已停止，正在执行的任务也会取消。"
                    sessionState.authorization == DeviceAuthorization.REVOKED -> "授权已撤销，设备命令不可执行。"
                    sessionState.authorization == DeviceAuthorization.SESSION -> "已授权常规设备操作；高风险操作和 Root 命令仍会单独询问。"
                    else -> "按操作请求确认。也可授权本次会话的常规设备操作。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!sessionAvailable || sessionState.authorization != DeviceAuthorization.SESSION) {
                    OutlinedButton(onClick = { session.authorizeSession() }, enabled = !busy) { Text(if (!sessionAvailable) "恢复会话" else "授权本次会话") }
                }
                if (sessionState.authorization != DeviceAuthorization.REVOKED) {
                    OutlinedButton(onClick = { session.revoke() }) { Text("撤销授权") }
                }
                if (!sessionState.stopped) {
                    TextButton(onClick = { session.stop() }) { Text("停止设备操作", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        launchError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        DeviceControlCard {
            TextButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showAdvanced) "收起诊断与高级设置" else "诊断与高级设置")
            }
            if (showAdvanced) {
                Text("应用标识", style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(context.packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                if (useRoot) {
                    Text(rootStatus.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val output = if (useRoot) rootResult else diagnostic
                if (output != null) {
                    Text("最近一次验证输出", style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(output, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
                HorizontalDivider()
                Text("无障碍服务", style = MaterialTheme.typography.titleSmall)
                Text("目前尚未接入 AI 自动操作流程。系统设置可查看服务注册状态。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    launchError = try {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        null
                    } catch (error: Exception) {
                        "无法打开无障碍设置：${error.message ?: error.javaClass.simpleName}"
                    }
                }) { Text("打开系统无障碍设置") }
            }
        }
    }
}

@Composable
private fun DeviceControlCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
