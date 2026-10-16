package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.HttpResume.Plan
import com.cortinadev.dogmatix.util.HttpResume.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpResumeTest {

    private val rom = "Tetris (World).gb"
    private fun partial(contentRange: String?, length: Long = -1, type: String? = "application/octet-stream") =
        Response(206, contentRange, length, type)

    @Test
    fun `content range shapes`() {
        assertEquals(HttpResume.ContentRange(100, 199, 1000), HttpResume.parseContentRange("bytes 100-199/1000"))
        assertEquals(HttpResume.ContentRange(100, 199, null), HttpResume.parseContentRange("bytes 100-199/*"))
        assertEquals(HttpResume.ContentRange(null, null, 1000), HttpResume.parseContentRange("bytes */1000"))
        assertNull(HttpResume.parseContentRange("bytes 200-100/1000"))
        assertNull(HttpResume.parseContentRange("bytes 0-1000/1000"))
        assertNull(HttpResume.parseContentRange("bytes */*"))
        assertNull(HttpResume.parseContentRange("items 0-1/2"))
        assertNull(HttpResume.parseContentRange(null))
    }

    @Test
    fun `validator is a strong ETag, else Last-Modified`() {
        assertEquals("\"abc\"", HttpResume.validator("\"abc\"", "Tue, 14 Oct 2026 10:00:00 GMT"))
        assertEquals("Tue, 14 Oct 2026 10:00:00 GMT", HttpResume.validator("W/\"abc\"", "Tue, 14 Oct 2026 10:00:00 GMT"))
        assertNull(HttpResume.validator(" ", null))
    }

    @Test
    fun `a 206 that continues the partial and has the same total is appended`() {
        assertEquals(Plan.Append(400, 1000), HttpResume.plan(400, 1000, partial("bytes 400-999/1000", 600), rom))
        // The total may be unknown in the header when we know it from the first response.
        assertEquals(Plan.Append(400, 1000), HttpResume.plan(400, 1000, partial("bytes 400-999/*"), rom))
        assertEquals(Plan.Append(400, 1000), HttpResume.plan(400, null, partial("bytes 400-999/1000"), rom))
    }

    @Test
    fun `a 200 means the range was ignored or the file changed - start from zero`() {
        assertEquals(Plan.Fresh(1200), HttpResume.plan(400, 1000, Response(200, null, 1200, "application/zip"), rom))
        assertEquals(Plan.Fresh(null), HttpResume.plan(400, 1000, Response(200, null, -1, null), rom))
        // A 206 that covers the whole file is as good as a 200.
        assertEquals(Plan.Fresh(1000), HttpResume.plan(400, 1000, partial("bytes 0-999/1000"), rom))
    }

    @Test
    fun `a 206 elsewhere or for another total is never appended`() {
        assertEquals(Plan.Refetch, HttpResume.plan(400, 1000, partial("bytes 300-999/1000"), rom))
        assertEquals(Plan.Refetch, HttpResume.plan(400, 1000, partial("bytes 400-1199/1200"), rom))
        // Capped ranges would leave a short file.
        assertEquals(Plan.Refetch, HttpResume.plan(400, 1000, partial("bytes 400-499/1000"), rom))
        // Body length that does not add up.
        assertEquals(Plan.Refetch, HttpResume.plan(400, 1000, partial("bytes 400-999/1000", 10), rom))
        assertEquals(Plan.Refetch, HttpResume.plan(400, 1000, partial(null), rom))
        // Without a partial (the refetch), a stray 206 is not the file.
        assertTrue(HttpResume.plan(0, null, partial("bytes 400-999/1000"), rom) is Plan.Reject)
    }

    @Test
    fun `416 is complete only when the partial is exactly the file`() {
        assertEquals(Plan.Complete, HttpResume.plan(1000, 1000, Response(416, "bytes */1000", 0, null), rom))
        assertEquals(Plan.Complete, HttpResume.plan(1000, null, Response(416, "bytes */1000", 0, null), rom))
        assertEquals(Plan.Refetch, HttpResume.plan(1200, 1000, Response(416, "bytes */1000", 0, null), rom))
        assertEquals(Plan.Refetch, HttpResume.plan(1000, 1200, Response(416, "bytes */1000", 0, null), rom))
        assertEquals(Plan.Refetch, HttpResume.plan(1000, 1000, Response(416, null, 0, null), rom))
    }

    @Test
    fun `a web page where a ROM was expected fails without touching the partial`() {
        assertTrue(HttpResume.plan(400, 1000, Response(200, null, 5000, "text/html; charset=utf-8"), rom) is Plan.Reject)
        assertTrue(HttpResume.plan(400, 1000, partial("bytes 400-999/1000", 600, "application/json"), rom) is Plan.Reject)
        assertTrue(HttpResume.plan(0, null, Response(200, null, 0, "application/octet-stream"), rom) is Plan.Fresh)
        assertTrue(HttpResume.plan(400, 1000, Response(200, null, 0, "application/octet-stream"), rom) is Plan.Reject)
        // Plain text is what servers send for unknown extensions, and a page is fine when it is the file.
        assertTrue(HttpResume.plan(0, null, Response(200, null, 300, "text/plain"), "Game (USA).cue") is Plan.Fresh)
        assertFalse(HttpResume.isWebPage("text/html", "manual.html"))
        assertTrue(HttpResume.isWebPage("TEXT/HTML", rom))
    }

    @Test
    fun `wrong content is a failure of its own, not to be retried on its own`() {
        val failure = DownloadFailures.classify(UnexpectedContentException("Unexpected response for x: HTTP 200 web page"))
        assertEquals(DownloadFailure.WRONG_CONTENT, failure)
        assertFalse(failure.transient)
    }
}
