package me.rerere.rikkahub.data.ai.chatgpt

import java.util.UUID

internal fun newChatGptHostId(): String = "urn:uuid:${UUID.randomUUID()}"

internal class ChatGptAccountException(message: String) : IllegalStateException(message)

internal fun requireChatGptSessionEpoch(expected: Long, current: Long) {
    if (expected != current) throw ChatGptAccountException("此账号已退出，本次登录已取消。请重新连接")
}

internal fun requireSameChatGptIdentity(
    expectedClientId: String,
    expectedSubject: String?,
    incomingClientId: String,
    incomingSubject: String,
) {
    if (expectedClientId.isBlank() || expectedClientId != incomingClientId) {
        throw ChatGptAccountException("返回的客户端注册与所选账号不一致，请重新添加账号")
    }
    if (incomingSubject.isBlank()) throw ChatGptAccountException("ChatGPT 未返回可验证的账号身份")
    if (expectedSubject != null && expectedSubject != incomingSubject) {
        throw ChatGptAccountException("登录的 ChatGPT 账号与所选账号不同；原账号已保留，请使用添加账号")
    }
}

internal fun chatGptNeedsRefresh(expiresAt: Long, now: Long): Boolean =
    expiresAt <= 0 || now >= expiresAt - 60_000L

internal fun chatGptPlanEnabled(scopes: List<String>): Boolean =
    "chatgpt.tokens.use.direct" in scopes
