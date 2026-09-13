package me.rerere.rikkahub.ui.components.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.device.DeviceCommandConfirmations
import org.koin.compose.koinInject

@Composable
fun DeviceCommandConfirmationHost(confirmations: DeviceCommandConfirmations = koinInject()) {
    val pending by confirmations.pending.collectAsStateWithLifecycle()
    val request = pending ?: return
    key(request.id, request.step) {
        if (request.preview.redWarning) {
            CriticalCommandConfirmationDialog(
                preview = request.preview,
                confirmationNumber = request.step,
                onConfirm = { confirmations.confirm(request.id, request.step) },
                onReject = { confirmations.reject(request.id) },
                transport = request.transport.name,
            )
        } else {
            AlertDialog(
                onDismissRequest = { confirmations.reject(request.id) },
                title = { Text("确认设备操作") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("执行通道：${request.transport}")
                        Text("命令：${request.preview.command}")
                        Text("用途：${request.preview.explanation}")
                        Text("影响：${request.preview.impact}")
                        Text("风险：${request.preview.riskExplanation}", color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { confirmations.confirm(request.id, request.step) }) { Text("允许执行") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmations.reject(request.id) }) { Text("拒绝") }
                },
            )
        }
    }
}
