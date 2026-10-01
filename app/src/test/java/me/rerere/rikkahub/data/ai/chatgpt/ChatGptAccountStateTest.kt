package me.rerere.rikkahub.data.ai.chatgpt

import me.rerere.oauth.ChatGptCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptAccountStateTest {
    private val credentials = ChatGptCredentials(
        clientId = "client-one", subject = "user-one", email = "one@example.test",
        accessToken = "access-secret-one", refreshToken = "refresh-secret-one", idToken = "id-secret-one",
        expiresAt = 120_000, scopes = listOf("openid", "chatgpt.tokens.use.direct"),
    )

    private fun connected(): ChatGptAccountState = ChatGptAccountState("urn:uuid:fixed-host")
        .registerClient(null, "client-one")
        .acceptCredentials("client-one", credentials)

    @Test fun registrationSurvivesFailedExchangeWithoutLosingHostIdentity() {
        val state = ChatGptAccountState("urn:uuid:fixed-host").registerClient(null, "client-one")
        assertEquals("urn:uuid:fixed-host", state.hostId)
        assertEquals("client-one", state.records["client-one"]?.clientId)
        assertNull(state.records["client-one"]?.credentials)
        assertFalse(state.records.getValue("client-one").summary().connected)
    }

    @Test fun registeringAgainPreservesActiveCredentialsUntilExchangeSucceeds() {
        val state = connected().registerClient("client-one", "client-one")
        assertEquals("access-secret-one", state.records["client-one"]?.credentials?.accessToken)
    }

    @Test fun signOutRetainsTheRegistrationAndOriginalIdentityForReconnect() {
        val state = connected().clearCredentials("client-one")
        val account = state.records.getValue("client-one")
        assertNull(account.credentials)
        assertEquals("client-one", account.clientId)
        assertEquals("user-one", account.subject)
        assertEquals("one@example.test", account.email)
        assertFalse(account.summary().connected)
        assertFalse(account.summary().planEnabled)
        assertEquals("urn:uuid:fixed-host", state.hostId)
    }

    @Test fun browserAccountSwitchCannotOverwriteASelectedAccountsSession() {
        val state = connected()
        assertTrue(runCatching {
            state.acceptCredentials("client-one", credentials.copy(subject = "different-user", accessToken = "other-secret"))
        }.isFailure)
        assertEquals("access-secret-one", state.records["client-one"]?.credentials?.accessToken)
        assertEquals("user-one", state.records["client-one"]?.subject)
    }

    @Test fun anotherAccountCannotReplaceASignedOutSelectedRegistration() {
        val state = connected().clearCredentials("client-one")
        assertTrue(runCatching { state.registerClient("client-one", "client-two") }.isFailure)
        assertTrue(runCatching {
            state.acceptCredentials("client-one", credentials.copy(subject = "different-user"))
        }.isFailure)
    }

    @Test fun refreshedTokensOnlyUpdateTheirOwnAccount() {
        val two = credentials.copy(clientId = "client-two", subject = "user-two", email = "two@example.test", accessToken = "access-secret-two")
        val state = connected().registerClient(null, "client-two").acceptCredentials("client-two", two)
            .acceptCredentials("client-one", credentials.copy(accessToken = "rotated-access", refreshToken = "rotated-refresh"))
        assertEquals("rotated-access", state.records["client-one"]?.credentials?.accessToken)
        assertEquals("rotated-refresh", state.records["client-one"]?.credentials?.refreshToken)
        assertEquals("access-secret-two", state.records["client-two"]?.credentials?.accessToken)
    }

    @Test fun identityOnlyLoginDoesNotPromisePlanAccess() {
        val state = connected().acceptCredentials("client-one", credentials.copy(scopes = listOf("openid", "email")))
        assertTrue(state.records.getValue("client-one").summary().connected)
        assertFalse(state.records.getValue("client-one").summary().planEnabled)
    }

    @Test fun printableStateAndSummariesDoNotExposeCredentials() {
        val state = connected()
        val record = state.records.getValue("client-one")
        listOf(state.toString(), record.toString(), record.summary().toString()).forEach { printable ->
            assertFalse(printable.contains("access-secret"))
            assertFalse(printable.contains("refresh-secret"))
            assertFalse(printable.contains("id-secret"))
        }
    }
}
