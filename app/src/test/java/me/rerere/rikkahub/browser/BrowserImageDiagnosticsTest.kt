package me.rerere.rikkahub.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserImageDiagnosticsTest {
    @Test
    fun websiteFailuresAreRestrictedToFixedCategories() {
        assertEquals("js_type", browserImageScriptFailure("type"))
        assertEquals("js_fetch", browserImageScriptFailure("fetch"))
        assertEquals("js_unknown", browserImageScriptFailure("https://example.test/image?secret=private"))
        assertEquals("js_unknown", browserImageScriptFailure(null))
    }

    @Test
    fun transportCannotCarryAnAddressOrCredential() {
        assertEquals("cached_blob", browserImageTransport("cached_blob"))
        assertEquals("retained_blob", browserImageTransport("retained_blob"))
        assertEquals("blob", browserImageTransport("blob"))
        assertEquals("unknown", browserImageTransport("Bearer private"))
    }

    @Test
    fun nativeFailuresNeverIncludeExceptionMessages() {
        assertEquals("security", browserImageNativeFailure(SecurityException("private")))
        assertEquals("invalid_data", browserImageNativeFailure(IllegalArgumentException("private")))
        assertEquals("native", browserImageNativeFailure(Exception("private")))
    }
}
