package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.browser.AgentBrowserController
import me.rerere.rikkahub.device.DownloadInstallManager
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject
import java.net.URI

/** Keep webpage navigation and actual file transfer separate choices for chat links. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatLinkHandler(content: @Composable () -> Unit) {
    val external = LocalUriHandler.current
    val nav = LocalNavController.current
    val manager = koinInject<DownloadInstallManager>()
    val browser = koinInject<AgentBrowserController>()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val handler = remember(external) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val isWeb = runCatching { URI(uri).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)
                if (isWeb) {
                    selected = uri
                    error = null
                } else external.openUri(uri)
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides handler, content = content)
    selected?.let { url ->
        ModalBottomSheet(onDismissRequest = { if (!busy) selected = null }) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("打开链接", style = MaterialTheme.typography.titleLarge)
                Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                FilledTonalButton(
                    onClick = {
                        browser.manualNavigate(url)
                        selected = null
                        nav.navigate(Screen.AgentBrowser)
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("在内置浏览器打开") }
                FilledTonalButton(
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                manager.enqueueDownload(url)
                                selected = null
                                nav.navigate(Screen.DeviceDownload)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (exception: Exception) {
                                error = exception.message ?: "下载未创建，请重试"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "正在检查下载链接…" else "下载文件到手机") }
                Text("下载会在后台继续。若链接是网页，请先打开网页找到文件下载入口。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    try {
                        external.openUri(url)
                        selected = null
                    } catch (_: Exception) {
                        error = "无法打开其他浏览器，可以使用内置浏览器继续"
                    }
                }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth()) { Text("使用其他浏览器") }
            }
        }
    }
}
