package com.cortinadev.dogmatix.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RommPlayStateTest {

    @Test
    fun `every value survives a write and a read back`() {
        val start = RommUserProps(status = "incomplete", backlogged = true, nowPlaying = false)
        for (state in RommPlayState.entries) {
            assertEquals(state, RommPlayState.of(state.applyTo(start)))
            assertEquals(state, RommPlayState.of(state.applyTo(RommUserProps())))
        }
    }

    @Test
    fun `reads playing over the status and the status over the backlog`() {
        assertEquals(RommPlayState.PLAYING, RommPlayState.of(RommUserProps("finished", backlogged = true, nowPlaying = true)))
        assertEquals(RommPlayState.FINISHED, RommPlayState.of(RommUserProps("finished", backlogged = true)))
        assertEquals(RommPlayState.BACKLOG, RommPlayState.of(RommUserProps(backlogged = true)))
        assertEquals(RommPlayState.NONE, RommPlayState.of(RommUserProps()))
    }

    @Test
    fun `sends only what changes`() {
        val playing = RommUserProps(status = "incomplete", nowPlaying = true)
        assertEquals(mapOf("status" to "finished", "now_playing" to false), RommPlayState.FINISHED.changesFrom(playing))
        assertEquals(mapOf("status" to null, "backlogged" to true, "now_playing" to false), RommPlayState.BACKLOG.changesFrom(playing))
        // Playing keeps the status RomM shows next to it.
        assertEquals(mapOf("now_playing" to true), RommPlayState.PLAYING.changesFrom(RommUserProps(status = "incomplete")))
        assertTrue(RommPlayState.NONE.changesFrom(RommUserProps()).isEmpty())
    }

    @Test
    fun `steps wrap around both ways`() {
        assertEquals(RommPlayState.NEVER_PLAYING, RommPlayState.NONE.step(-1))
        assertEquals(RommPlayState.NONE, RommPlayState.NEVER_PLAYING.step(1))
        assertEquals(RommPlayState.PLAYING, RommPlayState.NONE.step(2))
    }

    @Test
    fun `normalises statuses and names`() {
        assertEquals("completed_100", RommPlayState.normaliseStatus(" Completed-100 "))
        assertEquals("never_playing", RommPlayState.normaliseStatus("never playing"))
        assertEquals(null, RommPlayState.normaliseStatus("beaten"))
        assertEquals(RommPlayState.RETIRED, RommPlayState.fromName("RETIRED"))
        assertEquals(RommPlayState.NONE, RommPlayState.fromName(null))
    }

    @Test
    fun `the body carries the fields at the top level and inside data`() {
        val body = JsonParser.parseString(RommPlayState.body(mapOf("status" to null, "backlogged" to true))).asJsonObject
        assertTrue(body.get("status").isJsonNull)
        assertTrue(body.get("backlogged").asBoolean)
        val data = body.getAsJsonObject("data")
        assertTrue(data.get("status").isJsonNull)
        assertTrue(data.get("backlogged").asBoolean)
    }
}
