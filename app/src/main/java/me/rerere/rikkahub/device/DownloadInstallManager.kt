package me.rerere.rikkahub.device

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class DownloadProgress(val id: Long, val status: Int, val downloaded: Long, val total: Long, val uri: Uri? = null)

class DownloadInstallManager(private val context: Context) {
    private val manager get() = context.getSystemService(DownloadManager::class.java)

    fun enqueueApk(url: String, name: String): Long {
        require(url.startsWith("https://")) { "Only HTTPS downloads are allowed" }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "download.apk" }
        return manager.enqueue(DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(safeName); setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
        })
    }

    fun progress(id: Long): DownloadProgress? {
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use { if (!it.moveToFirst()) return null; val uri = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))?.let(Uri::parse)
            return DownloadProgress(id, it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)), it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)), it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)), uri) }
    }

    fun downloadedFile(id: Long): File? {
        val item = progress(id) ?: return null
        if (item.status != DownloadManager.STATUS_SUCCESSFUL) return null
        val uri = item.uri ?: return null
        return when (uri.scheme) {
            "file" -> uri.path?.let(::File)
            "content" -> File(context.cacheDir, "download-$id.apk").also { target ->
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) }
            }
            else -> null
        }?.takeIf { it.isFile && it.length() > 0 }
    }

    suspend fun installApk(file: File) = withContext(Dispatchers.Main) {
        require(file.isFile && file.length() > 0) { "APK file is unavailable" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.android.package-archive"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION) })
    }
}
