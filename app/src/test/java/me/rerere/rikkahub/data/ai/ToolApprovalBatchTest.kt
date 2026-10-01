package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ToolApprovalBatchTest {
    @Test fun automaticToolsAreNotLostWhenTheirSiblingRequiredApproval() {
        val read = tool("read", ToolApprovalState.Auto)
        val write = tool("write", ToolApprovalState.Approved)
        assertEquals(listOf(read, write), toolsAfterUserApproval(listOf(read, write)))
    }

    @Test fun deniedAndAnsweredToolsKeepTheirHumanDecision() {
        val denied = tool("delete", ToolApprovalState.Denied("Keep the file"))
        val answered = tool("ask_user", ToolApprovalState.Answered("Use option B"))
        assertEquals(listOf(denied, answered), toolsAfterUserApproval(listOf(denied, answered)))
    }

    @Test fun pendingAndAlreadyExecutedToolsAreNeverRunAgain() {
        val pending = tool("send", ToolApprovalState.Pending)
        val done = tool("done", ToolApprovalState.Auto).copy(output = listOf(UIMessagePart.Text("done")))
        assertTrue(toolsAfterUserApproval(listOf(pending, done)).isEmpty())
    }

    private fun tool(name: String, state: ToolApprovalState) = UIMessagePart.Tool(
        toolCallId = name, toolName = name, input = "{}", approvalState = state,
    )
}
