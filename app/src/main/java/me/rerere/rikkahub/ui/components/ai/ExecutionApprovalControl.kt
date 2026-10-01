package me.rerere.rikkahub.ui.components.ai

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.SecurityCheck
import me.rerere.hugeicons.stroke.SecurityWarning
import me.rerere.rikkahub.data.ai.ExecutionApprovalMode
import me.rerere.rikkahub.data.ai.ExecutionApprovalStore
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import org.koin.compose.koinInject

private val ExecutionApprovalMode.label: String
    get() = if (this == ExecutionApprovalMode.UNRESTRICTED) "完全不受限" else "重要操作手动确认"

/** The same local selector is available beside speed controls and in Settings. */
@Composable
fun ExecutionApprovalControl(
    compact: Boolean = true,
    store: ExecutionApprovalStore = koinInject(),
) {
    val mode by store.mode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var visible by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val unrestricted = mode == ExecutionApprovalMode.UNRESTRICTED
    val icon = if (unrestricted) HugeIcons.SecurityWarning else HugeIcons.SecurityCheck
    val description = "自动授权：${mode.label}，点击切换"
    if (compact) {
        ToggleSurface(checked = unrestricted, onClick = { visible = true }) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                    .testTag("execution_approval_mode").semantics { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
                    tint = if (unrestricted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (unrestricted) "不受限" else "重要确认", style = MaterialTheme.typography.labelMedium)
            }
        }
    } else {
        Surface(onClick = { visible = true }, shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }) {
            ListItem(
                headlineContent = { Text("自动授权") },
                supportingContent = { Text(mode.label) },
                leadingContent = { Icon(icon, null) },
            )
        }
    }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = { visible = false },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("自动授权", style = MaterialTheme.typography.titleLarge)
                Text("适用于此设备上的所有聊天，可随时切换。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup()) {
                    ExecutionApprovalMode.entries.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().selectable(
                                selected = option == mode, enabled = !saving, role = Role.RadioButton,
                                onClick = {
                                    if (option == mode) visible = false else scope.launch {
                                        saving = true
                                        try {
                                            store.selectMode(option)
                                            visible = false
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "授权设置未保存，请重试", Toast.LENGTH_SHORT).show()
                                        } finally {
                                            saving = false
                                        }
                                    }
                                },
                            ).padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = option == mode, onClick = null, enabled = !saving)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                                Text(if (option == ExecutionApprovalMode.IMPORTANT_ONLY)
                                    "查询、浏览和下载自动执行；安装、删除、提交表单等重要操作先确认。"
                                else "自动批准应用内执行请求，包括安装、删除和提交。操作会直接生效。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Text("系统权限和浏览器的 AI 开关仍需开启；登录、验证码和 AI 提问由你完成。停止或撤销设备授权仍然有效。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
