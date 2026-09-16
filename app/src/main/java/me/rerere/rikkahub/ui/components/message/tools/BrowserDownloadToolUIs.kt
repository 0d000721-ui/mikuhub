package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Internet
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController

internal val browserDownloadToolRenderers: List<ToolUIRenderer> = listOf(
    WebActionToolUI("download_start", "下载文件", true),
    WebActionToolUI("download_status", "查看下载进度", true),
    WebActionToolUI("download_cancel", "取消下载", true),
    WebActionToolUI("download_install", "静默安装 APK", true),
    WebActionToolUI("download_install_status", "查看安装结果", true),
    WebActionToolUI("browser_status", "检查浏览器状态"),
    WebActionToolUI("browser_navigate", "打开网页"),
    WebActionToolUI("browser_read", "读取网页"),
    WebActionToolUI("browser_click", "点击网页元素"),
    WebActionToolUI("browser_fill", "填写网页字段"),
    WebActionToolUI("browser_scroll", "滚动网页"),
    WebActionToolUI("browser_download", "下载网页文件", true),
)

private class WebActionToolUI(
    override val toolName: String,
    private val label: String,
    private val download: Boolean = false,
) : ToolUIRenderer {
    override fun icon(context: ToolUIContext): ImageVector = if (download) HugeIcons.Download04 else HugeIcons.Internet

    @Composable
    override fun title(context: ToolUIContext): String {
        if (context.content.getStringContent("error") != null) return "$label · 未完成"
        if (toolName == "download_install") return when (context.content.getStringContent("status")) {
            "SUCCEEDED" -> "$label · 已安装"
            "FAILED" -> "$label · 安装失败"
            "CANCELLED" -> "$label · 已取消"
            "UNKNOWN" -> "$label · 结果待确认"
            else -> label
        }
        val id = context.content.getStringContent("task_id")
        return if (id != null) "$label · 任务 #$id 已创建" else label
    }

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        val nav = LocalNavController.current
        TextButton(onClick = { nav.navigate(if (download) Screen.DeviceDownload else Screen.AgentBrowser) }) {
            Text(if (toolName.startsWith("download_install")) "查看安装结果" else if (download) "查看实际下载进度" else "查看浏览器")
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val nav = LocalNavController.current
        DefaultToolPreview(context) {
            TextButton(onClick = {
                onDismissRequest()
                nav.navigate(if (download) Screen.DeviceDownload else Screen.AgentBrowser)
            }) { Text(if (download) "下载中心" else "浏览器") }
        }
    }
}
