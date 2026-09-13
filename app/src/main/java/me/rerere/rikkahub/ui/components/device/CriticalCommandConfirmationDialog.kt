package me.rerere.rikkahub.ui.components.device

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.device.DeviceCommandPreview

@Composable
fun CriticalCommandConfirmationDialog(
    preview: DeviceCommandPreview?,
    confirmationNumber: Int,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    transport: String = "",
) {
    if (preview == null) return
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("高风险设备操作", color = Color.Red) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("这是第 $confirmationNumber/3 次确认。", color = Color.Red, style = MaterialTheme.typography.titleMedium)
                if (transport.isNotBlank()) Text("执行通道：$transport")
                Spacer(Modifier.height(8.dp))
                Text("命令：${preview.command}", color = Color.Red)
                Text("用途：${preview.explanation}")
                Text("影响：${preview.impact}", color = Color.Red)
                Text("风险：${preview.riskExplanation}", color = Color.Red)
                Text("该操作不会执行格机或恢复出厂命令。", color = Color.Red)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认第 $confirmationNumber 次", color = Color.Red) } },
        dismissButton = { TextButton(onClick = onReject) { Text("拒绝", color = Color.Red) } },
    )
}
