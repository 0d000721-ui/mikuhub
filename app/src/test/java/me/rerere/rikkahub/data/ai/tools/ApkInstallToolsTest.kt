package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.device.ApkInstallation
import me.rerere.rikkahub.device.ApkInstallStatus
import org.junit.Assert.*
import org.junit.Test

class ApkInstallToolsTest {
    @Test fun installedFlagComesFromInstallResultNotDownloadState() = runBlocking {
        ApkInstallStatus.entries.forEach { state ->
            val tool = buildApkInstallTools(
                install = { id -> ApkInstallation(id, state, "actual result") }, status = { emptyMap() },
            ).first()
            val result = (tool.execute(buildJsonObject { put("task_id", 17) }).single() as UIMessagePart.Text).text
            val payload = Json.parseToJsonElement(result).jsonObject
            assertEquals(state == ApkInstallStatus.SUCCEEDED, payload["installed"]!!.jsonPrimitive.boolean)
            assertEquals(17L, payload["task_id"]!!.jsonPrimitive.long)
        }
    }
    @Test fun invalidTaskCannotInvokeInstaller() = runBlocking {
        var called = false
        val tool = buildApkInstallTools({ called = true; error("unexpected") }, { emptyMap() }).first()
        assertTrue(runCatching { tool.execute(buildJsonObject { put("task_id", -1) }) }.isFailure)
        assertFalse(called)
    }
    @Test fun readingStatusDoesNotStartInstallation() = runBlocking {
        val record = ApkInstallation(17, ApkInstallStatus.UNKNOWN, "interrupted")
        val tool = buildApkInstallTools({ error("must not install") }, { mapOf(17L to record) }).last()
        val result = (tool.execute(buildJsonObject { put("task_id", 17) }).single() as UIMessagePart.Text).text
        val row = Json.parseToJsonElement(result).jsonObject["installations"]!!.jsonArray.single().jsonObject
        assertFalse(row["installed"]!!.jsonPrimitive.boolean)
        assertEquals("UNKNOWN", row["status"]!!.jsonPrimitive.content)
    }
}
