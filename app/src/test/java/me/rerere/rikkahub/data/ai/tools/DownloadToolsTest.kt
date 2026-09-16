package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.device.DownloadStatus
import me.rerere.rikkahub.device.DownloadTask
import org.junit.Assert.*
import org.junit.Test

class DownloadToolsTest {
    private fun task(status: DownloadStatus = DownloadStatus.DOWNLOADING) = DownloadTask(
        id = 42, url = "https://example.com/manual.pdf", fileName = "manual.pdf", createdAt = 100,
        status = status, downloadedBytes = 512, totalBytes = 2048, mimeType = "application/pdf",
    )

    private fun List<UIMessagePart>.payload(): JsonObject = Json.parseToJsonElement((single() as UIMessagePart.Text).text).jsonObject

    @Test fun startReturnsQueuedIdAndDoesNotClaimCompletion() = runBlocking {
        var receivedUrl = ""
        var receivedName: String? = null
        val tools = buildDownloadTools(
            start = { url, name -> receivedUrl = url; receivedName = name; 42 },
            snapshot = { emptyList() },
            cancel = { error("must not cancel") },
        )
        val start = tools.single { it.name == "download_start" }
        val input = buildJsonObject { put("url", "https://example.com/file"); put("name", "manual.pdf") }
        assertTrue(start.needsApproval(input))
        val result = start.execute(input).payload()
        assertEquals(42L, result["task_id"]!!.jsonPrimitive.long)
        assertEquals("QUEUED", result["status"]!!.jsonPrimitive.content)
        assertFalse(result["complete"]!!.jsonPrimitive.boolean)
        assertEquals("https://example.com/file", receivedUrl)
        assertEquals("manual.pdf", receivedName)
    }

    @Test fun readOnlyStatusReportsActualCountersAndUnknownTotalStaysUnknown() = runBlocking {
        val tools = buildDownloadTools(start = { _, _ -> error("must not start") }, snapshot = { listOf(task().copy(totalBytes = -1)) }, cancel = {})
        val status = tools.single { it.name == "download_status" }
        val input = buildJsonObject { put("task_id", 42) }
        assertFalse(status.needsApproval(input))
        val result = status.execute(input).payload()["tasks"]!!.jsonArray.single().jsonObject
        assertEquals(512L, result["downloaded_bytes"]!!.jsonPrimitive.long)
        assertFalse(result.containsKey("total_bytes"))
        assertFalse(result.containsKey("percent"))
        assertFalse(result["complete"]!!.jsonPrimitive.boolean)
    }

    @Test fun onlyReadableReadySnapshotIsReportedComplete() = runBlocking {
        var current = task(DownloadStatus.READY)
        val status = buildDownloadTools(start = { _, _ -> 1 }, snapshot = { listOf(current) }, cancel = {}).single { it.name == "download_status" }
        val empty = buildJsonObject {}
        assertTrue(status.execute(empty).payload()["tasks"]!!.jsonArray.single().jsonObject["complete"]!!.jsonPrimitive.boolean)
        current = current.copy(status = DownloadStatus.MISSING)
        assertFalse(status.execute(empty).payload()["tasks"]!!.jsonArray.single().jsonObject["complete"]!!.jsonPrimitive.boolean)
    }

    @Test fun cancellationRequiresApprovalAndReturnsObservedState() = runBlocking {
        var current = task()
        val cancel = buildDownloadTools(start = { _, _ -> 1 }, snapshot = { listOf(current) }, cancel = { id ->
            assertEquals(42L, id)
            current = current.copy(status = DownloadStatus.CANCELLED)
        }).single { it.name == "download_cancel" }
        val input = buildJsonObject { put("task_id", 42) }
        assertTrue(cancel.needsApproval(input))
        assertTrue(cancel.execute(input).payload()["cancelled"]!!.jsonPrimitive.boolean)
    }

    @Test fun cancellationRaceWithCompletionDoesNotFalselyClaimCancelled() = runBlocking {
        var current = task()
        val cancel = buildDownloadTools(start = { _, _ -> 1 }, snapshot = { listOf(current) }, cancel = {
            current = current.copy(status = DownloadStatus.READY)
        }).single { it.name == "download_cancel" }
        val result = cancel.execute(buildJsonObject { put("task_id", 42) }).payload()
        assertFalse(result["cancelled"]!!.jsonPrimitive.boolean)
        assertEquals("READY", result["task"]!!.jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test fun unknownOrInvalidTaskIdsFailWithoutCancellingAnything() = runBlocking {
        var cancelled = false
        val tools = buildDownloadTools(start = { _, _ -> 1 }, snapshot = { listOf(task()) }, cancel = { cancelled = true })
        val cancel = tools.single { it.name == "download_cancel" }
        assertTrue(runCatching { cancel.execute(buildJsonObject { put("task_id", 999) }) }.isFailure)
        assertTrue(runCatching { cancel.execute(buildJsonObject { put("task_id", -42) }) }.isFailure)
        assertTrue(runCatching { tools.single { it.name == "download_status" }.execute(buildJsonObject { put("task_id", "bad") }) }.isFailure)
        assertFalse(cancelled)
    }
}
