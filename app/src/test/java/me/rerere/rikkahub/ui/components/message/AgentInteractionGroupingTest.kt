package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class AgentInteractionGroupingTest {
    private fun tool(id: String, state: ToolApprovalState = ToolApprovalState.Pending, name: String = "workspace_shell") =
        UIMessagePart.Tool(id, name, "{}", approvalState = state)

    @Test fun everyPendingApprovalRemainsOutsideCollapsedTimelineInOriginalOrder() {
        val parts = listOf(UIMessagePart.Reasoning("thinking"), tool("1"), tool("2"), tool("3"), UIMessagePart.Text("after"))
        val blocks = parts.groupMessageParts()
        assertTrue(blocks.first() is MessagePartBlock.ThinkingBlock)
        assertEquals(listOf("1", "2", "3"), blocks.filterIsInstance<MessagePartBlock.InteractionBlock>().map { it.tool.toolCallId })
        assertTrue(blocks.last() is MessagePartBlock.ContentBlock)
    }

    @Test fun answeredAndSkippedQuestionsKeepTheirIndependentHistoryCards() {
        val parts = listOf(tool("1", ToolApprovalState.Answered("answer"), "ask_user"), tool("2", ToolApprovalState.Denied(), "ask_user"))
        assertEquals(2, parts.groupMessageParts().filterIsInstance<MessagePartBlock.InteractionBlock>().size)
    }

    @Test fun completedAndAutomaticToolsRemainInTheTimeline() {
        val parts = listOf(tool("1", ToolApprovalState.Auto), tool("2").copy(output = listOf(UIMessagePart.Text("done"))))
        val block = parts.groupMessageParts().single() as MessagePartBlock.ThinkingBlock
        assertEquals(2, block.steps.size)
    }
}
