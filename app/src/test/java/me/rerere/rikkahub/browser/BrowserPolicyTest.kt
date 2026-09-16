package me.rerere.rikkahub.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPolicyTest {
    @Test fun webAddressesRemainWebAddresses() {
        assertEquals("https://example.com/path?q=hello#part", browserUrl(" https://example.com/path?q=hello#part "))
        assertEquals("https://example.com/path", browserUrl("example.com/path", addHttps = true))
        assertEquals("http://localhost:8080/file", browserUrl("http://localhost:8080/file"))
    }

    @Test fun rejectLocalExecutableAndCredentialUrls() {
        listOf(
            "file:///sdcard/file", "content://downloads/123", "javascript:alert(1)", "intent://app",
            "data:text/html,hello", "blob:https://example.com/id", "https://user:pass@example.com/",
            "https:///missing-host", "https://example.com/\nheader", "https://example.com/bad path", "",
        ).forEach { value -> assertFalse("Must reject $value", isBrowserUrl(value)) }
        assertFalse(isBrowserUrl(null))
    }

    @Test fun schemeCannotBeSmuggledThroughAddressbarConvenience() {
        listOf("javascript:alert(1)", "intent:app", "file:/sdcard/file").forEach {
            assertTrue(runCatching { browserUrl(it, addHttps = true) }.isFailure)
        }
    }

    @Test fun arbitraryFieldTextIsOneJavascriptStringLiteral() {
        val value = "\" ); fetch('https://evil.test'); //\n\\\u2028\u2029中文"
        val literal = browserJsString(value)
        assertEquals(value, Json.parseToJsonElement(literal).jsonPrimitive.content)
        assertFalse(literal.contains('\n'))
        assertFalse(literal.contains('\u2028'))
        assertFalse(literal.contains('\u2029'))
    }

    @Test fun webViewDoubleEncodedJsonIsDecodedWithoutLosingQuotes() {
        val expected = """{"text":"say \"hello\"","elements":[]}"""
        val decoded = decodeBrowserJavascriptResult(JsonPrimitive(expected).toString())
        assertEquals(expected, decoded)
        assertEquals("say \"hello\"", Json.parseToJsonElement(decoded).jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(runCatching { decodeBrowserJavascriptResult("null") }.isFailure)
    }

    @Test fun permissionsAreOffUntilUserEnablesThem() {
        val permission = BrowserPermission()
        assertFalse(permission.enabled)
        assertTrue(runCatching { permission.permit() }.isFailure)
        permission.setEnabled(true)
        permission.verify(permission.permit())
    }

    @Test fun revokeInvalidatesActiveAndQueuedActionsEvenAfterReenable() {
        val permission = BrowserPermission()
        permission.setEnabled(true)
        val old = permission.permit()
        permission.setEnabled(false)
        assertTrue(runCatching { permission.verify(old) }.isFailure)
        permission.setEnabled(true)
        assertTrue(runCatching { permission.verify(old) }.isFailure)
        permission.verify(permission.permit())
    }

    @Test fun manualTakeoverInvalidatesActionsWithoutSilentlyDisablingTheSwitch() {
        val permission = BrowserPermission()
        permission.setEnabled(true)
        val old = permission.permit()
        permission.invalidate()
        assertTrue(permission.enabled)
        assertTrue(runCatching { permission.verify(old) }.isFailure)
        permission.verify(permission.permit())
    }

    @Test fun scrollDirectionCannotBecomeJavascript() {
        assertTrue(runCatching { BrowserScripts.scroll("down);alert(1)") }.isFailure)
        assertTrue(BrowserScripts.scroll("up").contains("* -1"))
        assertTrue(BrowserScripts.scroll("down").contains("* 1"))
    }
}
