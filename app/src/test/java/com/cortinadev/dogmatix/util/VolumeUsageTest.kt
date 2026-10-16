package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeUsageTest {

    private fun volume(id: String, removable: Boolean, label: String = "Espacio compartido interno") =
        VolumeUsage(id, label, removable, totalBytes = 100, freeBytes = 40, hostsLibrary = false)

    @Test
    fun `a volume is named by what it is, not by the system description`() {
        assertEquals(VolumeKind.INTERNAL, volume(VolumeUsage.PRIMARY, removable = false).kind)
        assertEquals(VolumeKind.SD_CARD, volume("6A3B-BB88", removable = true).kind)
        assertEquals(VolumeKind.EXTERNAL, volume("1234-ABCD", removable = false).kind)
    }

    @Test
    fun `only volumes of the same kind need their id`() {
        val internal = volume(VolumeUsage.PRIMARY, removable = false)
        val sd = volume("6A3B-BB88", removable = true)
        val usb = volume("0000-1111", removable = true)
        assertFalse(VolumeUsage.needsId(internal, listOf(internal, sd)))
        assertFalse(VolumeUsage.needsId(sd, listOf(internal, sd)))
        assertTrue(VolumeUsage.needsId(sd, listOf(internal, sd, usb)))
        assertTrue(VolumeUsage.needsId(usb, listOf(internal, sd, usb)))
    }
}
