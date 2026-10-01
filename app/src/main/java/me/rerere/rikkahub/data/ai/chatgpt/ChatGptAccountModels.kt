package me.rerere.rikkahub.data.ai.chatgpt

import kotlinx.serialization.Serializable
import me.rerere.oauth.ChatGptCredentials

data class ChatGptAccountSummary(
    val id: String,
    val clientId: String,
    val email: String?,
    val connected: Boolean,
    val planEnabled: Boolean,
)

sealed interface ChatGptAuthStatus {
    data object Idle : ChatGptAuthStatus
    data class Authorizing(val accountId: String?) : ChatGptAuthStatus
    data class Error(val message: String) : ChatGptAuthStatus
}

@Serializable
internal data class ChatGptAccountRecord(
    val id: String,
    val clientId: String,
    val subject: String? = null,
    val email: String? = null,
    val credentials: ChatGptCredentials? = null,
) {
    fun summary() = ChatGptAccountSummary(
        id = id,
        clientId = clientId,
        email = email,
        connected = credentials?.accessToken?.isNotBlank() == true,
        planEnabled = credentials?.let { chatGptPlanEnabled(it.scopes) } == true,
    )

    override fun toString(): String = "ChatGptAccountRecord(id=$id, credentials=[REDACTED])"
}

@Serializable
internal data class ChatGptAccountState(
    val hostId: String,
    val records: Map<String, ChatGptAccountRecord> = emptyMap(),
) {
    fun registerClient(selectedAccountId: String?, clientId: String): ChatGptAccountState {
        require(clientId.isNotBlank()) { "ChatGPT 未返回客户端注册" }
        val selected = selectedAccountId?.let {
            requireNotNull(records[it]) { "所选 ChatGPT 账号不存在" }
        }
        require(selected == null || selected.clientId == clientId) {
            "返回的客户端注册与所选账号不一致；原账号已保留"
        }
        if (clientId in records) return this
        return copy(records = records + (clientId to ChatGptAccountRecord(clientId, clientId)))
    }

    fun acceptCredentials(accountId: String, credentials: ChatGptCredentials): ChatGptAccountState {
        val record = requireNotNull(records[accountId]) { "ChatGPT 客户端注册不存在" }
        requireSameChatGptIdentity(record.clientId, record.subject, credentials.clientId, credentials.subject)
        require(credentials.accessToken.isNotBlank()) { "ChatGPT 未返回访问凭据" }
        val updated = record.copy(
            subject = credentials.subject,
            email = credentials.email.ifBlank { record.email.orEmpty() }.ifBlank { null },
            credentials = credentials,
        )
        return copy(records = records + (accountId to updated))
    }

    fun clearCredentials(accountId: String): ChatGptAccountState {
        val record = records[accountId] ?: return this
        return copy(records = records + (accountId to record.copy(credentials = null)))
    }

    override fun toString(): String = "ChatGptAccountState(accounts=${records.size}, credentials=[REDACTED])"
}
