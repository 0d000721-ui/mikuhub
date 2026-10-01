package me.rerere.rikkahub.browser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException

class BrowserImagePublicationTest {
    @Test fun onlyACompleteClosedWriteCanPublishAndCommittedFilesAreRetained() {
        val output = ByteArrayOutputStream()
        var published = 0
        var removed = 0
        val target = BrowserImagePublication({ output }, { published++; "saved" }, { removed++ })
        assertTrue(runCatching { target.commit {} }.isFailure)
        assertEquals(0, published)
        val bytes = ByteArray(70_000) { (it % 128).toByte() }
        target.write(bytes) {}
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals("saved", target.commit {})
        target.discard()
        assertEquals(1, published)
        assertEquals(0, removed)
    }

    @Test fun diskFailureRollsBackAndCannotReportSuccess() {
        var published = 0
        var removed = 0
        val target = BrowserImagePublication(
            { object : OutputStream() { override fun write(value: Int) { throw IOException("mock disk full") } } },
            { published++ }, { removed++ },
        )
        assertTrue(runCatching { target.write(ByteArray(100)) {} }.exceptionOrNull() is IOException)
        assertTrue(runCatching { target.commit {} }.isFailure)
        assertEquals(0, published)
        assertEquals(1, removed)
    }

    @Test fun cancellationDuringWriteRemovesThePartialFile() {
        var checked = 0
        var published = 0
        var removed = 0
        val target = BrowserImagePublication({ ByteArrayOutputStream() }, { published++ }, { removed++ })
        assertTrue(runCatching {
            target.write(ByteArray(70_000)) { if (++checked == 2) throw CancellationException() }
        }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { target.commit {} }.isFailure)
        assertEquals(0, published)
        assertEquals(1, removed)
    }

    @Test fun aRevokedDocumentAfterWritingCannotPublish() {
        var published = 0
        var removed = 0
        val target = BrowserImagePublication({ ByteArrayOutputStream() }, { published++ }, { removed++ })
        target.write(ByteArray(100)) {}
        assertTrue(runCatching { target.commit { throw CancellationException() } }.isFailure)
        target.discard()
        assertEquals(0, published)
        assertEquals(1, removed)
    }

    @Test fun publicationFailureIsRolledBack() {
        var removed = 0
        val target = BrowserImagePublication({ ByteArrayOutputStream() }, { throw IOException("mock media store failure") }, { removed++ })
        target.write(ByteArray(100)) {}
        assertTrue(runCatching { target.commit {} }.exceptionOrNull() is IOException)
        target.discard()
        assertEquals(1, removed)
    }
}
