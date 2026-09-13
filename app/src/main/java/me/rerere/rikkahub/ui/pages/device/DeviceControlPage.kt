package me.rerere.rikkahub.ui.pages.device

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ModelAbility
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.device.DeviceAccessSession
import me.rerere.rikkahub.device.DeviceCommandController
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
) {
    val context = LocalContext.current
    val state by shizuku.state.collectAsStateWithLifecycle()
    val message by shizuku.message.collectAsStateWithLifecycle()
    var launchError by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val sessionState by session.state.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val assistant = settings.getCurrentAssistant()
    val toolsEnabled = LocalToolOption.DeviceCommands in assistant.localTools
    val scope = rememberCoroutineScope()
    var diagnostic by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

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
                "未找到 Shizuku，请先安装 Shizuku 并启动服务。"
            } else {
                context.startActivity(intent)
                null
            }
        } catch (error: Exception) {
            "无法打开 Shizuku：${error.message ?: error.javaClass.simpleName}"
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Shizuku：${when (state) {
            ShizukuState.UNAVAILABLE -> "未连接"
            ShizukuState.UNAUTHORIZED -> "等待授权"
            ShizukuState.AUTHORIZED -> "已授权"
            ShizukuState.DENIED -> "需要在 Shizuku 中允许授权"
            ShizukuState.UNSUPPORTED -> "版本不受支持"
            ShizukuState.ERROR -> "发生错误"
        }}")
        Text("当前应用包名：${context.packageName}", style = MaterialTheme.typography.bodySmall)
        Text("当前助手：${assistant.name.ifBlank { "默认助手" }}")
        Text("允许当前助手使用设备工具")
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
        if (toolsEnabled && settings.getCurrentChatModel()?.abilities?.contains(ModelAbility.TOOL) != true) {
            Text("当前模型未启用工具调用能力。请在模型设置中启用工具调用，或选择支持工具调用的模型。")
        }
        Text("Shizuku 通过无线调试启动时提供 ADB shell 权限；AI 使用 pm 等本机命令，无需电脑端 adb。")
        Text("会话授权：${sessionState.authorization}，设备 Agent：${if (sessionState.stopped) "已停止" else "可用"}")
        message?.let { Text(it) }
        launchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { launchError = null; shizuku.requestPermission(1001) },
            enabled = state != ShizukuState.AUTHORIZED,
        ) { Text("请求 Shizuku 授权") }
        OutlinedButton(onClick = ::openShizuku) { Text("打开 Shizuku") }
        OutlinedButton(onClick = { launchError = null; shizuku.refresh() }) { Text("重新检查 Shizuku 状态") }
        OutlinedButton(
            enabled = !testing && state == ShizukuState.AUTHORIZED,
            onClick = {
                scope.launch {
                    testing = true
                    try {
                        val result = controller.execute("id", "验证 Shizuku 命令通道的实际运行身份", "shizuku")
                        diagnostic = result["error"]?.jsonPrimitive?.content ?: result["output"]?.jsonPrimitive?.content
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        diagnostic = error.message
                    } finally {
                        testing = false
                    }
                }
            },
        ) { Text(if (testing) "正在测试…" else "测试 Shizuku 命令通道（id）") }
        diagnostic?.let { Text(it) }
        Button(onClick = { session.authorizeSession() }) { Text("开启本次会话授权") }
        Button(onClick = { session.revoke() }) { Text("撤销会话授权") }
        Button(onClick = { session.stop() }) { Text("停止设备 Agent") }
        Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("打开无障碍设置") }
    }
}
