package me.rerere.rikkahub.service

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor

class TrafficCaptureVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopCapture() else startCapture()
        return START_NOT_STICKY
    }
    private fun startCapture() {
        tun = Builder().setSession("RikkaHub traffic diagnostics").addAddress("10.0.0.2", 32).addRoute("0.0.0.0", 0).establish()
    }
    private fun stopCapture() { tun?.close(); tun = null; stopSelf() }
    override fun onDestroy() { stopCapture(); super.onDestroy() }
    companion object { const val ACTION_STOP = "me.rerere.rikkahub.action.STOP_TRAFFIC_CAPTURE" }
}
