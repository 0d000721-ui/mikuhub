package me.rerere.rikkahub.device

import android.content.Context
import android.view.WindowManager
import android.os.BatteryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PerformanceSnapshot(
    val refreshRateHz: Float,
    val estimatedFps: Float? = null,
    val cpuFrequency: String? = null,
    val batteryPercent: Int? = null,
    val powerWatts: Float? = null,
    val temperatureC: Float? = null,
)

class PerformanceMonitor(private val context: Context) {
    suspend fun sample(): PerformanceSnapshot = withContext(Dispatchers.IO) {
        val refresh = context.getSystemService(WindowManager::class.java)?.defaultDisplay?.refreshRate ?: 0f
        val battery = context.getSystemService(BatteryManager::class.java)
        PerformanceSnapshot(refreshRateHz = refresh, cpuFrequency = readCpuFrequency(), batteryPercent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it >= 0 }, temperatureC = readTemperature())
    }

    private fun readCpuFrequency(): String? = runCatching {
        java.io.File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq").takeIf { it.canRead() }?.readText()?.trim()
    }.getOrNull()
    private fun readTemperature(): Float? = runCatching { java.io.File("/sys/class/thermal/thermal_zone0/temp").takeIf { it.canRead() }?.readText()?.trim()?.toFloat()?.let { it / 1000f } }.getOrNull()
}
