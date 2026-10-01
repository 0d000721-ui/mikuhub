package me.rerere.rikkahub.data.ai.chatgpt

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException

internal class ChatGptRequestSessions {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val monitor = Any()
    private val epochs = mutableMapOf<String, Long>()

    fun current(accountId: String): Job = synchronized(monitor) {
        jobs.computeIfAbsent(accountId) { SupervisorJob() }
    }

    /** A canceled placeholder prevents a concurrent resolver from reopening this account. */
    fun revoke(accountId: String) = synchronized(monitor) {
        epochs[accountId] = (epochs[accountId] ?: 0) + 1
        jobs.computeIfAbsent(accountId) { SupervisorJob() }
            .cancel(CancellationException("ChatGPT 账号已退出"))
    }

    fun snapshotEpochs(): Map<String, Long> = synchronized(monitor) { epochs.toMap() }

    fun requireEpoch(accountId: String, expectedEpoch: Long) = synchronized(monitor) {
        requireChatGptSessionEpoch(expectedEpoch, epochs[accountId] ?: 0)
    }

    /** Called only after the credential owner verifies and commits an authorization. */
    fun authorized(accountId: String, expectedEpoch: Long) = synchronized(monitor) {
        requireEpoch(accountId, expectedEpoch)
        jobs.compute(accountId) { _, current ->
            if (current == null || current.isCancelled) SupervisorJob() else current
        }
        Unit
    }
}
