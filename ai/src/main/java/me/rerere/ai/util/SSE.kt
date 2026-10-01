package me.rerere.ai.util

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.stripBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.internal.ServerSentEventReader
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

class SSEEventSource(
    private val request: Request,
    private val listener: EventSourceListener,
    private val allowHeaderlessResponses: Boolean = false,
) : EventSource,
    ServerSentEventReader.Callback,
    Callback {
    private var call: Call? = null

    @Volatile
    private var canceled = false

    fun connect(callFactory: Call.Factory) {
        call =
            callFactory.newCall(request).apply {
                enqueue(this@SSEEventSource)
            }
    }

    override fun onResponse(
        call: Call,
        response: Response,
    ) {
        processResponse(response)
    }

    fun processResponse(response: Response) {
        response.use {
            if (!response.isSuccessful) {
                listener.onFailure(this, null, response)
                return
            }

            val body = response.body

            if (!body.isEventStream() && !isHeaderlessResponses(response)) {
                listener.onFailure(
                    this,
                    IllegalStateException("Invalid content-type: ${body.contentType()}"),
                    response,
                )
                return
            }

            // This is a long-lived response. Cancel full-call timeouts.
            call?.timeout()?.cancel()

            // Replace the body with a stripped one so the callbacks can't see real data.
            val response = response.stripBody()

            val reader = ServerSentEventReader(body.source(), this)
            try {
                if (!canceled) {
                    listener.onOpen(this, response)
                    while (!canceled && reader.processNextEvent()) {
                    }
                }
            } catch (e: Exception) {
                val exception =
                    when {
                        canceled -> IOException("canceled", e)
                        else -> e
                    }
                listener.onFailure(this, exception, response)
                return
            }
            if (canceled) {
                listener.onFailure(this, IOException("canceled"), response)
            } else {
                listener.onClosed(this)
            }
        }
    }

    private fun ResponseBody.isEventStream(): Boolean {
        val contentType = contentType() ?: return false
        return contentType.type == "text" && contentType.subtype == "event-stream"
    }

    /** Accept only a real Responses SSE envelope, without consuming its first event. */
    private fun isHeaderlessResponses(response: Response): Boolean {
        if (!allowHeaderlessResponses || request.method != "POST" || request.url.scheme != "https" ||
            request.url.host != "api.openai.com" || request.url.encodedPath != "/v1/responses" ||
            response.header("Content-Type") != null || response.body.contentType() != null
        ) return false

        // response.created can contain large instructions and tool schemas. Bound the probe rather
        // than buffering the whole response; the normal SSE reader will replay these same bytes.
        val prefix = response.body.source().peek()
        var remaining = 1024L * 1024
        val data = mutableListOf<String>()
        try {
            var firstLine = true
            while (remaining > 0) {
                val rawLine = prefix.readUtf8LineStrict(remaining)
                remaining -= rawLine.toByteArray(Charsets.UTF_8).size + 1
                if (remaining < 0) return false
                val line = if (firstLine) rawLine.removePrefix("\uFEFF") else rawLine
                firstLine = false
                if (line.isEmpty()) {
                    if (data.isEmpty()) continue
                    val payload = json.parseToJsonElement(data.joinToString("\n")) as? JsonObject
                    return payload?.get("type")?.jsonPrimitive?.contentOrNull?.startsWith("response.") == true
                }
                if (line.startsWith(':')) continue
                when (line.substringBefore(':')) {
                    "data" -> data += line.substringAfter(':', "").removePrefix(" ")
                    "event", "id", "retry" -> Unit
                    else -> return false
                }
            }
        } catch (_: Exception) {
            // JSON and line-reading exceptions may quote the payload; do not expose those messages.
            return false
        } finally {
            prefix.close()
        }
        return false
    }

    override fun onFailure(
        call: Call,
        e: IOException,
    ) {
        listener.onFailure(this, e, null)
    }

    override fun request(): Request = request

    override fun cancel() {
        canceled = true
        call?.cancel()
    }

    override fun onEvent(
        id: String?,
        type: String?,
        data: String,
    ) {
        listener.onEvent(this, id, type, data)
    }

    override fun onRetryChange(timeMs: Long) {
        // Ignored. We do not auto-retry.
    }

    companion object {
        fun factory(callFactory: Call.Factory, allowHeaderlessResponses: Boolean = false) = EventSource.Factory { request, listener ->
            val actualRequest =
                if (request.header("Accept") == null) {
                    request.newBuilder().addHeader("Accept", "text/event-stream").build()
                } else {
                    request
                }

            SSEEventSource(actualRequest, listener, allowHeaderlessResponses).apply {
                connect(callFactory)
            }
        }
    }
}
