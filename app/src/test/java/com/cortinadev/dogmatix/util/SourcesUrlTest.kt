package com.cortinadev.dogmatix.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SourcesUrlTest {

    @Test
    fun `a typed address is trimmed and gets https when it has no scheme`() {
        assertEquals("https://example.com/sources.json", SourcesUrl.normalize("  example.com/sources.json \n"))
        assertEquals("http://192.168.1.5:8000/s.json", SourcesUrl.normalize("http://192.168.1.5:8000/s.json"))
        assertEquals("https://192.168.1.5:8000/s.json", SourcesUrl.normalize("192.168.1.5:8000/s.json"))
        // host:port is not a scheme
        assertEquals("https://localhost:8080/s.json", SourcesUrl.normalize("localhost:8080/s.json"))
        assertEquals("HTTPS://Example.com/a.json", SourcesUrl.normalize("HTTPS://Example.com/a.json"))
    }

    @Test
    fun `only http and https addresses with a host are accepted`() {
        listOf(
            "", "   ", "ftp://example.com/a.json", "file:///sdcard/a.json", "content://x/y", "magnet:?xt=urn:btih:abc",
            "javascript:alert(1)", "mailto:me@example.com", "https://", "http:example.com", "https://exa mple.com/a.json",
            "https:///a.json"
        ).forEach { assertNull(it, SourcesUrl.normalize(it)) }
    }

    @Test
    fun `GitHub file pages become their raw file`() {
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/main/lists/dogmatix-sources.json",
            SourcesUrl.normalize("https://github.com/user/repo/blob/main/lists/dogmatix-sources.json")
        )
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/v1.2/s.json",
            SourcesUrl.normalize("github.com/user/repo/raw/v1.2/s.json?raw=true")
        )
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/main/My%20list.json",
            SourcesUrl.normalize("https://www.github.com/user/repo/blob/main/My%20list.json")
        )
        // Not a file page: left alone.
        assertEquals("https://github.com/user/repo", SourcesUrl.normalize("https://github.com/user/repo"))
        assertEquals("https://github.com/user/repo/tree/main/lists", SourcesUrl.normalize("https://github.com/user/repo/tree/main/lists"))
        // Already raw: left alone.
        val raw = "https://raw.githubusercontent.com/user/repo/main/s.json"
        assertEquals(raw, SourcesUrl.normalize(raw))
    }

    @Test
    fun `a gist becomes its raw file`() {
        assertEquals("https://gist.githubusercontent.com/user/abc123/raw", SourcesUrl.normalize("https://gist.github.com/user/abc123"))
        assertEquals("https://gist.githubusercontent.com/user/abc123/raw", SourcesUrl.normalize("gist.github.com/user/abc123/#file-s-json"))
        val fileRaw = "https://gist.github.com/user/abc123/raw/s.json"
        assertEquals(fileRaw, SourcesUrl.normalize(fileRaw))
    }

    @Test
    fun `redirects resolve against the current address and stay on the web`() {
        assertEquals("https://cdn.example.com/s.json", SourcesUrl.redirectTarget("http://example.com/a/s.json", "https://cdn.example.com/s.json"))
        assertEquals("http://example.com/b/t.json", SourcesUrl.redirectTarget("http://example.com/a/s.json", "/b/t.json"))
        assertEquals("http://example.com/a/t.json", SourcesUrl.redirectTarget("http://example.com/a/s.json", "t.json"))
        assertNull(SourcesUrl.redirectTarget("http://example.com/a", null))
        assertNull(SourcesUrl.redirectTarget("http://example.com/a", " "))
        assertNull(SourcesUrl.redirectTarget("http://example.com/a", "ftp://example.com/s.json"))
        assertTrue(SourcesUrl.isRedirect(302) && SourcesUrl.isRedirect(308))
        assertFalse(SourcesUrl.isRedirect(304) || SourcesUrl.isRedirect(200))
    }

    @Test
    fun `a body up to the cap is read whole`() {
        val bytes = ByteArray(40_000) { (it % 251).toByte() }
        assertArrayEquals(bytes, SourcesUrl.readCapped(ByteArrayInputStream(bytes), max = 40_000))
        assertArrayEquals(ByteArray(0), SourcesUrl.readCapped(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `a body past the cap stops the import`() {
        try {
            SourcesUrl.readCapped(ByteArrayInputStream(ByteArray(40_001)), max = 40_000)
            fail("read past the cap")
        } catch (e: SourcesUrlException) {
            assertEquals(SourcesUrlProblem.TOO_LARGE, e.problem)
        }
    }

    @Test
    fun `the text is UTF-8 without a byte order mark`() {
        val text = "{\"nintendo\":{\"_name\":\"Nintendo – ñ\"}}"
        assertEquals(text, SourcesUrl.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, SourcesUrl.decode(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `only a document with a console and a source is a sources document`() {
        val sources = """{"nintendo":{"_name":"Nintendo","snes":{"name":"SNES","urls":[{"url":"https://example.com/snes/"}]}}}"""
        assertTrue(SourcesUrl.isSourcesDocument(sources))
        assertTrue(SourcesUrl.isSourcesDocument("""{"sony":{"psx":{"urls":[]},"ps2":{"urls":[{"url":"magnet:?xt=urn:btih:abc"}]}}}"""))
        listOf(
            // Consoles but no source anywhere, and JSON of other kinds whose nested objects read as consoles.
            """{"sony":{"psx":{"name":"PlayStation","urls":[]}}}""",
            """{"packages":{"node_modules/a":{"version":"1.0.0","resolved":"https://registry.example/a.tgz"}}}""",
            """{"data":{"user":{"name":"x","urls":["https://example.com"]}}}""",
            "", "   ", "<!DOCTYPE html><html><body>Not found</body></html>", "[1, 2, 3]", "\"text\"", "{}",
            """{"login":"user","id":1,"plan":{"name":"free","space":976562499}}""",
            """{"nintendo":{"_name":"Nintendo"}}""", "{\"broken\": "
        ).forEach { assertFalse(it, SourcesUrl.isSourcesDocument(it)) }
    }

    @Test
    fun `a picked file needs a console, with or without sources yet`() {
        assertTrue(SourcesUrl.isImportableFile("""{"nintendo":{"_name":"Nintendo","snes":{"name":"SNES","urls":[{"url":"https://example.com/snes/"}]}}}"""))
        // An export can hold a console that has no sources yet.
        assertTrue(SourcesUrl.isImportableFile("""{"sony":{"psx":{"name":"PlayStation","urls":[]}}}"""))
        listOf(
            // The tester's case: a manufacturer and nothing in it.
            """{"x":{}}""", """{"nintendo":{"_name":"Nintendo"}}""",
            "", "   ", "{}", "[1, 2, 3]", "\"text\"", "<!DOCTYPE html><html><body>Not found</body></html>",
            // Broken JSON (Gson's MalformedJsonException) never escapes: it is just not importable.
            "{\"broken\": ", "{\"a\":{\"b\":{\"urls\":[}}}"
        ).forEach { assertFalse(it, SourcesUrl.isImportableFile(it)) }
    }

    @Test
    fun `a failed file import has a name only for our own problems`() {
        assertEquals(SourcesUrlProblem.NOT_SOURCES, SourcesUrl.fileProblemOf(SourcesUrlException(SourcesUrlProblem.NOT_SOURCES)))
        assertEquals(SourcesUrlProblem.UNREADABLE, SourcesUrl.fileProblemOf(SourcesUrlException(SourcesUrlProblem.UNREADABLE)))
        // No network with a file: an IOException is not "unreachable".
        assertEquals(SourcesUrlProblem.OTHER, SourcesUrl.fileProblemOf(java.io.IOException("disk")))
        assertEquals(SourcesUrlProblem.OTHER, SourcesUrl.fileProblemOf(IllegalArgumentException("No sources found in file")))
    }

    @Test
    fun `the history names the file and its server`() {
        assertEquals("dogmatix-sources.json (raw.githubusercontent.com)", SourcesUrl.label("https://raw.githubusercontent.com/u/r/main/dogmatix-sources.json"))
        assertEquals("gist.githubusercontent.com", SourcesUrl.label("https://gist.githubusercontent.com/u/abc/raw"))
        assertEquals("example.com", SourcesUrl.label("https://example.com/"))
    }

    @Test
    fun `network trouble reads as unreachable`() {
        assertEquals(SourcesUrlProblem.UNREACHABLE, SourcesUrl.problemOf(UnknownHostException("x")))
        assertEquals(SourcesUrlProblem.UNREACHABLE, SourcesUrl.problemOf(ConnectException("refused")))
        assertEquals(SourcesUrlProblem.UNREACHABLE, SourcesUrl.problemOf(SocketTimeoutException("timeout")))
        assertEquals(SourcesUrlProblem.HTTP, SourcesUrl.problemOf(SourcesUrlException(SourcesUrlProblem.HTTP, 404)))
        assertEquals(SourcesUrlProblem.OTHER, SourcesUrl.problemOf(IllegalStateException("x")))
    }
}
