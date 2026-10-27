package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IaCookiesTest {

    // Made-up values: never a real session in a test.
    private val user = "player%40example.com"
    private val sig = "1700000000%201700086400%20abcDEF123%3D"

    @Test
    fun `reads both cookies out of the cookie manager's header`() {
        val header = "donation-identifier=abc; logged-in-sig=$sig; logged-in-user=$user; abtest=1"
        val pair = IaCookies.parse(header)!!
        assertEquals(user, pair.user)
        assertEquals(sig, pair.sig)
        assertEquals("player@example.com", pair.email)
    }

    @Test
    fun `either order and spacing`() {
        assertEquals(IaCookiePair(user, sig), IaCookies.parse("logged-in-user=$user;logged-in-sig=$sig"))
        assertEquals(IaCookiePair(user, sig), IaCookies.parse("  logged-in-sig = $sig ;  logged-in-user=$user  "))
    }

    @Test
    fun `one cookie alone is not a session`() {
        assertNull(IaCookies.parse("logged-in-user=$user"))
        assertNull(IaCookies.parse("logged-in-sig=$sig; other=1"))
        assertNull(IaCookies.parse("logged-in-user=; logged-in-sig=$sig"))
        assertNull(IaCookies.parse(""))
        assertNull(IaCookies.parse(null))
    }

    @Test
    fun `the stored pair goes back as a cookie header`() {
        val pair = IaCookies.of(user, sig)!!
        assertEquals("logged-in-user=$user; logged-in-sig=$sig", IaCookies.header(pair))
        assertNull(IaCookies.of("", sig))
        assertNull(IaCookies.of(user, " "))
        // A plus sign in the address stays one.
        assertEquals("a+b@example.com", IaCookiePair("a+b%40example.com", sig).email)
    }
}
