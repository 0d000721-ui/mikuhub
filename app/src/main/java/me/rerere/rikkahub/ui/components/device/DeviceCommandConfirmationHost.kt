package me.rerere.rikkahub.ui.components.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.device.DeviceCommandConfirmations
import me.rerere.rikkahub.device.DeviceCommandPreview
import me.rerere.rikkahub.ui.components.ui.ExecutionApprovalCard
import org.koin.compose.koinInject

@Composable
fun DeviceCommandConfirmationHost(confirmations: DeviceCommandConfirmations = koinInject()) {
    val pending by confirmations.pending.collectAsStateWithLifecycle()
    val request = pending ?: return
    key(request.id) {
        DeviceCommandApprovalSheet(
            preview = request.preview,
            confirmationNumber = request.step,
            onConfirm = { confirmations.confirm(request.id, request.step) },
            onReject = { confirmations.reject(request.id, it) },
            transport = request.transport.name,
            requestKey = request.id.toString(),
        )
    }
}

@Composable
internal fun DeviceCommandApprovalSheet(
    preview: DeviceCommandPreview,
    confirmationNumber: Int,
    onConfirm: () -> Unit,
    onReject: (String) -> Unit,
    transport: String,
    requestKey: String = preview.command,
) {
    val count = preview.confirmationCount.coerceAtLeast(1)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    ModalBottomSheet(
        onDismissRequest = { onReject("") },
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            ExecutionApprovalCard(
                requestKey = "$requestKey:$confirmationNumber",
                title = "允许执行这条设备指令？",
                subtitle = "执行通道 · $transport",
                status = if (count > 1) "高风险操作 · 第 $confirmationNumber / $count 次确认" else "等待你的确认",
                command = preview.command,
                warning = preview.redWarning,
                approveLabel = if (confirmationNumber < count) "继续第 $confirmationNumber 次确认" else "允许本次执行",
                onApprove = onConfirm,
                onReject = onReject,
                details = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(preview.explanation, style = MaterialTheme.typography.bodyMedium)
                        Text("影响：${preview.impact}", style = MaterialTheme.typography.bodySmall)
                        Text("风险：${preview.riskExplanation}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                },
            )
        }
    }
}
