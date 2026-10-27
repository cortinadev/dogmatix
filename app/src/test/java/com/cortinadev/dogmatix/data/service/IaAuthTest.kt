package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.util.IaCookiePair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IaAuthTest {

    // Made-up values: never a real session in a test.
    private val pair = IaCookiePair("player%40example.com", "1700000000%20abc")

    @Test
    fun `the user agent names the tool, its version and the project`() {
        assertEquals("Dogmatix/1.4 (+https://github.com/cortinadev/dogmatix)", IaAuth.userAgent("1.4"))
    }

    @Test
    fun `archive org gets the user agent and, over https with a session, the cookies`() {
        val signedIn = IaAuth.headersFor("1.4", "https://archive.org/download/x/a.zip", pair)
        assertEquals("Dogmatix/1.4 (+https://github.com/cortinadev/dogmatix)", signedIn["User-Agent"])
        assertEquals("logged-in-user=player%40example.com; logged-in-sig=1700000000%20abc", signedIn["Cookie"])
        assertEquals(setOf("User-Agent"), IaAuth.headersFor("1.4", "https://archive.org/metadata/x", null).keys)
        assertEquals(setOf("User-Agent"), IaAuth.headersFor("1.4", "http://archive.org/download/x/a.zip", pair).keys)
        assertEquals(2, IaAuth.headersFor("1.4", "https://ia601900.us.archive.org/view_archive.php?archive=/2/items/x/a.zip", pair).size)
    }

    @Test
    fun `nothing goes anywhere else`() {
        assertTrue(IaAuth.headersFor("1.4", "https://example.org/download/x/a.zip", pair).isEmpty())
        assertTrue(IaAuth.headersFor("1.4", "https://archive.org.evil.com/x", pair).isEmpty())
        assertTrue(IaAuth.headersFor("1.4", "ia://x", pair).isEmpty())
    }
}
