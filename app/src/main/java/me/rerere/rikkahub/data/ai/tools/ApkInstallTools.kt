package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.device.ApkInstallManager
import me.rerere.rikkahub.device.ApkInstallStatus
import me.rerere.rikkahub.device.ApkInstallation

fun createApkInstallTools(manager: ApkInstallManager): List<Tool> = buildApkInstallTools(manager::install, manager::snapshot)

internal fun buildApkInstallTools(
    install: suspend (Long) -> ApkInstallation,
    status: suspend () -> Map<Long, ApkInstallation>,
): List<Tool> = listOf(
    Tool(
        name = "download_install",
        description = """
            Silently install a downloaded single-file APK on THIS Android device using its download task_id.
            Call this after download_start when the user asks to download AND install an app; downloading alone never authorizes installation.
            Can wait up to 15 minutes for that download to finish, then automatically continue to installation without opening the Android installer UI.
            Uses the user's selected Shizuku (ADB shell) or Magisk/Root transport; never silently changes transport.
            The app handles device-session authorization/confirmation and displays the parsed APK package, version and hash.
            With a session authorized for Shizuku, installation proceeds without repeated app confirmation.
            Does not uninstall conflicting apps, downgrade packages, grant runtime permissions, bypass device restrictions or open the installed app.
            Read installed=true/status=SUCCEEDED before reporting success. Failure output explains the actual system error.
            Split APK sets (APKM/XAPK/APKS) are not supported. Installing this app's own update may close it; the receipt is checked on next launch.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(properties = buildJsonObject { put("task_id", idSchema()) }, required = listOf("task_id"))
        },
        // Exact package confirmation and session grants are enforced locally, just like device_command.
        execute = { input -> listOf(UIMessagePart.Text(install(input.jsonObject.installTaskId()).toInstallJson().toString())) },
    ),
    Tool(
        name = "download_install_status",
        description = "Read recorded APK installation progress/result for a download task_id, or all recent receipts. Download READY does not mean installed. UNKNOWN is an interrupted operation with an unconfirmed result; never report it as installed.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { put("task_id", idSchema()) }) },
        execute = { input ->
            val args = input.jsonObject
            val records = status()
            val selected = if (args.containsKey("task_id")) {
                listOfNotNull(records[args.installTaskId()])
            } else records.values.toList()
            listOf(UIMessagePart.Text(buildJsonObject {
                put("installations", buildJsonArray { selected.forEach { add(it.toInstallJson()) } })
                if (selected.isEmpty()) put("message", "暂无此任务的安装记录；下载完成不代表已安装")
            }.toString()))
        },
    ),
)

private fun idSchema() = buildJsonObject { put("type", "integer"); put("minimum", 1); put("description", "The APK download task ID") }
private fun JsonObject.installTaskId(): Long = (this["task_id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
    ?: error("task_id 必须是有效的下载任务编号")

private fun ApkInstallation.toInstallJson() = buildJsonObject {
    put("task_id", taskId)
    put("status", status.name)
    put("installed", status == ApkInstallStatus.SUCCEEDED)
    put("detail", detail)
    packageName?.let { put("package_name", it) }
    versionCode?.let { put("version_code", it) }
}
