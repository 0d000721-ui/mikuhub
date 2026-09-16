package me.rerere.rikkahub.device

import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileNotFoundException
import java.io.File
import java.security.MessageDigest
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class DownloadInstallManager(context: Context) {
    private val context = context.applicationContext
    private val manager get() = context.getSystemService(DownloadManager::class.java)
    // Keep the original preferences name so existing APK tasks survive the upgrade.
    private val preferences = this.context.getSharedPreferences("apk_downloads", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val _issue = MutableStateFlow<String?>(null)
    val issue = _issue.asStateFlow()
    private data class CompletedFileCheck(val checkedAt: Long, val missing: Boolean = false, val issue: String? = null)
    private val completedFileChecks = mutableMapOf<Long, CompletedFileCheck>()

    // Deliberately separate from provider clients: never attach API credentials or cookies.
    private val metadataClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** DownloadManager owns the transfer; observing only refreshes the visible state. */
    val downloads: Flow<List<DownloadTask>> = flow {
        while (currentCoroutineContext().isActive) {
            try {
                emit(mutex.withLock { readDownloads() })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _issue.value = error.message ?: "暂时无法读取下载任务"
            }
            delay(1_000)
        }
    }.flowOn(Dispatchers.IO).conflate()

    /** A fresh system snapshot, including a local-file check for completed tasks. */
    suspend fun snapshot(): List<DownloadTask> = withContext(Dispatchers.IO) {
        mutex.withLock { readDownloads(verifyCompletedFiles = true) }
    }

    suspend fun enqueueApk(url: String, name: String? = null): Long =
        enqueueDownload(url, name?.let(::safeApkFileName), APK_MIME_TYPE)

    /** Starts a real background file transfer; it never opens a web page or installs a package. */
    suspend fun enqueueDownload(
        url: String,
        name: String? = null,
        mimeType: String? = null,
        userAgent: String? = null,
        referer: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val source = validatedDownloadUrl(url)
        val agent = validatedDownloadHeader(userAgent)
        val page = validatedDownloadHeader(referer)?.let(::validatedDownloadUrl)
        val metadata = resolveMetadata(source, name, mimeType, agent, page)
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            enqueue(source, metadata.first, metadata.second, agent, page)
        }
    }

    /** HEAD avoids fetching the file twice. Servers without HEAD support get a bounded GET probe. */
    private suspend fun resolveMetadata(
        source: String,
        name: String?,
        mimeType: String?,
        userAgent: String?,
        referer: String?,
    ): Pair<String, String?> {
        fun request(head: Boolean) = Request.Builder().url(source).apply {
            if (head) head() else get().header("Range", "bytes=0-511")
            userAgent?.let { header("User-Agent", it) }
            referer?.let { header("Referer", it) }
        }.build()

        val first = metadataClient.newCall(request(head = true)).await()
        val response = if (first.code in setOf(403, 405, 501)) {
            first.close()
            metadataClient.newCall(request(head = false)).await()
        } else first
        response.use {
            require(it.isSuccessful) { "服务器返回 HTTP ${it.code}，请检查下载地址或使用无需登录的文件直链" }
            validatedDownloadUrl(it.request.url.toString())
            val serverMime = normalizedDownloadMimeType(it.header("Content-Type"))
            val sample = if (it.request.method != "HEAD") it.peekBody(512).bytes() else byteArrayOf()
            require(!isHtmlMimeType(serverMime) && !looksLikeHtmlDownload(sample)) {
                "这个地址返回的是网页，不是文件直链。请在网页中找到实际下载按钮或文件地址后重试。"
            }
            val effectiveMime = serverMime?.takeUnless { type -> type == "application/octet-stream" }
                ?: normalizedDownloadMimeType(mimeType) ?: serverMime
            val fileName = resolvedDownloadFileName(it.request.url.toString(), name, it.header("Content-Disposition"), effectiveMime)
            return fileName to effectiveMime
        }
    }

    private fun enqueue(source: String, fileName: String, mimeType: String?, userAgent: String?, referer: String?): Long {
        val dot = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
        val destination = fileName.substring(0, dot) + "-${UUID.randomUUID().toString().take(8)}" + fileName.substring(dot)
        val id = manager.enqueue(DownloadManager.Request(Uri.parse(source)).apply {
            setTitle(fileName)
            setDescription("可在 MikuHub 的下载中心查看进度")
            // Let DownloadManager record the actual response MIME. Caller hints are stored separately.
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            userAgent?.let { addRequestHeader("User-Agent", it) }
            referer?.let { addRequestHeader("Referer", it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, destination)
            } else {
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, destination)
            }
        })
        val saved = SavedDownload(id, source, fileName, System.currentTimeMillis(), mimeType = mimeType, userAgent = userAgent, referer = referer)
        try {
            saveRecords(loadRecords() + saved)
        } catch (error: Exception) {
            manager.remove(id)
            throw error
        }
        return id
    }

    suspend fun cancel(id: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val records = loadRecords()
            val record = records.firstOrNull { it.id == id } ?: return@withLock
            val current = queryDownloads()[id] ?: return@withLock
            if (!current.isActive) return@withLock
            manager.remove(id)
            saveRecords(records.map { if (it.id == id) record.copy(cancelled = true) else it })
        }
    }

    suspend fun retry(id: Long): Long {
        val record = withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = readDownloads(verifyCompletedFiles = true).firstOrNull { it.id == id }
                if (current?.isActive == true || current?.status == DownloadStatus.READY) return@withLock null
                loadRecords().firstOrNull { it.id == id } ?: error("找不到下载记录，请重新输入地址")
            }
        } ?: return id
        val newId = enqueueDownload(record.url, record.fileName, record.mimeType, record.userAgent, record.referer)
        withContext(Dispatchers.IO) {
            mutex.withLock {
                manager.remove(id)
                saveRecords(loadRecords().filterNot { it.id == id })
                completedFileChecks.remove(id)
            }
        }
        return newId
    }

    /** Opens only the downloaded content URI. It can never fall back to the remote URL. */
    suspend fun openDownloaded(id: Long) {
        val (uri, task) = withContext(Dispatchers.IO) { mutex.withLock { readableDownload(id) } }
        require(!task.isApk) { "APK 文件请使用“安装 APK”按钮" }
        val mime = task.mimeType?.takeUnless { it == "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(task.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT))
            ?: "application/octet-stream"
        withContext(Dispatchers.Main) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    clipData = ClipData.newRawUri(task.fileName, uri)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (_: android.content.ActivityNotFoundException) {
                error("文件已下载到手机，但尚未安装能打开这类文件的应用。安装相应应用后，可返回下载中心再次打开。")
            }
        }
    }

    suspend fun installDownloaded(id: Long): InstallLaunchResult {
        val uri = withContext(Dispatchers.IO) {
            mutex.withLock {
                val (downloadedUri, task) = readableDownload(id)
                require(task.isApk) { "该文件不是 APK，请使用“打开文件”" }
                // A changed redirect or login page must never masquerade as a downloaded APK.
                val isZip = context.contentResolver.openInputStream(downloadedUri)?.use { stream ->
                    stream.read() == 0x50 && stream.read() == 0x4b && stream.read() == 0x03 && stream.read() == 0x04
                } == true
                require(isZip) { "下载内容不是有效的 APK 安装包，可能是网页或错误响应，请更换文件直链" }
                downloadedUri
            }
        }
        return withContext(Dispatchers.Main) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                InstallLaunchResult.NEEDS_PERMISSION
            } else {
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME_TYPE)
                    clipData = ClipData.newRawUri("APK", uri)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
                InstallLaunchResult.INSTALLER_OPENED
            }
        }
    }

    /** Freeze the exact bytes in private storage, then send an FD/stdin instead of an inaccessible app path. */
    internal suspend fun prepareApk(id: Long): PreparedApk = withContext(Dispatchers.IO) {
        val (uri, task) = mutex.withLock { readableDownload(id) }
        require(task.isApk) { "这个任务不是 APK 安装包" }
        val directory = File(context.cacheDir, "apk-install").apply { mkdirs() }
        val file = File.createTempFile("package-", ".apk", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取下载文件" }.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count
                        require(size <= 8L * 1024 * 1024 * 1024) { "APK 超过 8 GB" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(size > 0) { "APK 文件为空" }
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
                ?: error("无法解析 APK，请下载完整的单文件安装包（不支持 APKM / XAPK / APKS）")
            @Suppress("DEPRECATION")
            val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            file.setReadOnly()
            PreparedApk(file, task.fileName, info.packageName, info.versionName.orEmpty(), version, size,
                digest.digest().joinToString("") { "%02x".format(it) })
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private fun readableDownload(id: Long): Pair<Uri, DownloadTask> {
        val task = readDownloads(verifyCompletedFiles = true).firstOrNull { it.id == id } ?: error("找不到下载任务")
        require(task.status == DownloadStatus.READY) {
            task.detail ?: if (task.status == DownloadStatus.MISSING) "下载文件已移除或无法读取，请点击重新下载" else "文件尚未下载完成"
        }
        val uri = manager.getUriForDownloadedFile(id)
        require(uri != null && uri.scheme == "content") { "下载文件无法读取，请重新下载" }
        return uri to task
    }

    private fun checkCompletedFile(task: DownloadTask, now: Long): CompletedFileCheck = try {
        val uri = manager.getUriForDownloadedFile(task.id)
        val stream = uri?.takeIf { it.scheme == "content" }?.let { context.contentResolver.openInputStream(it) }
        if (stream == null) CompletedFileCheck(now, missing = true)
        else stream.use {
            val prefix = ByteArray(4096)
            var count = 0
            while (count < prefix.size) {
                val read = it.read(prefix, count, prefix.size - count)
                if (read <= 0) break
                count += read
            }
            CompletedFileCheck(now, issue = downloadedContentIssue(prefix.copyOf(count), task.fileName, task.mimeType))
        }
    } catch (_: FileNotFoundException) {
        CompletedFileCheck(now, missing = true)
    } catch (_: SecurityException) {
        CompletedFileCheck(now, missing = true)
    } catch (_: IOException) {
        CompletedFileCheck(now, issue = "暂时无法读取下载文件，请稍后重试")
    }

    private fun loadRecords(): List<SavedDownload> = runCatching {
        json.decodeFromString<List<SavedDownload>>(preferences.getString("tasks", "[]") ?: "[]")
    }.getOrDefault(emptyList())

    private fun saveRecords(records: List<SavedDownload>) {
        check(preferences.edit().putString("tasks", json.encodeToString(records)).commit()) {
            "无法保存下载记录，请检查存储空间"
        }
    }

    private fun readDownloads(verifyCompletedFiles: Boolean = false): List<DownloadTask> {
        val records = loadRecords()
        val snapshots = runCatching { queryDownloads() }.getOrElse {
            _issue.value = "暂时无法读取系统下载状态，稍后会自动重试"
            return records.sortedByDescending { it.createdAt }.map { record ->
                if (record.cancelled || record.missing) record.toTask()
                else record.toTask(DownloadStatus.UNAVAILABLE).copy(detail = "暂时无法读取系统下载状态，请稍后重试")
            }
        }
        _issue.value = null
        val knownIds = records.mapTo(mutableSetOf()) { it.id }
        // DownloadManager queries are scoped to this application; import older app-owned transfers.
        val imported = snapshots.values.filter { it.id !in knownIds }
            .map { SavedDownload(it.id, it.url, it.fileName, it.createdAt, mimeType = it.mimeType) }
        var saved = records + imported
        val missingIds = mutableSetOf<Long>()
        val now = System.currentTimeMillis()
        val tasks = saved.sortedByDescending { it.createdAt }.map { record ->
            val task = when {
                record.cancelled -> record.toTask(DownloadStatus.CANCELLED)
                record.missing -> record.toTask(DownloadStatus.MISSING)
                else -> snapshots[record.id]?.let {
                    it.copy(fileName = record.fileName, createdAt = record.createdAt, mimeType = it.mimeType ?: record.mimeType)
                } ?: record.toTask(DownloadStatus.MISSING)
            }
            when {
                task.status == DownloadStatus.READY -> {
                    val cached = completedFileChecks[task.id]
                    val check = if (verifyCompletedFiles || cached == null || now - cached.checkedAt > 30_000) {
                        checkCompletedFile(task, now).also { completedFileChecks[task.id] = it }
                    } else cached
                    when {
                        check.missing -> {
                            missingIds.add(task.id)
                            task.copy(status = DownloadStatus.MISSING, detail = "下载文件已移除或无法读取，请重新下载")
                        }
                        check.issue != null -> task.copy(status = DownloadStatus.FAILED, detail = check.issue)
                        else -> task
                    }
                }
                else -> task
            }
        }
        if (missingIds.isNotEmpty()) saved = saved.map { if (it.id in missingIds) it.copy(missing = true) else it }
        if (imported.isNotEmpty() || missingIds.isNotEmpty()) saveRecords(saved)
        return tasks
    }

    private fun queryDownloads(): Map<Long, DownloadTask> {
        val result = mutableMapOf<Long, DownloadTask>()
        val cursor = manager.query(DownloadManager.Query()) ?: error("无法读取系统下载记录")
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val urlColumn = it.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            val titleColumn = it.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
            val mimeColumn = it.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)
            val statusColumn = it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val reasonColumn = it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            val downloadedColumn = it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalColumn = it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val updatedColumn = it.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
            while (it.moveToNext()) {
                val url = it.getString(urlColumn) ?: continue
                val status = it.getInt(statusColumn)
                val id = it.getLong(idColumn)
                result[id] = DownloadTask(
                    id = id,
                    url = url,
                    fileName = it.getString(titleColumn) ?: "download",
                    createdAt = it.getLong(updatedColumn),
                    status = downloadStatus(status),
                    downloadedBytes = it.getLong(downloadedColumn).coerceAtLeast(0),
                    totalBytes = it.getLong(totalColumn),
                    detail = downloadStatusDetail(status, it.getInt(reasonColumn)),
                    mimeType = normalizedDownloadMimeType(it.getString(mimeColumn)),
                )
            }
        }
        return result
    }
}

enum class InstallLaunchResult { NEEDS_PERMISSION, INSTALLER_OPENED }
