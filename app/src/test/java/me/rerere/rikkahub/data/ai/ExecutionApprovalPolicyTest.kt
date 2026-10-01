package me.rerere.rikkahub.data.ai

import org.junit.Assert.*
import org.junit.Test

class ExecutionApprovalPolicyTest {
    @Test fun missingOrUnknownPersistedModeDoesNotGrantUnrestrictedAccess() {
        listOf(null, "", "true", "SESSION", "UNRESTRICTED", "new-future-mode").forEach {
            assertEquals(ExecutionApprovalMode.IMPORTANT_ONLY, ExecutionApprovalMode.fromStoredValue(it))
        }
        assertEquals(ExecutionApprovalMode.UNRESTRICTED, ExecutionApprovalMode.fromStoredValue("unrestricted"))
    }

    @Test fun routineQueriesNavigationAndDownloadsDoNotInterruptTheUser() {
        listOf("search_web", "scrape_web", "browser_read", "browser_navigate", "browser_fill", "browser_scroll",
            "browser_download", "download_start", "download_cancel", "download_status", "device_status").forEach {
            assertFalse(it, ExecutionApprovalPolicy.requiresToolApproval(ExecutionApprovalMode.IMPORTANT_ONLY, it, true))
        }
    }

    @Test fun submissionsShellAndSensitiveOperationsNeedManualApprovalInDefaultMode() {
        listOf("browser_click", "workspace_shell", "clipboard_tool", "calendar_create", "memory_tool",
            "mcp__service__send_message", "mcp__service__browser_read", "new_unknown_tool").forEach {
            assertTrue(it, ExecutionApprovalPolicy.requiresToolApproval(ExecutionApprovalMode.IMPORTANT_ONLY, it, false))
        }
    }

    @Test fun workspaceFileToolsRespectTheirPathAndExplicitApprovalRules() {
        listOf("workspace_read_file", "workspace_write_file", "workspace_edit_file").forEach {
            assertFalse(ExecutionApprovalPolicy.requiresToolApproval(ExecutionApprovalMode.IMPORTANT_ONLY, it, false))
            assertTrue(ExecutionApprovalPolicy.requiresToolApproval(ExecutionApprovalMode.IMPORTANT_ONLY, it, true))
        }
    }

    @Test fun deviceAndInstallToolsUseTheirExactNativeCommandApprovalInsteadOfTwoPrompts() {
        listOf("device_command", "download_install").forEach {
            assertFalse(ExecutionApprovalPolicy.requiresToolApproval(ExecutionApprovalMode.IMPORTANT_ONLY, it, true))
        }
    }

    @Test fun unrestrictedAutomaticallyApprovesExecutionIncludingUnknownAndImportantTools() {
        listOf("browser_click", "workspace_shell", "mcp__service__write", "clipboard_tool", "new_tool").forEach {
            assertFalse(it, ExecutionApprovalPolicy.requiresToolApproval(ExecutionApprovalMode.UNRESTRICTED, it, true))
        }
    }

    @Test fun questionsAreAlwaysAnsweredByTheUserEvenInUnrestrictedMode() {
        ExecutionApprovalMode.entries.forEach {
            assertTrue(ExecutionApprovalPolicy.requiresToolApproval(it, "ask_user", false))
        }
    }
}
