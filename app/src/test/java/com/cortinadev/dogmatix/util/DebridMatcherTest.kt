package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.service.DebridFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebridMatcherTest {

    @Test
    fun `hex info-hash is lower-cased and extra params are ignored`() {
        val hash = "C12FE1C06BB254B8A0F3A1E5B5A4E5A6C7D8E9F0"
        val magnet = "magnet:?xt=urn:btih:$hash&dn=Some%20Name&tr=udp%3A%2F%2Ftracker"
        assertEquals(hash.lowercase(), DebridMatcher.infoHashFromMagnet(magnet))
    }

    @Test
    fun `base32 info-hash is converted to hex`() {
        // 32 base32 chars = 160 bits = 40 hex chars.
        val magnet = "magnet:?xt=urn:btih:YEX6DQDLWJKLRIHTUHS5LJKLU3ID44PQ"
        assertEquals("c12fe1c06bb254b8a0f3a1e5d5a54ba6d03e71f0", DebridMatcher.infoHashFromMagnet(magnet))
    }

    @Test
    fun `malformed magnets yield null`() {
        assertNull(DebridMatcher.infoHashFromMagnet("magnet:?dn=only-a-name"))
        assertNull(DebridMatcher.infoHashFromMagnet("magnet:?xt=urn:btih:tooshort"))
        assertNull(DebridMatcher.infoHashFromMagnet(""))
    }

    private val files = listOf(
        DebridFile(0, "Set/Game (USA).zip", 100),
        DebridFile(1, "Set/Game (Europe).zip", 200),
        DebridFile(2, "Set/Sub/Other Game.7z", 300),
        DebridFile(3, "Set/Game (USA).zip", 150)   // duplicate name, different size
    )

    @Test
    fun `exact base name and size wins over duplicates`() {
        assertEquals(3, DebridMatcher.pickFile(files, "Game (USA).zip", 150)?.id)
        assertEquals(0, DebridMatcher.pickFile(files, "Game (USA).zip", 100)?.id)
    }

    @Test
    fun `url-encoded and differently-cased names still match`() {
        assertEquals(1, DebridMatcher.pickFile(files, "game%20(europe).ZIP", 0)?.id)
        assertEquals(2, DebridMatcher.pickFile(files, "Other Game.7z", 999)?.id)
    }

    @Test
    fun `unique size match is a last resort and ambiguity yields null`() {
        assertEquals(2, DebridMatcher.pickFile(files, "renamed.7z", 300)?.id)
        assertNull(DebridMatcher.pickFile(files, "Game (USA).zip", 999))   // two candidates, no size match
        assertNull(DebridMatcher.pickFile(files, "missing.zip", 0))
        assertNull(DebridMatcher.pickFile(emptyList(), "Game (USA).zip", 100))
    }
}
