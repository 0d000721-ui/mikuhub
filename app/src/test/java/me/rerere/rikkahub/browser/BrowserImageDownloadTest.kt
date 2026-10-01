package me.rerere.rikkahub.browser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BrowserImageDownloadTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
    private val page = "https://chatgpt.com/c/example"

    @Test fun onlyTheCurrentOfficialPageMaySaveItsImages() {
        assertTrue(isChatGptImagePage(page))
        listOf("http://chatgpt.com/", "https://chatgpt.com.evil.test/", "https://evil.test/chatgpt.com", "https://user@chatgpt.com/", "https://chatgpt.com:444/", "blob:https://chatgpt.com/id").forEach {
            assertFalse(it, isChatGptImagePage(it))
        }
        assertTrue(isChatGptImageSource(page, "blob:https://chatgpt.com/image-id"))
        assertTrue(isChatGptImageSource(page, "https://chatgpt.com/image"))
        assertTrue(isChatGptImageSource(page, "https://files.oaiusercontent.com/image?signature=example"))
        listOf("blob:null/id", "blob:https://evil.test/id", "https://oaiusercontent.com.evil.test/image", "https://evil.test/image", "data:image/png;base64,AA==", "file:///image.png", "https://user@files.oaiusercontent.com/image", "http://files.oaiusercontent.com/image").forEach {
            assertFalse(it, isChatGptImageSource(page, it))
        }
        assertFalse(isChatGptImageSource("https://evil.test", "https://chatgpt.com/image"))
    }

    @Test fun aRecentExplicitActionIsConsumedOnceAndBoundToTheDocument() {
        val grant = BrowserImageDownloadGrant()
        assertFalse(grant.consume(100, 2))
        grant.allow(100, 2)
        assertFalse(grant.consume(101, 3))
        grant.allow(100, 2)
        assertTrue(grant.consume(101, 2))
        assertFalse(grant.consume(102, 2))
        grant.allow(100, 2)
        assertFalse(grant.consume(100 + IMAGE_DOWNLOAD_ACTION_TTL_MS + 1, 2))
        grant.allow(100, 2)
        grant.clear()
        assertFalse(grant.consume(101, 2))
    }

    @Test fun completedChunksProduceOnlyARealImageWithMatchingMime() {
        val download = BrowserImageChunks()
        download.append(0, png.size, "image/png", encoded(png.copyOfRange(0, 4)))
        download.append(4, png.size, "image/png", encoded(png.copyOfRange(4, png.size)))
        assertArrayEquals(png, download.finish())
        assertEquals("png", imageFileExtension("image/png"))
        assertEquals("jpg", imageFileExtension("image/jpeg"))
        assertEquals("webp", imageFileExtension("image/webp"))
    }

    @Test fun htmlOrAnotherFormatCannotMasqueradeAsAnImage() {
        val html = "<html>not an image</html>".toByteArray()
        assertTrue(runCatching { chunks(html, "image/png").finish() }.isFailure)
        assertTrue(runCatching { chunks(png, "image/jpeg").finish() }.isFailure)
        assertTrue(runCatching { chunks(png, "text/html") }.isFailure)
        val jpg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xdb.toByte())
        assertArrayEquals(jpg, chunks(jpg, "image/jpeg").finish())
        val webp = "RIFF1234WEBPVP8 ".toByteArray()
        assertArrayEquals(webp, chunks(webp, "image/webp").finish())
    }

    @Test fun outOfOrderChangedOrIncompleteChunksAreRejected() {
        assertTrue(runCatching { BrowserImageChunks().append(1, png.size, "image/png", encoded(png)) }.isFailure)
        val download = BrowserImageChunks()
        download.append(0, png.size, "image/png", encoded(png.copyOfRange(0, 4)))
        assertTrue(runCatching { download.finish() }.isFailure)
        assertTrue(runCatching { download.append(4, png.size + 1, "image/png", encoded(png)) }.isFailure)
        assertTrue(runCatching { download.append(4, png.size, "image/jpeg", encoded(png)) }.isFailure)
        assertTrue(runCatching { BrowserImageChunks().append(0, 2, "image/png", encoded(png)) }.isFailure)
        assertTrue(runCatching { BrowserImageChunks().append(0, MAX_BROWSER_IMAGE_BYTES + 1, "image/png", encoded(png)) }.isFailure)
        assertTrue(runCatching { BrowserImageChunks().append(0, 10, "image/png", "!not-base64!") }.isFailure)
        assertTrue(runCatching { BrowserImageChunks().append(0, MAX_BROWSER_IMAGE_BYTES, "image/png", encoded(ByteArray(BROWSER_IMAGE_CHUNK_BYTES + 1))) }.isFailure)
    }

    private fun encoded(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun chunks(bytes: ByteArray, mime: String) = BrowserImageChunks().also {
        it.append(0, bytes.size, mime, encoded(bytes))
    }
}
