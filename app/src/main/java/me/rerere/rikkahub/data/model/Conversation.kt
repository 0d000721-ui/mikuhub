package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .localFileUrls()
            .map { it.toUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        var newNodes: MutableList<MessageNode>? = null
        messages.forEachIndexed { index, message ->
            val node = messageNodes.getOrNull(index)
            if (node == null) {
                val target = newNodes ?: messageNodes.toMutableList().also { newNodes = it }
                target.add(message.toMessageNode())
                return@forEachIndexed
            }
            val messageIndex = node.messages.indexOfFirst { it.id == message.id }
            // Preserve history node identities so Compose can skip unchanged rows during streaming.
            if (messageIndex >= 0 && node.messages[messageIndex] == message) return@forEachIndexed
            val newMessages = node.messages.toMutableList()
            if (messageIndex >= 0) {
                newMessages[messageIndex] = message
            } else {
                newMessages.add(message)
            }
            val target = newNodes ?: messageNodes.toMutableList().also { newNodes = it }
            target[index] = node.copy(
                messages = newMessages,
                selectIndex = if (messageIndex >= 0) node.selectIndex else newMessages.lastIndex,
            )
        }
        return newNodes?.let { copy(messageNodes = it) } ?: this
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

/** Most stream updates only change text. Scan the full retained history only for actual removal candidates. */
internal fun Conversation.removedLocalFileUrls(
    previous: Conversation,
    additionallyRetained: () -> Set<String> = { emptySet() },
): Set<String> {
    if (messageNodes === previous.messageNodes) return emptySet()
    val candidates = mutableSetOf<String>()
    previous.messageNodes.forEachIndexed { index, oldNode ->
        val newNode = messageNodes.getOrNull(index)
        if (oldNode === newNode) return@forEachIndexed
        val oldFiles = oldNode.messages.flatMap { it.parts }.localFileUrls()
        if (oldFiles.isEmpty()) return@forEachIndexed
        val replacementFiles = newNode?.messages?.flatMap { it.parts }?.localFileUrls().orEmpty()
        candidates.addAll(oldFiles - replacementFiles)
    }
    if (candidates.isEmpty()) return emptySet()
    candidates.removeAll(messageNodes.flatMap { it.messages }.flatMap { it.parts }.localFileUrls())
    if (candidates.isNotEmpty()) candidates.removeAll(additionallyRetained())
    return candidates
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/** 本地附件引用，包含工具结果中的嵌套附件。 */
internal fun List<UIMessagePart>.localFileUrls(): Set<String> = buildSet {
    this@localFileUrls.forEach { part ->
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Document -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Tool -> {
                addAll(part.output.localFileUrls())
                null
            }

            else -> null
        }
        if (url?.startsWith("file://") == true) add(url)
    }
}
