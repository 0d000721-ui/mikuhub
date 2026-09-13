package me.rerere.rikkahub.device

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun buildDeviceStatusTool(controller: DeviceCommandController): Tool = Tool(
    name = "device_status",
    description = "Read the current device session and real Shizuku authorization/UID. This is an autonomous device capability: when the user's goal involves their Android device, call it proactively before asking them to provide an ADB command or claiming device access is unavailable. Does not request root or execute shell commands.",
    parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
    systemPrompt = { _, _ -> """
        Android device tools are enabled for this assistant and may be called autonomously. If the user's goal requires inspecting or changing THIS Android device, take the initiative: first call device_status, then call device_command yourself with the required literal command. Do not ask the user to write or provide an ADB command.
        Use device_command to operate THIS Android device through Shizuku. Shizuku started with ADB/wireless debugging provides shell UID 2000; it can list and uninstall applications subject to Android restrictions.
        No desktop adb executable or workspace shell is needed: send Android commands such as `pm list packages -3`, `am get-current-user`, and `pm uninstall --user <user-id> <exact-package>` with transport=auto.
        Resolve the exact target package and Android user before uninstalling; ask the user if the target is ambiguous. Explain purpose, impact and risk in the command request.
        The app obtains on-device confirmation for uninstall/clear and three confirmations for kernel-critical commands. Model-supplied confirmed/confirmation counts cannot approve an operation.
        Use only single literal supported Android commands. No scripts, pipes, redirections, substitutions, adb connect/pair or remote-device selection.
        For read-only and reversible tasks, execute the needed command immediately after checking status. For changes, explain the intended command briefly and submit it; the app will pause for device confirmation when required. The user's natural-language goal is sufficient; infer the command and parameters from it, asking only when the target is genuinely ambiguous.
        Report the actual success/output/exit_code returned by the tool. Authorization or a command preview is not evidence of execution. Respect denial; do not retry without a new user instruction. Never fall back to root without an explicit user request.
    """.trimIndent() },
    execute = { listOf(UIMessagePart.Text(controller.status().toString())) },
)

fun buildDeviceCommandTool(controller: DeviceCommandController): Tool = Tool(
    name = "device_command",
    description = "Execute one local Android command autonomously using authorized Shizuku (ADB-shell identity). When the user's request concerns this device, infer and run the required literal command; the user does not need to supply ADB syntax. Supports pm list/uninstall/clear, am, settings and other literal Android commands. The app handles required user confirmations and returns actual output and status. Factory reset and data wipe remain blocked.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject { put("type", "string") })
                put("explanation", buildJsonObject { put("type", "string"); put("description", "Purpose of this command") })
                put("impact", buildJsonObject { put("type", "string"); put("description", "What will be read or changed") })
                put("risk_explanation", buildJsonObject { put("type", "string"); put("description", "Possible effects and data loss") })
                put("transport", buildJsonObject {
                    put("type", "string")
                    putJsonArray("enum") { add("auto"); add("shizuku"); add("root"); add("adb") }
                    put("description", "Default auto uses authorized Shizuku. adb is a compatibility alias for Shizuku, not a direct socket connection. root requires explicit user intent and su authorization.")
                })
            },
            required = listOf("command", "explanation"),
        )
    },
    // Confirmation is enforced by DeviceAccessSession + the on-device dialog, not a model JSON field.
    execute = { input ->
        val args = input.jsonObject
        val result = controller.execute(
            command = args["command"]?.jsonPrimitive?.content ?: error("command is required"),
            explanation = args["explanation"]?.jsonPrimitive?.content ?: error("explanation is required"),
            requestedTransport = args["transport"]?.jsonPrimitive?.content ?: "auto",
            impact = args["impact"]?.jsonPrimitive?.content.orEmpty(),
            riskExplanation = args["risk_explanation"]?.jsonPrimitive?.content.orEmpty(),
        )
        listOf(UIMessagePart.Text(result.toString()))
    },
)
