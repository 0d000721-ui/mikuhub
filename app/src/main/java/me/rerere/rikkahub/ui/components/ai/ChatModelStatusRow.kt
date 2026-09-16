package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.device.DownloadInstallManager
import me.rerere.rikkahub.device.DownloadStatus
import me.rerere.rikkahub.device.formatDownloadBytes
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.Tooltip
import org.koin.compose.koinInject

/** A wrapping row keeps status controls visible even on narrow screens. */
@Composable
internal fun ChatModelStatusRow(
    modelState: ModelListState,
    conversation: Conversation,
    loading: Boolean,
    onOpenContext: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val contextWidth = 86.dp * fontScale
    val downloadWidth = 96.dp * fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        // Leave fixed room for the two status chips; only the model name is shortened.
        val modelWidth = (maxWidth - contextWidth - downloadWidth - 8.dp).coerceIn(96.dp, 220.dp)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompactModelSelector(modelState, Modifier.width(modelWidth))
            ContextUsageIndicator(
                conversation = conversation,
                loading = loading,
                modifier = Modifier.width(contextWidth),
                onClick = onOpenContext,
            )
            DownloadStatusIndicator(Modifier.width(downloadWidth), onOpenDownloads)
        }
    }
}

@Composable
private fun CompactModelSelector(state: ModelListState, modifier: Modifier) {
    val model = state.currentModel
    val name = model?.let { it.displayName.ifBlank { it.modelId } } ?: "选择模型"
    Tooltip(tooltip = { Text(name) }) {
        Surface(
            onClick = state::open,
            modifier = modifier.heightIn(min = 40.dp).testTag("chat_model_selector")
                .semantics { contentDescription = "当前模型：$name，点击切换" },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp).clearAndSetSemantics {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (model != null) {
                    AutoAIIcon(model.modelId, Modifier.size(22.dp), color = Color.Transparent)
                } else {
                    Icon(HugeIcons.Brain02, null, Modifier.size(18.dp))
                }
                Text(
                    text = name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(HugeIcons.ArrowDown01, null, Modifier.size(12.dp))
            }
        }
    }
}

/** Collect separately so transfer progress does not recompose the text editor. */
@Composable
private fun DownloadStatusIndicator(
    modifier: Modifier,
    onClick: () -> Unit,
    manager: DownloadInstallManager = koinInject(),
) {
    val tasks by manager.downloads.collectAsStateWithLifecycle(initialValue = emptyList())
    val active = remember(tasks) { tasks.filter { it.isActive } }
    val downloaded = remember(active) { active.sumOf { it.downloadedBytes.coerceAtLeast(0) } }
    val total = remember(active) { active.sumOf { it.totalBytes.coerceAtLeast(0) } }
    val progress = if (active.isNotEmpty() && active.all { it.totalBytes > 0 } && total > 0) {
        (downloaded.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
    } else null
    val percent = progress?.let { (it * 100).toInt() }
    val latest = tasks.maxByOrNull { it.createdAt }
    val failed = active.isEmpty() && latest?.status == DownloadStatus.FAILED
    val label = when {
        active.size > 1 -> "${active.size}项 ${percent?.let { "$it%" } ?: "下载"}"
        active.size == 1 -> when (active.first().status) {
            DownloadStatus.QUEUED -> "排队中"
            DownloadStatus.WAITING -> "等待网络"
            else -> percent?.let { "$it%" } ?: "下载中"
        }
        failed -> "下载失败"
        latest?.status == DownloadStatus.READY -> "已下载"
        else -> "下载"
    }
    val description = when {
        active.isNotEmpty() -> buildString {
            append("${active.size} 个下载任务，已下载 ${formatDownloadBytes(downloaded)}")
            if (progress != null) append("，共 ${formatDownloadBytes(total)}，$percent%")
            else append("，总大小未知")
            if (active.size == 1) append("，${active.first().statusLabel}")
            append("。点击查看下载进度")
        }
        failed -> "${latest?.fileName} 下载失败，点击查看原因并重试"
        latest?.status == DownloadStatus.READY -> "${latest.fileName} 下载完成，点击打开下载管理"
        else -> "下载管理，查看文件和下载进度"
    }
    ChatStatusChip(
        modifier = modifier.testTag("chat_download_status"),
        icon = HugeIcons.Download01,
        label = label,
        description = description,
        active = active.isNotEmpty(),
        error = failed,
        progress = progress,
        onClick = onClick,
    )
}

@Composable
internal fun ChatStatusChip(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    error: Boolean = false,
    progress: Float? = null,
) {
    val accent = when {
        error -> MaterialTheme.colorScheme.error
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    Tooltip(tooltip = { Text(description) }) {
        Surface(
            onClick = onClick,
            modifier = modifier.heightIn(min = 40.dp).semantics { contentDescription = description },
            shape = RoundedCornerShape(12.dp),
            color = when {
                error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                active -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else -> Color.Transparent
            },
            contentColor = accent,
        ) {
            Box {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 11.dp).clearAndSetSemantics {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(icon, null, Modifier.size(16.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (progress != null) {
                    Canvas(Modifier.align(Alignment.BottomCenter).padding(horizontal = 9.dp, vertical = 4.dp)
                        .fillMaxWidth().height(2.dp)) {
                        drawRoundRect(trackColor, cornerRadius = CornerRadius(size.height))
                        drawRoundRect(accent, size = Size(size.width * progress.coerceIn(0f, 1f), size.height),
                            cornerRadius = CornerRadius(size.height))
                    }
                }
            }
        }
    }
}
