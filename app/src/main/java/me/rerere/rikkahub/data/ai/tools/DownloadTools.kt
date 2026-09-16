package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.device.DownloadInstallManager
import me.rerere.rikkahub.device.DownloadStatus
import me.rerere.rikkahub.device.DownloadTask

fun createDownloadTools(manager: DownloadInstallManager): List<Tool> = buildDownloadTools(
    start = { url, name -> manager.enqueueDownload(url, name) },
    snapshot = manager::snapshot,
    cancel = manager::cancel,
)

/** Function arguments keep tool semantics testable without a running Android DownloadManager. */
internal fun buildDownloadTools(
    start: suspend (url: String, name: String?) -> Long,
    snapshot: suspend () -> List<DownloadTask>,
    cancel: suspend (id: Long) -> Unit,
): List<Tool> = listOf(
    Tool(
        name = "download_start",
        description = """
            Download a real file onto this phone using an HTTP(S) direct file URL; the user can open it in the app's Downloads center.
            Works without opening a browser. Supports APK, ZIP, documents, images and other files.
            HTML landing pages are rejected: locate an actual file URL first. No cookies or login credentials are forwarded.
            Requires user approval. Returns a queued task_id, NOT a completed download.
            Use download_status to verify bytes and completion; never claim the file is ready until status is READY.
            Does not open the website, open the file, or install APKs. If the user also requested installation, call download_install with the returned task_id; it waits for completion and installs through the selected device transport.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("url", buildJsonObject {
                        put("type", "string")
                        put("description", "The direct HTTP(S) file URL, not a web page URL")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional desired file name with its correct extension; server filename is used if omitted")
                    })
                },
                required = listOf("url"),
            )
        },
        needsApproval = { true },
        execute = {
            val input = it.jsonObject
            val url = input.string("url")?.takeIf(String::isNotBlank) ?: error("url is required")
            val id = start(url, input.string("name"))
            textResult(buildJsonObject {
                put("task_id", id)
                put("status", "QUEUED")
                put("complete", false)
                put("message", "下载任务已创建，系统正在后台传输。请通过 download_status 查询实际进度，不要将排队视为下载完成。")
            })
        },
    ),
    Tool(
        name = "download_status",
        description = """
            Read actual Android download tasks, received bytes, total bytes and status.
            A READY result means the completed local file was checked to be readable.
            Other states, including QUEUED, DOWNLOADING, WAITING and UNAVAILABLE, are not completion.
            Provide task_id for one task; omit it for up to 50 recent tasks, with active tasks first.
            Do not busy-poll in a loop; progress is also visible beside the model and in Downloads.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("task_id", taskIdProperty("Optional task ID returned by download_start"))
            })
        },
        needsApproval = { false },
        execute = {
            val input = it.jsonObject
            val id = input.taskId(required = false)
            val all = snapshot()
            val selected = if (id == null) all.sortedBy { if (it.isActive) 0 else 1 }.take(50)
                else listOf(all.firstOrNull { task -> task.id == id } ?: error("找不到下载任务 $id"))
            textResult(buildJsonObject {
                put("total_tasks", all.size)
                put("tasks", buildJsonArray { selected.forEach { add(it.toToolJson()) } })
            })
        },
    ),
    Tool(
        name = "download_cancel",
        description = "Cancel an active download by task_id after user approval. Does not delete completed files or install packages.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject { put("task_id", taskIdProperty("The active task ID to cancel")) },
                required = listOf("task_id"),
            )
        },
        needsApproval = { true },
        execute = {
            val id = requireNotNull(it.jsonObject.taskId(required = true))
            val before = snapshot().firstOrNull { task -> task.id == id } ?: error("找不到下载任务 $id")
            require(before.isActive) { "任务已${before.statusLabel}，无需取消" }
            cancel(id)
            val after = snapshot().firstOrNull { task -> task.id == id } ?: error("取消后暂时无法读取任务状态，请稍后查询")
            textResult(buildJsonObject {
                put("cancelled", after.status == DownloadStatus.CANCELLED)
                put("task", after.toToolJson())
            })
        },
    ),
)

private fun taskIdProperty(description: String) = buildJsonObject {
    put("type", "integer")
    put("minimum", 1)
    put("description", description)
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.taskId(required: Boolean): Long? {
    val value = this["task_id"] ?: return if (required) error("task_id is required") else null
    return (value as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: error("task_id must be a positive integer")
}

private fun DownloadTask.toToolJson() = buildJsonObject {
    put("task_id", id)
    put("file_name", fileName)
    put("status", status.name)
    put("status_label", statusLabel)
    put("complete", status == DownloadStatus.READY)
    put("downloaded_bytes", downloadedBytes)
    if (totalBytes >= 0) put("total_bytes", totalBytes)
    percent?.let { put("percent", it) }
    mimeType?.let { put("mime_type", it) }
    detail?.let { put("detail", it) }
    if (status == DownloadStatus.READY) {
        put("next_action", if (isApk) "若用户要求安装，调用 download_install(task_id)；也可在下载中心选择静默安装" else "用户可在下载中心打开本地文件")
    }
}

private fun textResult(value: JsonObject): List<UIMessagePart> = listOf(UIMessagePart.Text(value.toString()))
