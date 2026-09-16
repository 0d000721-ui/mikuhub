package me.rerere.rikkahub.device

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class PendingDeviceCommand(
    val id: Long,
    val preview: DeviceCommandPreview,
    val transport: DeviceTransport,
    val step: Int = 1,
)

/** Confirmation steps originate in the device UI, never in model-supplied arguments. */
class DeviceCommandConfirmations(private val timeoutMillis: Long = 300_000) {
    private val mutex = Mutex()
    private val lock = Any()
    private var sequence = 0L
    private var answer: CompletableDeferred<Int>? = null
    private val _pending = MutableStateFlow<PendingDeviceCommand?>(null)
    val pending = _pending.asStateFlow()

    suspend fun request(preview: DeviceCommandPreview, transport: DeviceTransport): Int = mutex.withLock {
        val deferred = CompletableDeferred<Int>()
        val request = synchronized(lock) {
            PendingDeviceCommand(++sequence, preview, transport).also {
                answer = deferred
                _pending.value = it
            }
        }
        try {
            withTimeoutOrNull(timeoutMillis) { deferred.await() } ?: 0
        } finally {
            synchronized(lock) {
                if (answer === deferred) {
                    answer = null
                    _pending.value = null
                }
                deferred.cancel()
            }
        }
    }

    fun confirm(id: Long, step: Int) = synchronized(lock) {
        val request = _pending.value ?: return@synchronized
        if (request.id != id || request.step != step) return@synchronized
        if (step < request.preview.confirmationCount) {
            _pending.value = request.copy(step = step + 1)
        } else {
            answer?.complete(step)
            _pending.value = null
        }
    }

    fun reject(id: Long, reason: String = "") = synchronized(lock) {
        if (_pending.value?.id != id) return@synchronized
        if (reason.isBlank()) {
            answer?.complete(0)
        } else {
            answer?.completeExceptionally(DeviceCommandRejectedException("用户拒绝了本次操作：${reason.trim()}"))
        }
        _pending.value = null
    }
}
