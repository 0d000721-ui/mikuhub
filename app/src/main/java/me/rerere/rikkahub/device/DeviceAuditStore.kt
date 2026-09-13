package me.rerere.rikkahub.device

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.builtins.ListSerializer
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File

@Serializable
data class PersistedDeviceAudit(
    val timestamp: Long,
    val command: String,
    val transport: DeviceTransport,
    val risk: DeviceCommandRisk,
    val allowed: Boolean,
    val output: String,
    val error: String? = null,
)

class DeviceAuditStore(context: Context) {
    private val file = File(context.filesDir, "device-command-audit.json")

    @Synchronized
    fun append(entry: DeviceAuditEntry) {
        val entries = read().takeLast(499) + PersistedDeviceAudit(
            timestamp = System.currentTimeMillis(), command = entry.command,
            transport = entry.transport, risk = entry.risk, allowed = entry.allowed,
            output = entry.output.take(16_384), error = entry.error,
        )
        file.writeText(JsonInstant.encodeToString(ListSerializer(PersistedDeviceAudit.serializer()), entries))
    }

    @Synchronized
    fun read(): List<PersistedDeviceAudit> = runCatching {
        if (!file.exists()) emptyList() else JsonInstant.decodeFromString(ListSerializer(PersistedDeviceAudit.serializer()), file.readText())
    }.getOrDefault(emptyList())

    @Synchronized
    fun clear() { file.delete() }
}
