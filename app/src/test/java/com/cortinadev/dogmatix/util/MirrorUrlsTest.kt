package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorUrlsTest {

    private val source = "https://myrient.example/files/No-Intro/Nintendo - Game Boy/"
    private val file = "https://myrient.example/files/No-Intro/Nintendo%20-%20Game%20Boy/Tetris%20(World)%20(Rev%201).zip"

    @Test
    fun `same file under each mirror, encoded`() {
        val mirrors = listOf("https://mirror-a.example/No-Intro/Nintendo - Game Boy", "https://mirror-b.example/gb/")
        assertEquals(
            listOf(
                "https://mirror-a.example/No-Intro/Nintendo%20-%20Game%20Boy/Tetris%20%28World%29%20%28Rev%201%29.zip",
                "https://mirror-b.example/gb/Tetris%20%28World%29%20%28Rev%201%29.zip"
            ),
            MirrorUrls.alternatives(file, source, mirrors)
        )
    }

    @Test
    fun `files of another source, or without mirrors, have no alternatives`() {
        assertTrue(MirrorUrls.alternatives("https://other.example/x.zip", source, listOf("https://m.example/")).isEmpty())
        assertTrue(MirrorUrls.alternatives(file, source, emptyList()).isEmpty())
        assertTrue(MirrorUrls.alternatives(file, "magnet:?xt=urn:btih:abc", listOf("https://m.example/")).isEmpty())
    }

    @Test
    fun `the source itself is not its own mirror`() {
        assertTrue(MirrorUrls.alternatives(file, source, listOf(source)).isEmpty())
        assertEquals(
            listOf("https://m.example/gb", "https://m.example/No-Intro/Nintendo - Game Boy"),
            MirrorUrls.parse("https://m.example/gb\n\nftp://nope\n$source\n https://m.example/gb\nhttps://m.example/No-Intro/Nintendo - Game Boy ", source)
        )
    }

    @Test
    fun `host of a web address`() {
        assertEquals("myrient.example", MirrorUrls.hostOf(file))
        assertEquals("myrient.example", MirrorUrls.hostOf(source))
        assertEquals("192.168.1.10", MirrorUrls.hostOf("http://192.168.1.10:8090/api/roms/1/content/x.zip"))
        assertNull(MirrorUrls.hostOf("magnet:?xt=urn:btih:abc"))
        assertNull(MirrorUrls.hostOf("romm://psx"))
    }
}
