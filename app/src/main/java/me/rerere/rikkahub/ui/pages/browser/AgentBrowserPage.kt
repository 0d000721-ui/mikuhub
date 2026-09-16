package me.rerere.rikkahub.ui.pages.browser

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.browser.AgentBrowserController
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.Screen
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentBrowserPage(controller: AgentBrowserController = koinInject()) {
    val state by controller.state.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    val keyboard = LocalSoftwareKeyboardController.current
    var address by rememberSaveable { mutableStateOf(state.url) }
    LaunchedEffect(state.url) { address = state.url }
    DisposableEffect(controller) { onDispose { controller.detachDisplay() } }
    BackHandler(state.canGoBack) { controller.manualGoBack() }

    fun navigate() {
        keyboard?.hide()
        controller.manualNavigate(address)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = { Text("浏览器", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { BackButton() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                actions = {
                    IconButton(onClick = { nav.navigate(Screen.DeviceDownload) }) {
                        Icon(HugeIcons.Download01, contentDescription = "查看下载进度")
                    }
                    TextButton(onClick = { controller.releaseBrowser() }, enabled = state.url.isNotBlank() || state.aiEnabled) {
                        Text("关闭会话")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("输入网址，如 https://example.com") },
                leadingIcon = { Icon(HugeIcons.Earth, null, Modifier.size(20.dp)) },
                trailingIcon = {
                    IconButton(onClick = ::navigate, enabled = address.isNotBlank()) {
                        Icon(HugeIcons.ArrowRight01, "打开网页")
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { navigate() }),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("允许 AI 操作此浏览器", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.aiBusy) state.lastAction ?: "AI 正在操作"
                        else if (state.aiEnabled) "已开启 · 返回聊天后仍可读取与操作"
                        else "关闭时 AI 无法读取网页或进行操作",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.aiBusy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.aiEnabled) TextButton(onClick = { controller.setAiEnabled(false) }) { Text("停止") }
                Switch(checked = state.aiEnabled, onCheckedChange = controller::setAiEnabled)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = controller::manualGoBack, enabled = state.canGoBack) {
                    Icon(HugeIcons.ArrowLeft01, "上一页")
                }
                IconButton(onClick = controller::manualGoForward, enabled = state.canGoForward) {
                    Icon(HugeIcons.ArrowRight01, "下一页")
                }
                Text(
                    state.title,
                    Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { if (state.loading) controller.stop() else controller.reload() }, enabled = state.url.isNotBlank()) {
                    Icon(if (state.loading) HugeIcons.Cancel01 else HugeIcons.Refresh01, if (state.loading) "停止加载" else "刷新网页")
                }
            }
            if (state.loading) LinearProgressIndicator(
                progress = { state.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            val message = state.error ?: state.message
            if (message != null) {
                Surface(color = if (state.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = controller::clearMessage) { Icon(HugeIcons.Cancel01, "关闭提示", Modifier.size(18.dp)) }
                    }
                }
            }
            if (state.url.isBlank()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(HugeIcons.Earth, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("网页与下载，留在应用内", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "输入网址开始浏览。网页文件会加入下载中心，离开此页仍会继续下载。\n\n开启上方开关后，可回到聊天让 AI 读取页面、点击链接和填写表单。你可以随时停止，或关闭整个浏览器会话。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                key(state.session) {
                    AndroidView(
                        factory = { controller.webViewForDisplay() },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onRelease = { view -> (view.parent as? ViewGroup)?.removeView(view) },
                    )
                }
            }
        }
    }
}
