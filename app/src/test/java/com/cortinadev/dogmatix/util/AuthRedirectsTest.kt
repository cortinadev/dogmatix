package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRedirectsTest {

    private val download = "https://archive.org/download/item/Game.zip"

    @Test
    fun `the session follows a redirect to a data node`() {
        assertTrue(AuthRedirects.keepAuth(download, "https://ia601900.us.archive.org/2/items/item/Game.zip"))
        assertTrue(AuthRedirects.keepAuth(download, "https://dn721601.ca.archive.org/0/items/item/Game.zip"))
        assertTrue(AuthRedirects.keepAuth("https://ia601900.us.archive.org/x", "https://archive.org/download/item/Game.zip"))
    }

    @Test
    fun `and never leaves archive org or https`() {
        assertFalse(AuthRedirects.keepAuth(download, "https://archive.org.evil.com/x"))
        assertFalse(AuthRedirects.keepAuth(download, "https://example.org/x"))
        assertFalse(AuthRedirects.keepAuth(download, "https://notarchive.org/x"))
        assertFalse(AuthRedirects.keepAuth(download, "http://ia601900.us.archive.org/x"))
        assertFalse(AuthRedirects.keepAuth("https://example.org/x", "https://archive.org/download/item/Game.zip"))
    }

    @Test
    fun `resolves relative locations`() {
        assertEquals("https://archive.org/download/item/Other.zip", AuthRedirects.resolve(download, "Other.zip"))
        assertEquals("https://ia601900.us.archive.org/view_archive.php?archive=/2/items/item/Set.zip",
            AuthRedirects.resolve(download, "//ia601900.us.archive.org/view_archive.php?archive=/2/items/item/Set.zip"))
        assertEquals("https://example.org/a", AuthRedirects.resolve(download, "https://example.org/a"))
    }

    @Test
    fun `strips only the credentials`() {
        val headers = mapOf("User-Agent" to "Dogmatix/1.0", "Cookie" to "a=b", "authorization" to "Bearer x")
        assertTrue(AuthRedirects.carriesCredentials(headers))
        assertEquals(mapOf("User-Agent" to "Dogmatix/1.0"), AuthRedirects.withoutCredentials(headers))
        assertFalse(AuthRedirects.carriesCredentials(mapOf("User-Agent" to "Dogmatix/1.0")))
        assertTrue(AuthRedirects.isArchiveHost("ARCHIVE.ORG"))
        assertFalse(AuthRedirects.isArchiveHost(null))
    }
}
