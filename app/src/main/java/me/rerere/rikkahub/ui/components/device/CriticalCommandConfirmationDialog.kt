package me.rerere.rikkahub.ui.components.device

import androidx.compose.runtime.Composable
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
    DeviceCommandApprovalSheet(preview, confirmationNumber, onConfirm, { onReject() }, transport)
}
