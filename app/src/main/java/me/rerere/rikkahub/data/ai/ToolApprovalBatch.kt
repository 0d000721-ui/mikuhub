package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState

/** Keep the whole unexecuted batch when resuming after a user's answer or approval. */
internal fun toolsAfterUserApproval(tools: List<UIMessagePart.Tool>): List<UIMessagePart.Tool> =
    tools.filter { !it.isExecuted && it.approvalState !is ToolApprovalState.Pending }
