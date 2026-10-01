package me.rerere.rikkahub.ui.pages.setting.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.chatgpt.ChatGptAccountManager
import me.rerere.rikkahub.data.ai.chatgpt.ChatGptAuthStatus
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ChatGptProviderConfigure(
    provider: ProviderSetting.OpenAI,
    onEdit: (ProviderSetting.OpenAI) -> Unit,
    manageAccount: Boolean,
) {
    val manager = koinInject<ChatGptAccountManager>()
    val connector = koinInject<ChatGptProviderConnector>()
    val appScope = koinInject<AppScope>()
    val loadingProviders by connector.loading.collectAsStateWithLifecycle()
    val catalogStatuses by connector.catalogStatus.collectAsStateWithLifecycle()
    val accounts by manager.accounts.collectAsStateWithLifecycle()
    val authStatus by manager.authStatus.collectAsStateWithLifecycle()
    val currentProvider by rememberUpdatedState(provider)
    val selected = accounts.find { it.id == provider.chatGptAccountId }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val toaster = LocalToaster.current
    var choosingAccount by remember { mutableStateOf(false) }
    val fetchingModels = provider.id in loadingProviders
    var showWelcome by remember { mutableStateOf(false) }
    val authorizing = authStatus is ChatGptAuthStatus.Authorizing

    val prefs = remember(context) { context.getSharedPreferences("chatgpt_ui", Context.MODE_PRIVATE) }
    LaunchedEffect(selected?.id, selected?.planEnabled) {
        if (manageAccount && selected?.planEnabled == true && !prefs.getBoolean("plan_welcome_shown", false)) showWelcome = true
    }

    fun selectAndFetch(accountId: String) {
        if (!manageAccount) {
            onEdit(provider.copy(chatGptAccountId = accountId, apiKey = ""))
            return
        }
        appScope.launch {
            try {
                val count = connector.activateAndDiscover(currentProvider.id, accountId)
                toaster.show("已读取 $count 个账户可用模型", type = ToastType.Success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toaster.show(e.message ?: "读取账户模型失败，请重试", type = ToastType.Error)
            }
        }
    }

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it)) },
        label = { Text("服务商名称") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Codex / ChatGPT", style = MaterialTheme.typography.titleMedium)
            Text(
                "使用 ChatGPT 账户授权可用模型，符合条件的请求使用你的 ChatGPT 方案或额度。登录与授权在官方页面完成。",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (selected != null) {
                Text(selected.email ?: "ChatGPT 账户", style = MaterialTheme.typography.bodyLarge)
                Text(
                    when {
                        !selected.connected -> "已退出，重新登录可恢复连接"
                        !selected.planEnabled -> "账户已登录，尚未授权 ChatGPT 方案使用"
                        else -> "已连接 · Using ChatGPT plan"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("尚未连接账户", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!manageAccount) {
                Text("选择已授权账户，保存模型覆盖配置后生效。添加账户或重新授权请前往主 Codex / ChatGPT 服务商。",
                    style = MaterialTheme.typography.bodySmall)
            } else if (authorizing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("等待浏览器完成登录", modifier = Modifier.weight(1f))
                    TextButton(onClick = manager::cancelSignIn) { Text("取消") }
                }
            } else {
                Button(
                    onClick = {
                        manager.startSignIn(context, selected?.id, ::selectAndFetch)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AutoAIIcon("OpenAI", color = Color.Transparent)
                        Text("Continue with ChatGPT")
                    }
                }
            }
            if (manageAccount && authStatus is ChatGptAuthStatus.Error) {
                Text((authStatus as ChatGptAuthStatus.Error).message, color = MaterialTheme.colorScheme.error)
            }
            if (accounts.isNotEmpty()) {
                Column {
                    OutlinedButton(onClick = { choosingAccount = true }, enabled = !authorizing) {
                        Text(if (manageAccount) "选择账户 / 添加账户" else "选择账户")
                    }
                    DropdownMenu(expanded = choosingAccount, onDismissRequest = { choosingAccount = false }) {
                        accounts.forEach { account ->
                            DropdownMenuItem(
                                enabled = manageAccount || (account.connected && account.planEnabled),
                                text = { Text("${account.email ?: "ChatGPT"} · ${account.clientId.takeLast(6)}${if (!account.connected) " · 已退出" else ""}") },
                                onClick = {
                                    choosingAccount = false
                                    if (account.connected && account.planEnabled) selectAndFetch(account.id)
                                    else {
                                        manager.startSignIn(context, account.id, ::selectAndFetch)
                                    }
                                },
                            )
                        }
                        if (manageAccount) DropdownMenuItem(text = { Text("添加另一个账户") }, onClick = {
                            choosingAccount = false
                            manager.startSignIn(context, onSuccess = ::selectAndFetch)
                        })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (manageAccount) TextButton(
                    onClick = {
                        appScope.launch {
                            try {
                                connector.refreshIfStale(currentProvider.id, force = true)?.let { count ->
                                    toaster.show("已更新 $count 个账户可用模型", type = ToastType.Success)
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { toaster.show("模型目录刷新失败，请重试", type = ToastType.Error) }
                        }
                    },
                    enabled = selected?.planEnabled == true && selected.connected && !fetchingModels && !authorizing,
                ) { Text(if (fetchingModels) "读取中…" else "刷新模型") }
                TextButton(onClick = { uriHandler.openUri("https://chatgpt.com/settings/usage") }) { Text("Manage usage") }
                if (manageAccount && selected?.connected == true) {
                    TextButton(onClick = {
                        appScope.launch {
                            try {
                                manager.signOut(selected.id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                toaster.show(e.message ?: "远程撤销未确认，可在 ChatGPT 设置中断开", type = ToastType.Warning)
                            }
                        }
                    }, enabled = !authorizing) { Text("退出") }
                }
            }
            if (manageAccount) Text("模型从所选账户实时读取 · ${provider.models.size} 个", style = MaterialTheme.typography.labelSmall)
            if (manageAccount) {
                val status = catalogStatuses[provider.id]?.takeIf { it.accountId == provider.chatGptAccountId }
                status?.lastSuccessfulAt?.let { timestamp ->
                    Text("最近成功更新：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                status?.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("启用")
        Switch(checked = provider.enabled, onCheckedChange = { onEdit(provider.copy(enabled = it)) })
    }
    if (showWelcome) {
        AlertDialog(
            onDismissRequest = { showWelcome = false; prefs.edit().putBoolean("plan_welcome_shown", true).apply() },
            title = { Text("You're using your ChatGPT plan") },
            text = { Text("符合条件的 AI 请求将使用你的 ChatGPT 方案或额度。可以通过 Manage usage 在 ChatGPT 设置中查看和管理使用情况。") },
            confirmButton = { TextButton(onClick = { showWelcome = false; prefs.edit().putBoolean("plan_welcome_shown", true).apply() }) { Text("Got it") } },
        )
    }
}
