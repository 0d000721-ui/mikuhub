package me.rerere.rikkahub.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException

/** Fixed categories only: diagnostics must never contain website or exception text. */
internal enum class BrowserImageStage {
    START, FETCH, CHUNKS, SIGNATURE, DECODE, CREATE, WRITE, PUBLISH, COMPLETE,
}

internal fun browserImageScriptFailure(code: String?): String = when (code) {
    "origin", "busy", "http", "type", "size", "unsupported", "empty", "cancelled", "fetch", "read" -> "js_$code"
    else -> "js_unknown"
}

internal fun browserImageTransport(value: String?): String = when (value) {
    "retained_blob", "cached_blob", "blob", "https" -> value
    else -> "unknown"
}

internal fun browserImageNativeFailure(error: Throwable): String = when (error) {
    is TimeoutCancellationException -> "timeout"
    is CancellationException -> "cancelled"
    is SecurityException -> "security"
    is IOException -> "io"
    is IllegalArgumentException -> "invalid_data"
    is IllegalStateException -> "invalid_state"
    else -> "native"
}
