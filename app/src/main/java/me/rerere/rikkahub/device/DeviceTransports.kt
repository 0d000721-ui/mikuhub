package me.rerere.rikkahub.device

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DeviceCapability(
    val shizukuAvailable: Boolean,
    val shizukuAuthorized: Boolean,
    val rootAvailable: Boolean,
    val adbAvailable: Boolean,
)

class ShizukuStatus(private val context: Context) {
    private val _available = MutableStateFlow(isAvailable())
    val available: StateFlow<Boolean> = _available
    private val binderListener = Shizuku.OnBinderReceivedListener { _available.value = isAvailable() }
    private val deadListener = Shizuku.OnBinderDeadListener { _available.value = false }
    init { Shizuku.addBinderReceivedListener(binderListener); Shizuku.addBinderDeadListener(deadListener) }
    fun close() { Shizuku.removeBinderReceivedListener(binderListener); Shizuku.removeBinderDeadListener(deadListener) }
    fun isAvailable(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    fun isAuthorized(): Boolean = try {
        isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }
    fun requestPermission(requestCode: Int) {
        if (isAvailable() && !isAuthorized()) Shizuku.requestPermission(requestCode)
    }
}

fun deviceCapability(context: Context): DeviceCapability {
    val status = ShizukuStatus(context)
    return DeviceCapability(status.isAvailable(), status.isAuthorized(), false, false)
}
