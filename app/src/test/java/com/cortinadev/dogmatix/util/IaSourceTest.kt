package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IaSourceTest {

    private val n64 = "https://archive.org/download/ultimate-rom-collection/Nintendo%20-%20N64.zip/"

    @Test
    fun `recognises ia sources and keeps the identifier's case`() {
        assertTrue(IaSource.isSource("ia://msdos_Prince_of_Persia_1990"))
        assertTrue(IaSource.isSource("  IA://Item "))
        assertFalse(IaSource.isSource("https://archive.org/details/x"))
        assertFalse(IaSource.isSource("romm://psp"))
        assertEquals("msdos_Prince_of_Persia_1990", IaSource.itemOf(" ia://msdos_Prince_of_Persia_1990 "))
        assertEquals("Item", IaSource.itemOf("ia://Item/roms"))
        assertNull(IaSource.itemOf("ia://"))
        assertNull(IaSource.itemOf("ia://  /"))
        assertNull(IaSource.itemOf("https://archive.org/details/x"))
    }

    @Test
    fun `normalizes the four forms of address`() {
        val plain = IaSource.Normalized("ia://msdos_Prince_of_Persia_1990", null)
        assertEquals(plain, IaSource.normalize("ia://msdos_Prince_of_Persia_1990"))
        assertEquals(plain, IaSource.normalize("https://archive.org/details/msdos_Prince_of_Persia_1990"))
        assertEquals(plain, IaSource.normalize("https://www.archive.org/details/msdos_Prince_of_Persia_1990?tab=about"))
        assertEquals(plain, IaSource.normalize("http://archive.org/download/msdos_Prince_of_Persia_1990/"))
        assertEquals(plain, IaSource.normalize(" https://archive.org/download/msdos_Prince_of_Persia_1990 "))
    }

    @Test
    fun `keeps a folder of the item as its subpath`() {
        val roms = IaSource.Normalized("ia://item", "roms/Disc Sets")
        assertEquals(roms, IaSource.normalize("ia://item/roms/Disc Sets/"))
        assertEquals(roms, IaSource.normalize("https://archive.org/download/item/roms/Disc%20Sets/"))
        assertEquals(roms, IaSource.normalize("https://archive.org/details/item//roms/Disc%20Sets"))
    }

    @Test
    fun `an archive of the item becomes its stable zip viewer address`() {
        val viewer = IaSource.Normalized(n64, null)
        assertEquals(viewer, IaSource.normalize("https://archive.org/download/ultimate-rom-collection/Nintendo%20-%20N64.zip/"))
        assertEquals(viewer, IaSource.normalize("https://archive.org/download/ultimate-rom-collection/Nintendo%20-%20N64.zip"))
        assertEquals(
            viewer,
            IaSource.normalize("https://ia601900.us.archive.org/view_archive.php?archive=/2/items/ultimate-rom-collection/Nintendo%20-%20N64.zip")
        )
        assertEquals(
            viewer,
            IaSource.normalize("https://ia601900.us.archive.org/view_archive.php?archive=/2/items/ultimate-rom-collection/Nintendo%20-%20N64.zip&file=Game%20%28USA%29.z64")
        )
        assertEquals(
            IaSource.Normalized("https://archive.org/download/x/sets/Sony%20-%20PS1.7z/", null),
            IaSource.normalize("ia://x/sets/Sony - PS1.7z")
        )
    }

    @Test
    fun `other addresses are not internet archive`() {
        assertNull(IaSource.normalize("https://example.org/download/item/"))
        assertNull(IaSource.normalize("https://archive.org.evil.com/details/item"))
        assertNull(IaSource.normalize("https://archive.org/search?query=nes"))
        assertNull(IaSource.normalize("magnet:?xt=urn:btih:abc"))
        assertNull(IaSource.normalize("romm://psp"))
    }

    @Test
    fun `builds download addresses segment by segment and reads them back`() {
        assertEquals("https://archive.org/download/x/roms/Game%20(USA).zip", IaSource.downloadUrl("x", "roms/Game (USA).zip"))
        assertEquals("https://archive.org/download/x/A%26B%20%2B%20C%23.zip", IaSource.downloadUrl("x", "A&B + C#.zip"))
        assertEquals("x" to "roms/Game (USA).zip", IaSource.itemAndPathOf("https://archive.org/download/x/roms/Game%20(USA).zip"))
        assertEquals("x" to "A&B + C#.zip", IaSource.itemAndPathOf(IaSource.downloadUrl("x", "A&B + C#.zip")))
        assertEquals("item" to "Set.zip/Game (USA).zip", IaSource.itemAndPathOf("https://archive.org/download/item/Set.zip/Game%20%28USA%29.zip"))
        assertNull(IaSource.itemAndPathOf("https://example.org/download/x/a.zip"))
        assertNull(IaSource.itemAndPathOf("https://archive.org/download/x/"))
        assertEquals("https://archive.org/metadata/x", IaSource.metadataUrl("x"))
        assertEquals("https://archive.org/metadata/x/item_last_updated", IaSource.lastUpdatedUrl("x"))
    }

    @Test
    fun `tells files inside an archive from files of the item`() {
        assertTrue(IaSource.isInsideArchive("Nintendo - N64.zip/Game (USA).z64"))
        assertFalse(IaSource.isInsideArchive("roms/Game (USA).zip"))
        assertFalse(IaSource.isInsideArchive("Game.zip"))
    }
}
