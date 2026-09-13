package me.rerere.rikkahub.device

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Keeps the permission flow testable without a running Android Binder service. */
internal interface ShizukuPermissionBackend {
    fun isBinderAlive(): Boolean
    fun isPreV11(): Boolean
    fun isPermissionGranted(): Boolean
    fun shouldShowRequestPermissionRationale(): Boolean
    fun requestPermission(requestCode: Int)
    fun observe(
        onBinderReceived: () -> Unit,
        onBinderDead: () -> Unit,
        onPermissionResult: (requestCode: Int, granted: Boolean) -> Unit,
    ): AutoCloseable
}

internal object AndroidShizukuPermissionBackend : ShizukuPermissionBackend {
    override fun isBinderAlive() = Shizuku.pingBinder()
    override fun isPreV11() = Shizuku.isPreV11()
    override fun isPermissionGranted() = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    override fun shouldShowRequestPermissionRationale() = Shizuku.shouldShowRequestPermissionRationale()
    override fun requestPermission(requestCode: Int) = Shizuku.requestPermission(requestCode)

    override fun observe(
        onBinderReceived: () -> Unit,
        onBinderDead: () -> Unit,
        onPermissionResult: (Int, Boolean) -> Unit,
    ): AutoCloseable {
        val received = Shizuku.OnBinderReceivedListener { onBinderReceived() }
        val dead = Shizuku.OnBinderDeadListener { onBinderDead() }
        val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
            onPermissionResult(code, result == PackageManager.PERMISSION_GRANTED)
        }
        val registration = AutoCloseable {
            runCatching { Shizuku.removeBinderReceivedListener(received) }
            runCatching { Shizuku.removeBinderDeadListener(dead) }
            runCatching { Shizuku.removeRequestPermissionResultListener(permission) }
        }
        try {
            Shizuku.addBinderDeadListener(dead)
            Shizuku.addRequestPermissionResultListener(permission)
            // Also delivers a Binder that arrived before the device page was opened.
            Shizuku.addBinderReceivedListenerSticky(received)
        } catch (error: Exception) {
            registration.close()
            throw error
        }
        return registration
    }
}
