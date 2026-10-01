package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.ui.components.ui.ToggleSurface

@Composable
internal fun OpenAiSpeedButton(
    model: Model,
    provider: ProviderSetting?,
    assistantBodies: List<CustomBody>,
    onSelect: (OpenAiSpeed) -> Unit,
) {
    val options = availableOpenAiSpeeds(model, provider)
    if (options.isEmpty()) return
    val wire = effectiveOpenAiServiceTier(model.customBodies, assistantBodies)
    val selected = OpenAiSpeed.fromWire(wire)
    var visible by remember(model.id) { mutableStateOf(false) }
    ToggleSurface(checked = selected == OpenAiSpeed.FAST || selected == OpenAiSpeed.ULTRAFAST, onClick = { visible = true }) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(HugeIcons.Zap, contentDescription = "响应速度", modifier = Modifier.size(20.dp))
            Text(selected?.label ?: if (wire == null || wire == "auto") "默认" else "自定义", style = MaterialTheme.typography.labelMedium)
        }
    }
    if (visible) {
        ModalBottomSheet(
            onDismissRequest = { visible = false },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("响应速度", style = MaterialTheme.typography.titleLarge)
                Text("速度与推理强度分别设置。可用性由 OpenAI 账号权限决定。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (model.customBodies.none { it.key == "service_tier" } && assistantBodies.any { it.key == "service_tier" }) {
                    Text("当前继承助手设置；选择后保存到此模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.selectableGroup()) {
                    options.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option); visible = false }).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = option == selected, onClick = null)
                            Column {
                                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                                Text(when (option) {
                                    OpenAiSpeed.STANDARD -> "模型的标准速度"
                                    OpenAiSpeed.FAST -> "优先处理请求"
                                    OpenAiSpeed.ULTRAFAST -> "GPT-6 Astra 的最快速度"
                                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
