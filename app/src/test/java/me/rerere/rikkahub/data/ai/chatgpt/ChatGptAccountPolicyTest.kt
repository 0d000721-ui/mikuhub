package me.rerere.rikkahub.data.ai.chatgpt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ChatGptAccountPolicyTest {
    @Test fun aPendingSignInCannotRestoreAnAccountSignedOutAfterItStarted() {
        assertTrue(runCatching { requireChatGptSessionEpoch(expected = 0, current = 1) }.isFailure)
        requireChatGptSessionEpoch(expected = 1, current = 1)
    }

    @Test fun registrationHostIdUsesTheRequiredUrnUuidFormat() {
        val first = newChatGptHostId()
        val second = newChatGptHostId()
        assertTrue(first.startsWith("urn:uuid:"))
        assertTrue(UUID.fromString(first.removePrefix("urn:uuid:")).toString() == first.removePrefix("urn:uuid:"))
        assertFalse(first == second)
    }

    @Test fun differentSubjectCannotReplacePreviouslyConnectedAccount() {
        assertTrue(runCatching {
            requireSameChatGptIdentity("client-one", "original-user", "client-one", "another-user")
        }.isFailure)
    }

    @Test fun differentRegistrationCannotReplaceSelectedAccount() {
        assertTrue(runCatching {
            requireSameChatGptIdentity("client-one", "original-user", "client-two", "original-user")
        }.isFailure)
    }

    @Test fun accountCanCompleteRegistrationBeforeFirstIdentityIsKnown() {
        requireSameChatGptIdentity("client-one", null, "client-one", "first-user")
    }

    @Test fun anEmptyIdentityCannotBeAcceptedAsConnected() {
        assertTrue(runCatching {
            requireSameChatGptIdentity("client-one", null, "client-one", "")
        }.isFailure)
    }

    @Test fun refreshStartsBeforeExpiryAndAtItsBoundary() {
        assertFalse(chatGptNeedsRefresh(expiresAt = 120_000, now = 59_999))
        assertTrue(chatGptNeedsRefresh(expiresAt = 120_000, now = 60_000))
        assertTrue(chatGptNeedsRefresh(expiresAt = 120_000, now = 120_001))
    }

    @Test fun missingExpiryNeverGrantsAnIndefiniteSession() {
        assertTrue(chatGptNeedsRefresh(expiresAt = 0, now = 1))
    }

    @Test fun planPermissionRequiresExactGrantedScope() {
        assertFalse(chatGptPlanEnabled(listOf("openid", "resource.invoke")))
        assertFalse(chatGptPlanEnabled(listOf("chatgpt.tokens.use.direct.fake")))
        assertTrue(chatGptPlanEnabled(listOf("openid", "chatgpt.tokens.use.direct")))
    }
}
