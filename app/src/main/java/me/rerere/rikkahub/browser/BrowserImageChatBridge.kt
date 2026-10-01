package me.rerere.rikkahub.browser

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

enum class BrowserImageChatResult { QUEUED, DELIVERED, SKIPPED, FAILED }

data class BrowserImageChatReceipt(val result: BrowserImageChatResult, val message: String)

data class BrowserImageChatEvent(
    val target: BrowserImageChatTarget,
    val receipt: BrowserImageChatReceipt,
    val nodeId: Uuid? = null,
)

/** Local files only. The provider avoids a Controller -> ChatService -> tools -> Controller DI cycle. */
class BrowserImageChatBridge(
    private val filesManager: FilesManager,
    private val appScope: AppScope,
    private val chatService: () -> ChatService,
) {
    private val routing = BrowserImageChatRouting()
    private val pending = mutableSetOf<Job>()
    private var source: Source? = null
    private val eventChannel = Channel<BrowserImageChatEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private data class Source(val conversationId: Uuid, val canCreateDraft: Deferred<Boolean>)

    /** Call only from an explicit chat browser entry, with that entry's fixed conversation ID. */
    fun openFromConversation(conversationId: Uuid?) {
        invalidate()
        if (conversationId != null) bindSource(conversationId)
    }

    /** Tool closures carry their generation run's conversation, not a global selected-chat setting. */
    suspend fun bindForTool(conversationId: Uuid) = withContext(Dispatchers.Main.immediate) {
        bindSource(conversationId)
    }

    private fun bindSource(conversationId: Uuid) {
        if (source?.conversationId == conversationId) return
        invalidate()
        val service = chatService()
        service.addConversationReference(conversationId)
        routing.bind(conversationId)
        source = Source(conversationId, appScope.async {
            service.prepareBrowserImageConversation(conversationId)
        })
    }

    fun observeNativeConversation(conversationId: Uuid) {
        if (source?.conversationId?.let { it != conversationId } == true) invalidate()
    }

    fun captureTarget(): BrowserImageChatTarget? = routing.capture()
    fun isCurrent(target: BrowserImageChatTarget): Boolean = routing.isCurrent(target)

    /** Changing the webpage cancels queued transfers, while the next image may still use this chat. */
    fun cancelPending() {
        routing.cancelPending()
        pending.toList().forEach { it.cancel() }
        pending.clear()
    }

    fun invalidate() {
        cancelPending()
        routing.clear()
        source?.let {
            it.canCreateDraft.cancel()
            chatService().removeConversationReference(it.conversationId)
        }
        source = null
    }

    suspend fun deliver(target: BrowserImageChatTarget?, savedUri: Uri): BrowserImageChatReceipt {
        if (target == null) return BrowserImageChatReceipt(BrowserImageChatResult.SKIPPED, "图片已保存到手机。")
        val origin = source
        if (!routing.isCurrent(target) || origin?.conversationId != target.conversationId) {
            return BrowserImageChatReceipt(BrowserImageChatResult.SKIPPED, "来源聊天已改变，图片保留在手机中。")
        }
        if (savedUri.scheme !in setOf("content", "file")) {
            return BrowserImageChatReceipt(BrowserImageChatResult.FAILED, "图片已保存，但未能回传聊天。")
        }
        currentCoroutineContext().ensureActive()
        // Never wait for the caller's own generation here: browser_download can run inside that job.
        val task = appScope.launch(start = CoroutineStart.LAZY) {
            var localFiles = emptyList<Uri>()
            var attached = false
            try {
                fun verifyOrigin() { check(routing.isCurrent(target)) { "图片来源已改变" } }
                verifyOrigin()
                val service = chatService()
                val canCreate = origin.canCreateDraft.await()
                while (true) {
                    verifyOrigin()
                    val generation = service.getGenerationJobStateFlow(target.conversationId).first() ?: break
                    generation.join()
                }
                verifyOrigin()
                withContext(Dispatchers.IO) {
                    localFiles = listOf(filesManager.copySavedBrowserImageToChat(savedUri))
                }
                check(localFiles.size == 1) { "无法复制聊天图片" }
                currentCoroutineContext().ensureActive()
                verifyOrigin()
                val nodeId = service.appendBrowserImage(
                    conversationId = target.conversationId,
                    imageUrl = localFiles.single().toString(),
                    canCreateDraft = canCreate,
                    verifyOrigin = ::verifyOrigin,
                    onAttached = { attached = it },
                )
                if (routing.isCurrent(target)) eventChannel.send(BrowserImageChatEvent(
                    target, BrowserImageChatReceipt(BrowserImageChatResult.DELIVERED, "图片已保存并回传原聊天。"), nodeId,
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (routing.isCurrent(target)) eventChannel.send(BrowserImageChatEvent(
                    target, BrowserImageChatReceipt(BrowserImageChatResult.FAILED, "图片已保存到手机，但回传聊天失败，请从相册添加。"),
                ))
            } finally {
                if (!attached && localFiles.isNotEmpty()) withContext(NonCancellable + Dispatchers.IO) {
                    filesManager.deleteChatFiles(localFiles)
                }
            }
        }
        pending += task
        task.invokeOnCompletion { appScope.launch { pending -= task } }
        task.start()
        return BrowserImageChatReceipt(BrowserImageChatResult.QUEUED, "图片正在回传原聊天；若回复尚未结束，将在结束后显示。")
    }
}
