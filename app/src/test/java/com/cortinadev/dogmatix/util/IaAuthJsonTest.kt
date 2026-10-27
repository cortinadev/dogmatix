package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IaAuthJsonTest {

    @Test
    fun `a valid session gives the account's name`() {
        val body = """{"success": true, "value": {"username": "player@example.com", "screenname": "Player One", "itemname": "@player"}}"""
        assertEquals(IaIdentity("Player One"), IaAuthJson.whoami(200, body))
        assertEquals(IaIdentity("@player"), IaAuthJson.whoami(200, """{"success": true, "value": {"itemname": "@player"}}"""))
        assertEquals(IaIdentity(null), IaAuthJson.whoami(200, """{"success": true}"""))
    }

    @Test
    fun `not logged in, errors and odd answers are no session`() {
        assertNull(IaAuthJson.whoami(401, """{"success": false, "value": "Not logged in"}"""))
        assertNull(IaAuthJson.whoami(200, """{"success": false, "value": "Not logged in"}"""))
        assertNull(IaAuthJson.whoami(200, "<html>error</html>"))
        assertNull(IaAuthJson.whoami(503, ""))
    }
}
