package me.rerere.rikkahub.ui.pages.setting.components

import org.junit.Assert.*
import org.junit.Test

class ChatGptCatalogRefreshPolicyTest {
    private var now = 1_000L
    private fun policy() = ChatGptCatalogRefreshPolicy(clock = { now }, ttlMillis = 900_000L, retryMillis = 30_000L)

    @Test fun initialStartupAndNewAccountRefreshImmediately() {
        val policy = policy()
        assertTrue(policy.shouldRefresh("provider:account-a"))
        policy.succeeded("provider:account-a")
        assertFalse(policy.shouldRefresh("provider:account-a"))
        assertTrue(policy.shouldRefresh("provider:account-b"))
    }

    @Test fun successfulRefreshExpiresAtTtlAndManualRefreshBypassesIt() {
        val policy = policy()
        policy.succeeded("a")
        now += 899_999L
        assertFalse(policy.shouldRefresh("a"))
        assertTrue(policy.shouldRefresh("a", force = true))
        now += 1L
        assertTrue(policy.shouldRefresh("a"))
    }

    @Test fun failedRefreshDoesNotMarkSuccessAndBackoffOnlyBlocksAutomaticRetry() {
        val policy = policy()
        policy.failed("a")
        assertFalse(policy.shouldRefresh("a"))
        assertTrue(policy.shouldRefresh("a", force = true))
        now += 30_000L
        assertTrue(policy.shouldRefresh("a"))
        policy.succeeded("a")
        assertFalse(policy.shouldRefresh("a"))
    }

    @Test fun staleRefreshCannotApplyAfterAccountSwitchOrNewRequestOrSignOut() {
        assertTrue(catalogResponseIsCurrent("a", 1, "a", 1, connected = true))
        assertFalse(catalogResponseIsCurrent("a", 1, "b", 1, connected = true))
        assertFalse(catalogResponseIsCurrent("a", 1, "a", 2, connected = true))
        assertFalse(catalogResponseIsCurrent("a", 1, "a", 1, connected = false))
    }
}
