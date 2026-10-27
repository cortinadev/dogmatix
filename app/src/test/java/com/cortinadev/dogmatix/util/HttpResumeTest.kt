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
    fun `the file changed on the server - ask again and start from zero with its new validator`() {
        // 206 that continues the partial but for another total: the remote file is another one now.
        assertEquals(Plan.Refetch, HttpResume.plan(400, 1000, partial("bytes 400-1199/1200", 800), rom))
        // The second request goes without Range: its 200 is written from zero (and recorded anew).
        assertEquals(Plan.Fresh(1200), HttpResume.plan(0, null, Response(200, null, 1200, "application/octet-stream"), rom))
        assertEquals("\"v2\"", HttpResume.validator("\"v2\"", null))
        // A 200 to If-Range is the server saying the file changed: from zero, recorded anew.
        assertEquals(Plan.Fresh(1200), HttpResume.plan(400, 1000, Response(200, null, 1200, "application/octet-stream"), rom))
        // A 206 to the request without Range is not the file: the attempt fails.
        assertTrue(HttpResume.plan(0, null, partial("bytes 400-1199/1200", 800), rom) is Plan.Reject)
        assertEquals(Plan.Refetch, HttpResume.plan(0, null, Response(416, "bytes */1200", 0, null), rom))
    }

    @Test
    fun `416 for another length is never complete`() {
        assertEquals(Plan.Refetch, HttpResume.plan(900, null, Response(416, "bytes */1000", 0, null), rom))
        assertEquals(Plan.Refetch, HttpResume.plan(900, 900, Response(416, "bytes */1000", 0, null), rom))
    }

    @Test
    fun `a partial longer than the file is never appended to`() {
        assertEquals(Plan.Refetch, HttpResume.plan(1500, 1000, Response(416, "bytes */1000", 0, null), rom))
        // A server that ignores the range sends the whole file: written from zero.
        assertEquals(Plan.Fresh(1000), HttpResume.plan(1500, 1000, partial("bytes 0-999/1000", 1000), rom))
        assertEquals(Plan.Fresh(1000), HttpResume.plan(1500, 1000, Response(200, null, 1000, null), rom))
    }

    @Test
    fun `without Content-Length the range header decides`() {
        assertEquals(Plan.Append(400, 1000), HttpResume.plan(400, 1000, partial("bytes 400-999/1000", -1), rom))
        assertEquals(Plan.Fresh(null), HttpResume.plan(0, null, Response(200, null, -1, "application/octet-stream"), rom))
    }

    @Test
    fun `a range without its total needs the recorded one`() {
        assertEquals(Plan.Append(400, 1000), HttpResume.plan(400, 1000, partial("bytes 400-999/*", 600), rom))
        // Nothing recorded and nothing in the header: where would it end?
        assertEquals(Plan.Refetch, HttpResume.plan(400, null, partial("bytes 400-999/*", 600), rom))
    }

    @Test
    fun `an HTML page with 206 is rejected too`() {
        assertTrue(HttpResume.plan(400, 1000, partial("bytes 400-999/1000", 600, "text/html"), rom) is Plan.Reject)
    }

    @Test
    fun `a body cut short is never complete`() {
        // The connection dropped: the partial and its record stay for the next attempt.
        assertTrue(HttpResume.isTruncated(received = 600, total = 1000))
        assertFalse(HttpResume.isTruncated(received = 1000, total = 1000))
        // Unknown length: nothing to compare with.
        assertFalse(HttpResume.isTruncated(received = 600, total = -1))
        assertFalse(HttpResume.isTruncated(received = 600, total = null))
        // An appended body counts from the bytes already on disk.
        assertTrue(HttpResume.isTruncated(received = 400 + 500, total = 1000))
    }

    @Test
    fun `archive org's ZIP viewer cannot be carried on`() {
        // What the viewer sent on 27-oct-2026 (HTTP/1.1, after its 302 to the data node): 200,
        // chunked (no length), no Accept-Ranges, Range ignored. The 302 said "Accept-Ranges: bytes",
        // but only the last answer counts.
        val viewer = Response(200, null, -1, "application/zip", acceptRanges = null)
        assertFalse(HttpResume.canResume(viewer))
        // Its partial is never used: the next attempt sends no Range and writes from zero.
        assertEquals(Plan.Fresh(null), HttpResume.plan(0, null, viewer, "Robotron 64 (USA).zip"))
    }

    @Test
    fun `what can be carried on`() {
        // A length alone keeps the behaviour of every other server (many honour Range without saying so).
        assertTrue(HttpResume.canResume(Response(200, null, 244718, "application/zip")))
        assertTrue(HttpResume.canResume(Response(206, "bytes 0-99/244718", 100, "application/zip")))
        // Streamed without a length but announcing ranges: the next attempt asks with Range.
        assertTrue(HttpResume.canResume(Response(200, null, -1, "application/zip", acceptRanges = "bytes")))
        assertTrue(HttpResume.canResume(Response(200, null, -1, null, acceptRanges = " Bytes ")))
        assertTrue(HttpResume.canResume(Response(206, "bytes 0-99/*", -1, null)))
        // The server says it does not, length or not.
        assertFalse(HttpResume.canResume(Response(200, null, 244718, "application/zip", acceptRanges = "none")))
        assertFalse(HttpResume.canResume(Response(200, null, -1, "application/zip", acceptRanges = "none")))
        // Unknown units say nothing either way.
        assertFalse(HttpResume.canResume(Response(200, null, -1, null, acceptRanges = "items")))
        assertTrue(HttpResume.canResume(Response(200, null, 10, null, acceptRanges = "items")))
    }

    @Test
    fun `wrong content is a failure of its own, not to be retried on its own`() {
        val failure = DownloadFailures.classify(UnexpectedContentException("Unexpected response for x: HTTP 200 web page"))
        assertEquals(DownloadFailure.WRONG_CONTENT, failure)
        assertFalse(failure.transient)
    }
}
