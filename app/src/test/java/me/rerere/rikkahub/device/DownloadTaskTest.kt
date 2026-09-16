package me.rerere.rikkahub.device

import android.app.DownloadManager
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DownloadTaskTest {
    private fun task(status: DownloadStatus = DownloadStatus.DOWNLOADING, downloaded: Long = 0, total: Long = -1) =
        DownloadTask(1, "https://example.com/app.apk", "app.apk", 100, status, downloaded, total)

    @Test fun unknownSizeRemainsIndeterminateAndReportsReceivedBytes() {
        val download = task(downloaded = 2048)
        assertNull(download.progressFraction)
        assertNull(download.percent)
        assertEquals("已下载 2.0 KB · 总大小未知", download.sizeLabel)
    }

    @Test fun knownSizeShowsRealFractionAndClampsInconsistentCounters() {
        assertEquals(25, task(downloaded = 256, total = 1024).percent)
        assertEquals(100, task(downloaded = Long.MAX_VALUE, total = 1024).percent)
        assertEquals(0, task(downloaded = -100, total = 1024).percent)
        assertNull(task(total = 0).percent)
    }

    @Test fun completedUnknownLengthTransferShowsComplete() {
        val download = task(DownloadStatus.READY, downloaded = 4096)
        assertEquals(100, download.percent)
        assertFalse(download.isActive)
    }

    @Test fun terminalStatesDoNotRemainActive() {
        listOf(DownloadStatus.CANCELLED, DownloadStatus.FAILED, DownloadStatus.READY, DownloadStatus.MISSING, DownloadStatus.UNAVAILABLE)
            .forEach { assertFalse(task(it).isActive) }
        listOf(DownloadStatus.QUEUED, DownloadStatus.WAITING, DownloadStatus.DOWNLOADING)
            .forEach { assertTrue(task(it).isActive) }
    }

    @Test fun systemStatusAndReasonAreReadableAndUnknownStatusIsHonest() {
        assertEquals(DownloadStatus.DOWNLOADING, downloadStatus(DownloadManager.STATUS_RUNNING))
        assertEquals(DownloadStatus.UNAVAILABLE, downloadStatus(9_999))
        assertEquals("连接 Wi-Fi 后会自动继续", downloadStatusDetail(DownloadManager.STATUS_PAUSED, DownloadManager.PAUSED_QUEUED_FOR_WIFI))
        assertEquals("存储空间不足，清理空间后重试", downloadStatusDetail(DownloadManager.STATUS_FAILED, DownloadManager.ERROR_INSUFFICIENT_SPACE))
        assertTrue(downloadStatusDetail(DownloadManager.STATUS_FAILED, 404)!!.contains("HTTP 404"))
    }

    @Test fun httpAndHttpsValidationRejectsMalformedOrCredentialUrls() {
        assertEquals("https://example.com/app.apk?key=a", validatedDownloadUrl(" https://example.com/app.apk?key=a "))
        assertEquals("http://example.com/file.zip", validatedDownloadUrl("http://example.com/file.zip"))
        listOf("https:///app.apk", "https://", "file:///app.apk", "https://user:pass@example.com/a.apk", "https://example.com/bad path", "javascript:alert(1)")
            .forEach { value ->
                assertTrue("Should reject $value", runCatching { validatedDownloadUrl(value) }.isFailure)
            }
    }

    @Test fun apkNameCannotEscapeDownloadDirectoryAndKeepsReadableUnicode() {
        assertEquals("初音.apk", safeApkFileName("初音.APK"))
        assertEquals("download.apk", safeApkFileName("..."))
        val name = safeApkFileName("../../folder\\app:one.apk")
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains(':'))
        assertFalse(name.startsWith('.'))
        assertTrue(name.endsWith(".apk"))
        assertEquals("app.apk", safeApkFileName("app.apk?token=hidden"))
    }

    @Test fun persistedRecordsRestoreMultipleIdsRetrySourcesAndCancellation() {
        val records = listOf(
            SavedDownload(10, "https://example.com/a.apk?token=x", "应用.apk", 100),
            SavedDownload(20, "https://example.com/b.apk", "b.apk", 200, cancelled = true),
        )
        val restored = Json.decodeFromString<List<SavedDownload>>(Json.encodeToString(records))
        assertEquals(records, restored)
        assertEquals(DownloadStatus.CANCELLED, restored[1].toTask().status)
        assertEquals("https://example.com/a.apk?token=x", restored[0].url)
    }

    @Test fun bytesFormattingSupportsLargeApksWithoutIntegerOverflow() {
        assertEquals("2.00 GB", formatDownloadBytes(2L * 1024 * 1024 * 1024))
        assertEquals("0 B", formatDownloadBytes(-1))
    }

    @Test fun oldSavedRecordsDefaultMissingFlagAndNewMissingRecordSurvivesRestart() {
        val oldRecord = """{"id":1,"url":"https://example.com/a.apk","fileName":"a.apk","createdAt":100}"""
        val restored = Json.decodeFromString<SavedDownload>(oldRecord)
        assertFalse(restored.cancelled)
        assertFalse(restored.missing)
        val missing = Json.decodeFromString<SavedDownload>(Json.encodeToString(restored.copy(missing = true)))
        assertEquals(DownloadStatus.MISSING, missing.toTask().status)
    }

    @Test fun genericNamesPreserveTheirRealTypeAndNeverAppendApk() {
        assertEquals("report.pdf", resolvedDownloadFileName("https://example.com/report.pdf?token=x", null, null, "application/pdf"))
        assertEquals("archive.zip", resolvedDownloadFileName("https://example.com/download", null, "attachment; filename=archive.zip", "application/zip"))
        assertEquals("download", resolvedDownloadFileName("https://example.com/", null, null, null))
        assertEquals("自定义.png", resolvedDownloadFileName("https://example.com/download", "自定义", null, "image/png"))
    }

    @Test fun contentDispositionSupportsUtf8AndDoesNotTreatPlusAsSpace() {
        assertEquals("初音+文件.zip", contentDispositionFileName("attachment; filename=old.zip; filename*=UTF-8''%E5%88%9D%E9%9F%B3+%E6%96%87%E4%BB%B6.zip"))
        assertEquals("space file.pdf", contentDispositionFileName("attachment; filename=\"space file.pdf\""))
        assertEquals("hello+world.txt", resolvedDownloadFileName("https://example.com/hello+world.txt", null, null, "text/plain"))
        assertEquals("初音.apk", resolvedDownloadFileName("https://example.com/%E5%88%9D%E9%9F%B3.apk", null, null, APK_MIME_TYPE))
    }

    @Test fun contentDispositionTraversalAndVeryLongUnicodeNamesStayWithinFilenameLimits() {
        val name = resolvedDownloadFileName("https://example.com/get", null, "attachment; filename=\"../../secret\\file.zip\"", "application/zip")
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.startsWith('.'))
        val long = safeDownloadFileName("😀初音".repeat(100) + ".zip")
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= 180)
        assertTrue(long.endsWith(".zip"))
        assertFalse(long.contains('\uFFFD'))
    }

    @Test fun htmlCannotMasqueradeAsApkEvenWhenCallerSuppliesAnApkName() {
        assertTrue(runCatching { resolvedDownloadFileName("https://example.com/download", "app.apk", null, "text/html; charset=UTF-8") }.isFailure)
        assertTrue(runCatching { resolvedDownloadFileName("https://example.com/app.apk", null, null, "application/xhtml+xml") }.isFailure)
        assertFalse(task().copy(mimeType = "text/html").isApk)
        assertTrue(task().copy(mimeType = "application/octet-stream").isApk)
        assertFalse(task().copy(fileName = "report.pdf", mimeType = "application/pdf").isApk)
    }

    @Test fun requestHeadersRejectInjection() {
        assertEquals("Mozilla/5.0", validatedDownloadHeader("Mozilla/5.0"))
        assertNull(validatedDownloadHeader(""))
        assertTrue(runCatching { validatedDownloadHeader("agent\r\nAuthorization: secret") }.isFailure)
        assertTrue(runCatching { validatedDownloadHeader("agent\u0000") }.isFailure)
    }

    @Test fun mimeAndRetryHeadersRoundTripWhileOldRecordsRemainCompatible() {
        val record = SavedDownload(1, "https://example.com/report", "report.pdf", 100, mimeType = "application/pdf", userAgent = "Browser", referer = "https://example.com/")
        assertEquals(record, Json.decodeFromString<SavedDownload>(Json.encodeToString(record)))
        assertEquals("application/pdf", record.toTask().mimeType)
        assertNull(Json.decodeFromString<SavedDownload>("""{"id":1,"url":"https://example.com/a.apk","fileName":"a.apk","createdAt":100}""").mimeType)
    }

    @Test fun completedHtmlWithBinaryMimeIsStillRejected() {
        val html = "\uFEFF \n<!-- CDN error -->\n<!DOCTYPE html><html><body>Sign in</body>".toByteArray()
        assertTrue(looksLikeHtmlDownload(html))
        assertNotNull(downloadedContentIssue(html, "app.apk", "application/octet-stream"))
        assertNotNull(downloadedContentIssue("<HTML><head>login".toByteArray(), "manual.pdf", null))
        assertNotNull(downloadedContentIssue("<body>rate limited".toByteArray(), "download", "text/plain"))
    }

    @Test fun completedApkNeedsArchivePrefixAndGenericFilesKeepTheirOwnFormat() {
        assertNotNull(downloadedContentIssue("Not Found".toByteArray(), "app.apk", "application/octet-stream"))
        assertNotNull(downloadedContentIssue(byteArrayOf(), "app.apk", APK_MIME_TYPE))
        assertNull(downloadedContentIssue(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00), "app.apk", APK_MIME_TYPE))
        assertNull(downloadedContentIssue("%PDF-1.7".toByteArray(), "manual.pdf", "application/pdf"))
        assertNull(downloadedContentIssue("Example text with <html> inside".toByteArray(), "notes.txt", "text/plain"))
    }
}
