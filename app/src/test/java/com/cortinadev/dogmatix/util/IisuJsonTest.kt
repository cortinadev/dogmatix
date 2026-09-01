package com.cortinadev.dogmatix.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IisuJsonTest {

    private val component = "com.cortinadev.dogmatix/com.cortinadev.dogmatix.MainActivity"

    private val document = """
        {
          "consoles": [
            {
              "shortName": "xbox",
              "longName": "Microsoft Xbox",
              "romExtensions": [".iso", ".ISO"],
              "emulators": [
                {
                  "id": "X1-BOX",
                  "name": "X1 BOX (Standalone)",
                  "routeType": "uri",
                  "commands": [{ "description": "X1 BOX", "command": "com.izzy2lost.x1box/.LauncherActivity -d %ROM_URI%" }],
                  "packages": ["com.izzy2lost.x1box"]
                }
              ]
            },
            {
              "shortName": "nes",
              "longName": "Nintendo Entertainment System",
              "romExtensions": [".nes", ".NES"],
              "emulators": []
            }
          ]
        }
    """.trimIndent()

    private fun console(json: String, shortName: String) =
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("consoles")
            .map { it.asJsonObject }
            .first { it.get("shortName").asString == shortName }

    @Test
    fun `adds the extension and the emulator to the requested console only`() {
        val patch = IisuJson.patchEmuladores(document, listOf("xbox"), component)

        assertTrue(patch.changed)
        assertEquals(listOf("xbox"), patch.configured)
        assertTrue(patch.missing.isEmpty())

        val xbox = console(patch.content, "xbox")
        val extensions = xbox.getAsJsonArray("romExtensions").map { it.asString }
        assertTrue(extensions.containsAll(listOf(".iso", ".ISO", ".dgmtx", ".DGMTX")))

        val emulators = xbox.getAsJsonArray("emulators").map { it.asJsonObject }
        assertEquals(listOf("X1-BOX", IisuJson.EMULATOR_ID), emulators.map { it.get("id").asString })

        val dogmatix = emulators.last()
        assertEquals("uri", dogmatix.get("routeType").asString)
        assertEquals(
            "$component -a android.intent.action.VIEW -d %ROM_URI%",
            dogmatix.getAsJsonArray("commands").first().asJsonObject.get("command").asString
        )
        assertEquals(
            listOf("com.cortinadev.dogmatix"),
            dogmatix.getAsJsonArray("packages").map { it.asString }
        )

        // The console we did not ask for keeps exactly what it had.
        val nes = console(patch.content, "nes")
        assertEquals(listOf(".nes", ".NES"), nes.getAsJsonArray("romExtensions").map { it.asString })
        assertEquals(0, nes.getAsJsonArray("emulators").size())
    }

    @Test
    fun `console with no emulators list still gets one`() {
        val patch = IisuJson.patchEmuladores(document, listOf("nes"), component)
        val emulators = console(patch.content, "nes").getAsJsonArray("emulators").map { it.asJsonObject }
        assertEquals(listOf(IisuJson.EMULATOR_ID), emulators.map { it.get("id").asString })
    }

    @Test
    fun `folder names are matched case-insensitively`() {
        val patch = IisuJson.patchEmuladores(document, listOf("XBOX"), component)
        assertEquals(listOf("XBOX"), patch.configured)
        assertTrue(patch.changed)
    }

    @Test
    fun `folders with no console are reported and nothing is written for them`() {
        val patch = IisuJson.patchEmuladores(document, listOf("dreamcast"), component)
        assertFalse(patch.changed)
        assertEquals(listOf("dreamcast"), patch.missing)
        assertTrue(patch.configured.isEmpty())
    }

    @Test
    fun `running twice changes nothing the second time`() {
        val first = IisuJson.patchEmuladores(document, listOf("xbox"), component)
        val second = IisuJson.patchEmuladores(first.content, listOf("xbox"), component)

        assertFalse(second.changed)
        // An unchanged document comes back byte-identical, not merely equivalent: reflowing it
        // would drop comments and formatting the user (or iiSU's updater) may care about.
        assertEquals(first.content, second.content)
    }

    @Test
    fun `null romExtensions and emulators count as empty`() {
        val doc = """{ "consoles": [ { "shortName": "xbox", "romExtensions": null, "emulators": null } ] }"""
        val patch = IisuJson.patchEmuladores(doc, listOf("xbox"), component)

        assertTrue(patch.changed)
        val xbox = console(patch.content, "xbox")
        assertEquals(listOf(".dgmtx", ".DGMTX"), xbox.getAsJsonArray("romExtensions").map { it.asString })
        assertEquals(listOf(IisuJson.EMULATOR_ID), xbox.getAsJsonArray("emulators").map { it.asJsonObject.get("id").asString })
    }

    @Test
    fun `a document without a consoles list is rejected, not silently accepted`() {
        for (doc in listOf("""{}""", """{ "consoles": {} }""", """[]""", """null""")) {
            try {
                IisuJson.patchEmuladores(doc, listOf("xbox"), component)
                fail("expected IllegalArgumentException for $doc")
            } catch (_: IllegalArgumentException) {
                // Callers treat this as "the file is damaged" and write nothing.
            }
        }
    }

    @Test
    fun `an entry left by an older version is replaced, not duplicated`() {
        val stale = IisuJson.patchEmuladores(document, listOf("xbox"), "old.pkg/old.pkg.MainActivity")
        val fresh = IisuJson.patchEmuladores(stale.content, listOf("xbox"), component)

        assertTrue(fresh.changed)
        val emulators = console(fresh.content, "xbox").getAsJsonArray("emulators").map { it.asJsonObject }
        assertEquals(1, emulators.count { it.get("id").asString == IisuJson.EMULATOR_ID })
        assertTrue(
            emulators.last().getAsJsonArray("commands").first().asJsonObject
                .get("command").asString.startsWith(component)
        )
    }
}
