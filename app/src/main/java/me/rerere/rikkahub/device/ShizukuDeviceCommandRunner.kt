package me.rerere.rikkahub.device

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

class ShizukuDeviceCommandRunner(context: Context) : DeviceCommandRunner {
    private val bindMutex = Mutex()
    private var remote: IShizukuUserService? = null
    private var connection: ServiceConnection? = null
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuUserService::class.java))
        .tag("device-commands-v3").version(3).daemon(false).processNameSuffix("device").debuggable(false)

    suspend fun installApk(file: File, size: Long, userId: Int): String = coroutineScope {
        val service = service()
        val execution = async(Dispatchers.IO) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                service.installApk(it, size, userId)
            }
        }
        try {
            val result = withTimeoutOrNull(APK_INSTALL_TIMEOUT + 10_000) { execution.await() }
                ?: throw DeviceShellException(DeviceShellResult(-1, "安装服务未返回结果，请检查安装状态后再决定是否重试", timedOut = true))
            requireApkInstallSuccess(result.checkedOutput())
        } finally {
            if (!execution.isCompleted) runCatching { service.cancel() }
            execution.cancel()
        }
    }

    private suspend fun service(): IShizukuUserService = bindMutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            check(Shizuku.pingBinder()) { "Shizuku 未连接，请打开 Shizuku 启动服务" }
            check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "Shizuku 尚未授权，请在设备控制页授权" }
            remote?.takeIf { it.asBinder().pingBinder() }?.let { return@withContext it }
            connection?.let { runCatching { Shizuku.unbindUserService(args, it, false) } }
            val ready = CompletableDeferred<IShizukuUserService>()
            val callback = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (connection !== this || !ready.isActive) return
                    val binder = IShizukuUserService.Stub.asInterface(service)
                    remote = binder
                    ready.complete(binder)
                }
                override fun onServiceDisconnected(name: ComponentName) = disconnected()
                override fun onBindingDied(name: ComponentName) = disconnected()
                override fun onNullBinding(name: ComponentName) = disconnected()
                private fun disconnected() {
                    if (connection === this) remote = null
                    ready.completeExceptionally(IllegalStateException("Shizuku 命令服务已断开"))
                }
            }
            connection = callback
            try {
                Shizuku.bindUserService(args, callback)
                withTimeoutOrNull(10_000) { ready.await() } ?: error("连接 Shizuku 命令服务超时，请重启 Shizuku 后重试")
            } catch (error: Exception) {
                ready.cancel()
                connection = null
                remote = null
                runCatching { Shizuku.unbindUserService(args, callback, true) }
                throw error
            }
        }
    }

    override suspend fun run(command: String): String = coroutineScope {
        val service = service()
        val execution = async(Dispatchers.IO) { service.exec(command, "/") }
        try {
            val result = withTimeoutOrNull(35_000) { execution.await() }
                ?: throw DeviceShellException(DeviceShellResult(-1, "Shizuku 命令服务未返回结果", timedOut = true))
            result.checkedOutput()
        } finally {
            if (!execution.isCompleted) runCatching { service.cancel() }
            execution.cancel()
        }
    }
}
