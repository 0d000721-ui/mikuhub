package me.rerere.rikkahub.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class AndroidDeviceCommandBackend(
    private val manager: ShizukuManager,
    private val shizukuRunner: ShizukuDeviceCommandRunner,
    private val rootRunner: RootDeviceCommandRunner,
) : DeviceCommandBackend {
    override suspend fun status(): DeviceBackendStatus = withContext(Dispatchers.Main.immediate) {
        manager.refresh()
        val state = manager.state.value
        DeviceBackendStatus(
            state,
            if (state == ShizukuState.AUTHORIZED) runCatching { Shizuku.getUid() }.getOrNull() else null,
            manager.message.value,
        )
    }

    override suspend fun run(transport: DeviceTransport, command: String): String = when (transport) {
        DeviceTransport.SHIZUKU -> shizukuRunner.run(command)
        DeviceTransport.ROOT -> rootRunner.run(command)
        DeviceTransport.ADB -> error("本机 ADB shell 操作应通过已授权的 Shizuku 执行")
    }
}
