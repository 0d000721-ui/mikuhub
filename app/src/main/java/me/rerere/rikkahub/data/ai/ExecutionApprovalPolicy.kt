package me.rerere.rikkahub.data.ai

enum class ExecutionApprovalMode(val storageValue: String) {
    IMPORTANT_ONLY("important_only"),
    UNRESTRICTED("unrestricted");

    companion object {
        fun fromStoredValue(value: String?): ExecutionApprovalMode =
            entries.find { it.storageValue == value } ?: IMPORTANT_ONLY
    }
}

/** A single atomic read pairs the choice with its change counter, including A -> B -> A switches. */
data class ExecutionApprovalSnapshot(val mode: ExecutionApprovalMode, val revision: Long)

/** Local policy only: model arguments never change the user's execution mode. */
object ExecutionApprovalPolicy {
    private val routineTools = setOf(
        "search_web", "scrape_web", "recent_chats", "conversation_search", "use_skill",
        "browser_status", "browser_read", "browser_navigate", "browser_fill", "browser_scroll", "browser_download",
        "download_start", "download_status", "download_cancel", "download_install_status", "device_status",
        "get_time_info", "get_screen_time", "calendar_query", "chart_display", "eval_javascript", "text_to_speech",
    )

    fun requiresToolApproval(mode: ExecutionApprovalMode, toolName: String, declaredNeedsApproval: Boolean): Boolean = when {
        // An execution preference cannot supply an answer to a question.
        toolName == "ask_user" -> true
        mode == ExecutionApprovalMode.UNRESTRICTED -> false
        // These tools check the actual command/package, transport and current mode locally.
        toolName == "device_command" || toolName == "download_install" -> false
        toolName in routineTools -> false
        // Preserve explicit workspace rules and the outside-writable-roots check.
        toolName in setOf("workspace_read_file", "workspace_write_file", "workspace_edit_file") -> declaredNeedsApproval
        // Unknown tools, third-party MCP, form clicks, shell and sensitive state changes need a decision.
        else -> true
    }
}
