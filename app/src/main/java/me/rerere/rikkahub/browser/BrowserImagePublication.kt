package me.rerere.rikkahub.browser

import java.io.OutputStream

internal class BrowserImagePublication<T>(
    private val open: () -> OutputStream,
    private val publish: () -> T,
    private val remove: () -> Unit,
) {
    private var ready = false
    private var committed = false
    private var discarded = false

    fun write(bytes: ByteArray, checkActive: () -> Unit) {
        check(!ready && !committed && !discarded)
        try {
            open().use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    checkActive()
                    val count = minOf(32 * 1024, bytes.size - offset)
                    output.write(bytes, offset, count)
                    offset += count
                }
                output.flush()
            }
            checkActive()
            ready = true
        } catch (error: Throwable) {
            discard()
            throw error
        }
    }

    fun commit(checkActive: () -> Unit): T {
        check(ready && !committed && !discarded) { "图片尚未完整写入，未保存" }
        checkActive()
        return publish().also { committed = true }
    }

    fun discard() {
        if (!committed && !discarded) discarded = runCatching(remove).isSuccess
    }
}
