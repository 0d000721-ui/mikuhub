package me.rerere.rikkahub.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ProcessMemoryInfo(val pid: Int, val uid: Int?, val rssKb: Long?, val maps: List<String>)

class MemoryDiagnostics {
    suspend fun inspect(pid: Int): ProcessMemoryInfo = withContext(Dispatchers.IO) {
        require(pid > 0) { "Invalid pid" }
        val proc = File("/proc/$pid")
        check(proc.isDirectory) { "Process is unavailable" }
        val status = File(proc, "status").takeIf { it.canRead() }?.readLines().orEmpty()
        val uid = status.firstOrNull { it.startsWith("Uid:") }?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
        val rss = status.firstOrNull { it.startsWith("VmRSS:") }?.filter { it.isDigit() }?.trim()?.toLongOrNull()
        val maps = File(proc, "maps").takeIf { it.canRead() }?.useLines { it.take(500).toList() }.orEmpty()
        ProcessMemoryInfo(pid, uid, rss, maps)
    }
}
