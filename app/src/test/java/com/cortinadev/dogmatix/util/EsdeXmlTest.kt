package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EsdeXmlTest {

    private val bundled = """
        <?xml version="1.0"?>
        <systemList>
            <system>
                <name>snes</name>
                <fullname>Nintendo SNES (Super Nintendo)</fullname>
                <path>%ROMPATH%/snes</path>
                <extension>.sfc .SFC .zip .ZIP</extension>
                <command label="Snes9x - Current">%EMULATOR_RETROARCH% %EXTRA_ROM%=%ROM%</command>
                <command label="bsnes">%EMULATOR_RETROARCH% %EXTRA_ROM%=%ROM%</command>
                <platform>snes</platform>
                <theme>snes</theme>
            </system>
            <system>
                <name>megadrive</name>
                <fullname>Sega Mega Drive</fullname>
                <path>%ROMPATH%/megadrive</path>
                <extension>.md .MD</extension>
                <command label="Genesis Plus GX">%EMULATOR_RETROARCH% %EXTRA_ROM%=%ROM%</command>
                <platform>megadrive</platform>
                <theme>megadrive</theme>
            </system>
        </systemList>
    """.trimIndent()

    @Test
    fun `find rules are created from scratch and merged into existing files`() {
        val created = EsdeXml.findRulesWithDogmatix(null, "com.cortinadev.dogmatix/com.cortinadev.dogmatix.MainActivity")!!
        assertTrue(created.contains("<emulator name=\"DOGMATIX\">"))
        assertTrue(created.contains("<entry>com.cortinadev.dogmatix/com.cortinadev.dogmatix.MainActivity</entry>"))

        val existing = "<?xml version=\"1.0\"?>\n<ruleList>\n    <emulator name=\"OTHER\">\n    </emulator>\n</ruleList>\n"
        val merged = EsdeXml.findRulesWithDogmatix(existing, "a/b")!!
        assertTrue(merged.contains("OTHER"))
        assertTrue(merged.indexOf("DOGMATIX") > merged.indexOf("OTHER"))
        assertTrue(merged.trimEnd().endsWith("</ruleList>"))

        assertNull(EsdeXml.findRulesWithDogmatix(merged, "a/b"))
    }

    @Test
    fun `bundled system is copied, extended and gets the command last`() {
        val patch = EsdeXml.patchCustomSystems(null, bundled, listOf("snes"))
        assertTrue(patch.changed)
        assertEquals(listOf("snes"), patch.configured)
        val block = EsdeXml.systemBlock(patch.content, "snes")!!
        assertTrue(block.contains("<extension>.dgmtx .DGMTX .sfc"))
        assertTrue(block.contains("label=\"Dogmatix\""))
        // Dogmatix must not become the default emulator: its command goes last.
        assertTrue(block.indexOf("label=\"Dogmatix\"") > block.lastIndexOf("label=\"bsnes\""))
        // The other bundled system is not dragged in.
        assertNull(EsdeXml.systemBlock(patch.content, "megadrive"))
    }

    @Test
    fun `existing custom system is patched in place and kept`() {
        val custom = EsdeXml.patchCustomSystems(null, bundled, listOf("megadrive")).content
        val patch = EsdeXml.patchCustomSystems(custom, bundled, listOf("megadrive", "snes"))
        assertEquals(listOf("megadrive", "snes"), patch.configured)
        // megadrive was already configured: not duplicated.
        assertEquals(1, Regex("<name>megadrive</name>").findAll(patch.content).count())
        // Re-running changes nothing.
        assertFalse(EsdeXml.patchCustomSystems(patch.content, bundled, listOf("megadrive", "snes")).changed)
    }

    @Test
    fun `folders without a matching system are reported`() {
        val patch = EsdeXml.patchCustomSystems(null, bundled, listOf("Super Nintendo Entertainment System"))
        assertEquals(listOf("Super Nintendo Entertainment System"), patch.missing)
        assertTrue(patch.configured.isEmpty())
    }

    @Test
    fun `system matches by path basename too`() {
        assertTrue(EsdeXml.systemBlock(bundled, "SNES")!!.contains("<name>snes</name>"))
    }

    @Test
    fun `gamelist entry is created, merged and not duplicated`() {
        val file = "star.dgmtx"
        val created = EsdeXml.gamelistWithShortcut(null, file, "★ Search for more games...")!!
        assertTrue(created.contains("<path>./star.dgmtx</path>"))
        assertTrue(created.contains("<altemulator>Dogmatix</altemulator>"))

        val existing = "<?xml version=\"1.0\"?>\n<gameList>\n\t<game>\n\t\t<path>./Mario.zip</path>\n\t</game>\n</gameList>\n"
        val merged = EsdeXml.gamelistWithShortcut(existing, file, "n")!!
        assertTrue(merged.contains("Mario.zip"))
        assertTrue(merged.trimEnd().endsWith("</gameList>"))

        assertNull(EsdeXml.gamelistWithShortcut(merged, file, "n"))
    }
}
