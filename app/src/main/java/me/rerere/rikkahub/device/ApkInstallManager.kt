package me.rerere.rikkahub.device

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File

internal data class PreparedApk(
    val file: File, val fileName: String, val packageName: String, val versionName: String,
    val versionCode: Long, val size: Long, val sha256: String,
) : Closeable {
    override fun close() { file.delete() }
}

@Serializable
enum class ApkInstallStatus { WAITING_DOWNLOAD, PREPARING, WAITING_APPROVAL, INSTALLING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }

@Serializable
data class ApkInstallation(
    val taskId: Long,
    val status: ApkInstallStatus,
    val detail: String,
    val packageName: String? = null,
    val versionCode: Long? = null,
    val attemptedAt: Long = System.currentTimeMillis(),
) {
    val isActive get() = status in setOf(ApkInstallStatus.WAITING_DOWNLOAD, ApkInstallStatus.PREPARING,
        ApkInstallStatus.WAITING_APPROVAL, ApkInstallStatus.INSTALLING)
}

/** One installation at a time, through the selected device transport and the existing session controls. */
class ApkInstallManager(
    context: Context,
    private val downloads: DownloadInstallManager,
    private val session: DeviceAccessSession,
    private val backend: DeviceCommandBackend,
    private val confirmations: DeviceCommandConfirmations,
    private val shizuku: ShizukuDeviceCommandRunner,
    private val root: RootDeviceCommandRunner,
) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("apk_install_results", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _states = MutableStateFlow<Map<Long, ApkInstallation>>(emptyMap())
    val states = _states.asStateFlow()
    private var restored = false
    @Volatile private var active: Pair<Long, Job>? = null

    fun stop(taskId: Long) {
        active?.takeIf { it.first == taskId }?.second?.cancel(CancellationException("用户停止安装"))
    }

    suspend fun snapshot(): Map<Long, ApkInstallation> = withContext(Dispatchers.IO) {
        synchronized(this@ApkInstallManager) {
            if (!restored) {
                val saved = runCatching { json.decodeFromString<List<ApkInstallation>>(preferences.getString("results", "[]")!!) }
                    .getOrDefault(emptyList())
                _states.value = saved.associate { record -> record.taskId to recover(record) }
                restored = true
            }
            _states.value
        }
    }

    private fun recover(record: ApkInstallation): ApkInstallation {
        if (!record.isActive) return record
        // Installing an update of this app kills its process. Verify that exact version on the next launch.
        if (record.status == ApkInstallStatus.INSTALLING && record.packageName == context.packageName) {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            if (version == record.versionCode && info.lastUpdateTime >= record.attemptedAt) {
                return record.copy(status = ApkInstallStatus.SUCCEEDED, detail = "本应用更新已完成，已核对版本及安装时间")
            }
        }
        return record.copy(status = ApkInstallStatus.UNKNOWN, detail = "上次安装流程被中断，结果未确认；请检查应用状态后决定是否重试")
    }

    private fun update(record: ApkInstallation) {
        _states.value = (_states.value + (record.taskId to record)).values.sortedByDescending { it.attemptedAt }
            .take(100).associateBy { it.taskId }
        // Failure to save a receipt must never change an already successful installation into failure.
        runCatching { preferences.edit().putString("results", json.encodeToString(_states.value.values.toList())).commit() }
    }

    suspend fun install(taskId: Long): ApkInstallation = withContext(Dispatchers.IO) {
        val transport = session.state.value.preferredTransport
        val accessVersion = session.accessVersion
        mutex.withLock {
            snapshot()
            active = taskId to requireNotNull(currentCoroutineContext()[Job])
            fun checkSession() {
                check(!session.stopped && session.authorization != DeviceAuthorization.REVOKED) { "设备会话已停止，请在设备控制页重新授权" }
                check(session.accessVersion == accessVersion) { "设备授权已改变，请重新发起安装" }
                check(session.state.value.preferredTransport == transport) { "安装通道已改变，请重新发起安装" }
            }
            var record = ApkInstallation(taskId, ApkInstallStatus.WAITING_DOWNLOAD, "等待 APK 下载完成，完成后继续安装")
            try {
                checkSession()
                checkTransport(transport)
                update(record)
                withTimeout(15 * 60_000L) {
                    val tasks = downloads.downloads.first { tasks ->
                        val task = tasks.firstOrNull { it.id == taskId }
                        task == null || !task.isActive || !task.isApk || session.stopped ||
                            session.authorization == DeviceAuthorization.REVOKED || session.state.value.preferredTransport != transport
                            || session.accessVersion != accessVersion
                    }
                    currentCoroutineContext().ensureActive()
                    checkSession()
                    val task = tasks.firstOrNull { it.id == taskId } ?: error("找不到下载任务 #$taskId")
                    require(task.isApk) { "该任务不是 APK，请使用完整的单文件 APK" }
                    check(task.status == DownloadStatus.READY) { task.detail ?: "下载尚未完成：${task.statusLabel}" }
                }
                record = record.copy(status = ApkInstallStatus.PREPARING, detail = "正在校验安装包")
                update(record)
                downloads.prepareApk(taskId).use { apk ->
                    checkSession()
                    // Android allocates a range of 100000 UIDs to each Android user/profile.
                    val userId = android.os.Process.myUid() / 100_000
                    val args = apkInstallArguments(apk.size, userId)
                    record = record.copy(status = ApkInstallStatus.WAITING_APPROVAL, packageName = apk.packageName,
                        versionCode = apk.versionCode, detail = "准备安装 ${apk.packageName} ${apk.versionName}")
                    update(record)
                    val preview = DeviceCommandPolicy.preview(
                        DeviceShellCommand(args).command,
                        "静默安装 ${apk.fileName}\n${apk.packageName} ${apk.versionName}\nSHA-256: ${apk.sha256}",
                        "安装到当前 Android 用户 $userId；同签名应用会覆盖更新，保留其数据。" +
                            if (apk.packageName == context.packageName) "更新本应用后会退出，可重新打开。" else "",
                        "安装新的应用代码；签名冲突、缺少分包或系统安装限制会返回错误。",
                    )
                    val output = session.execute(preview, transport, object : DeviceCommandRunner {
                        override suspend fun run(command: String): String {
                            checkSession()
                            checkTransport(transport)
                            record = record.copy(status = ApkInstallStatus.INSTALLING, detail = "正在通过 ${transport.name} 静默安装")
                            update(record)
                            return when (transport) {
                                DeviceTransport.SHIZUKU -> shizuku.installApk(apk.file, apk.size, userId)
                                DeviceTransport.ROOT -> root.installApk(apk.file, apk.size, userId)
                                else -> error("请在设备控制页选择 Shizuku（ADB）或 Root")
                            }
                        }
                    }, requestConfirmation = { checked, actual -> confirmations.request(checked, actual) })
                    requireApkInstallSuccess(output)
                    record = record.copy(status = ApkInstallStatus.SUCCEEDED,
                        detail = "已静默安装 ${apk.packageName} ${apk.versionName}（安装服务返回 Success）")
                    update(record)
                    record
                }
            } catch (cancelled: CancellationException) {
                record = record.copy(status = if (record.status == ApkInstallStatus.INSTALLING) ApkInstallStatus.UNKNOWN else ApkInstallStatus.CANCELLED,
                    detail = if (record.status == ApkInstallStatus.INSTALLING) "已停止等待安装结果，系统可能已提交安装；请检查应用状态" else "安装等待已取消或超时")
                update(record)
                throw cancelled
            } catch (error: Exception) {
                val unknown = record.status == ApkInstallStatus.INSTALLING &&
                    (error is android.os.RemoteException || (error is DeviceShellException && (error.result.timedOut || error.result.cancelled)))
                record = record.copy(status = when {
                    error is DeviceCommandRejectedException -> ApkInstallStatus.CANCELLED
                    unknown -> ApkInstallStatus.UNKNOWN
                    else -> ApkInstallStatus.FAILED
                }, detail = if (unknown) "安装结果未确认：${error.message}。请检查应用状态后再决定是否重试。"
                    else apkInstallFailureHint(error.message ?: "静默安装未完成"))
                update(record)
                record
            } finally {
                active = null
            }
        }
    }

    private suspend fun checkTransport(transport: DeviceTransport) {
        if (transport == DeviceTransport.SHIZUKU) {
            val status = backend.status()
            check(status.shizukuState == ShizukuState.AUTHORIZED && status.shizukuUid in setOf(0, 2000)) {
                "请先在设备控制页授权 Shizuku，并使用 ADB / 无线调试启动它；不会自动改用 Root"
            }
        }
    }
}
