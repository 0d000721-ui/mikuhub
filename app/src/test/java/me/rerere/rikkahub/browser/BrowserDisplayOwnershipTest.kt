package me.rerere.rikkahub.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDisplayOwnershipTest {
    @Test fun oldPageDisposalCannotDetachTheNewHost() {
        val display = BrowserDisplayOwnership()
        val old = display.newOwner()
        val current = display.newOwner()
        display.claim(old)
        display.claim(current)
        assertFalse(display.release(old))
        assertTrue(display.owns(current))
        assertTrue(display.release(current))
        assertFalse(display.owns(current))
    }

    @Test fun closingTheSessionRevokesEveryOldDisplayLease() {
        val display = BrowserDisplayOwnership()
        val old = display.newOwner()
        display.claim(old)
        display.clear()
        assertFalse(display.release(old))
        val current = display.newOwner()
        display.claim(current)
        assertFalse(display.release(old))
        assertTrue(display.owns(current))
    }

    @Test fun loadFinishedWithoutVisibleDomAndAFrameIsNotRendered() {
        assertFalse(browserPageRendered(attached = true, drawn = false, visibleElements = 4))
        assertFalse(browserPageRendered(attached = true, drawn = true, visibleElements = 0))
        assertFalse(browserPageRendered(attached = false, drawn = true, visibleElements = 4))
        assertTrue(browserPageRendered(attached = true, drawn = true, visibleElements = 4))
    }

    @Test fun releasingAnEmptyPageHostKeepsTheExplicitAiPermitForBackgroundNavigation() {
        val display = BrowserDisplayOwnership()
        val permission = BrowserPermission()
        val owner = display.newOwner()
        display.claim(owner)
        permission.setEnabled(true)
        val permit = permission.permit()
        assertTrue(display.release(owner))
        permission.verify(permit)
        val next = display.newOwner()
        display.claim(next)
        assertFalse(display.release(owner))
        permission.verify(permit)
    }
}
