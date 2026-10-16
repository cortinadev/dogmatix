package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.FrontendCheck.Detail
import com.cortinadev.dogmatix.util.FrontendCheck.EsdeInput
import com.cortinadev.dogmatix.util.FrontendCheck.Folder
import com.cortinadev.dogmatix.util.FrontendCheck.IisuInput
import com.cortinadev.dogmatix.util.FrontendCheck.Repair
import com.cortinadev.dogmatix.util.FrontendCheck.Shortcut
import com.cortinadev.dogmatix.util.FrontendCheck.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontendCheckTest {

    private val component = DgmtxFile.launchComponent("com.cortinadev.dogmatix.debug")
    private val release = DgmtxFile.launchComponent("com.cortinadev.dogmatix")

    private val bundled = """
        <systemList>
            <system><name>snes</name><path>%ROMPATH%/snes</path><extension>.sfc .zip</extension><command label="Snes9x">x</command></system>
            <system><name>psx</name><path>%ROMPATH%/psx</path><extension>.cue .m3u</extension><command label="DuckStation">x</command></system>
        </systemList>
    """.trimIndent()

    private val custom = EsdeXml.patchCustomSystems(null, bundled, listOf("snes", "psx")).content
    private val findRules = EsdeXml.findRulesWithDogmatix(null, component)!!
    private val shortcutEntry = EsdeXml.gamelistWithShortcut(null, DgmtxFile.SHORTCUT_FILE, DgmtxFile.SHORTCUT_NAME)!!

    private fun esde(
        installed: Boolean = true,
        folder: Folder = Folder.OK,
        findRules: String? = this.findRules,
        customSystems: String? = custom,
        gamelists: Map<String, String> = mapOf("snes" to shortcutEntry, "psx" to shortcutEntry),
        covers: Set<String> = setOf("snes", "psx")
    ) = FrontendCheck.evaluateEsde(
        EsdeInput(
            installed, folder, component, findRules, customSystems,
            FrontendCheck.esdeSystems(listOf("snes", "psx", "gba"), bundled, customSystems),
            gamelists.filterValues(FrontendCheck::listsShortcut).keys, covers
        )
    )

    @Test
    fun esdeAllInPlace() {
        val findings = esde()
        assertTrue(findings.all { it.status == Status.OK })
        assertEquals(
            listOf(Detail.ESDE_FOLDER_OK, Detail.ESDE_FIND_RULE_OK, Detail.ESDE_SYSTEMS_OK, Detail.ESDE_GAMELISTS_OK, Detail.ESDE_COVERS_OK),
            findings.map { it.detail }
        )
        assertEquals(2, findings.first { it.detail == Detail.ESDE_SYSTEMS_OK }.total)
    }

    @Test
    fun esdeFolderStates() {
        // Not installed and never set up: no ES-DE section at all.
        assertTrue(esde(installed = false, folder = Folder.NOT_SET).isEmpty())
        assertEquals(listOf(Repair.PICK_ESDE_FOLDER), esde(folder = Folder.NOT_SET).map { it.repair })
        val lost = esde(folder = Folder.NO_ACCESS).single()
        assertEquals(Detail.ESDE_FOLDER_NO_ACCESS, lost.detail)
        assertEquals(Repair.PICK_ESDE_FOLDER, lost.repair)
    }

    @Test
    fun esdeWarningsNameWhatIsMissingAndRepairWithTheSetup() {
        val other = EsdeXml.findRulesWithDogmatix(null, release)!!
        val findings = esde(
            findRules = other,
            gamelists = mapOf("snes" to shortcutEntry, "psx" to "<gameList>\n</gameList>"),
            covers = setOf("psx")
        )
        val rule = findings.first { it.detail == Detail.ESDE_FIND_RULE_OTHER_BUILD }
        assertEquals(listOf("com.cortinadev.dogmatix"), rule.missing)
        assertEquals(Repair.CONFIGURE_ESDE, rule.repair)
        assertEquals(listOf("psx"), findings.first { it.detail == Detail.ESDE_GAMELISTS_MISSING }.missing)
        val covers = findings.first { it.detail == Detail.ESDE_COVERS_MISSING }
        assertEquals(listOf("snes"), covers.missing)
        assertEquals(2, covers.total)

        // Both builds listed: this one is registered.
        assertTrue(esde(findRules = EsdeXml.findRulesWithDogmatix(other, component)).any { it.detail == Detail.ESDE_FIND_RULE_OK })
        assertTrue(esde(findRules = null).any { it.detail == Detail.ESDE_FIND_RULE_MISSING && it.repair == Repair.CONFIGURE_ESDE })
    }

    @Test
    fun esdeSystemsWithoutTheCommandAndWithoutAnyMatch() {
        // Custom systems as the user had them before the setup: snes overridden without Dogmatix.
        val plain = "<systemList>\n<system><name>snes</name><path>%ROMPATH%/snes</path><extension>.sfc</extension></system>\n</systemList>"
        val systems = esde(customSystems = plain).first { it.detail == Detail.ESDE_SYSTEMS_MISSING }
        assertEquals(listOf("psx", "snes"), systems.missing)
        assertEquals(Repair.CONFIGURE_ESDE, systems.repair)

        val none = FrontendCheck.evaluateEsde(EsdeInput(true, Folder.OK, component, findRules, custom, emptyMap()))
        assertEquals(Detail.ESDE_NO_SYSTEMS, none.last().detail)
        assertEquals(Repair.NONE, none.last().repair)
    }

    @Test
    fun withoutEsdeInstalledNothingCanBeRepaired() {
        val findings = esde(installed = false, findRules = null)
        assertTrue(findings.any { it.detail == Detail.ESDE_NOT_INSTALLED })
        assertTrue(findings.all { it.repair == Repair.NONE })
    }

    @Test
    fun esdeSystemsPreferTheCustomBlockAndUseItsName() {
        val renamed = "<systemList><system><name>SFC</name><path>%ROMPATH%/snes</path></system></systemList>"
        assertEquals(mapOf("snes" to "sfc", "psx" to "psx"), FrontendCheck.esdeSystems(listOf("snes", "psx", "gba"), bundled, renamed))
        assertEquals(mapOf("snes" to "snes"), FrontendCheck.esdeSystems(listOf("snes"), null, custom))
    }

    // ---- iiSU ----------------------------------------------------------------------------------

    private val emuladores = """
        { "consoles": [
            { "shortName": "snes", "romExtensions": [".sfc"], "emulators": [] },
            { "shortName": "psx", "romExtensions": [".cue"], "emulators": [] },
            { "shortName": "n64", "romExtensions": [".z64"], "emulators": [] }
        ] }
    """.trimIndent()

    private fun iisu(installed: Boolean = true, folder: Folder = Folder.OK, text: String? = emuladores, folders: List<String> = listOf("snes", "psx", "gba")) =
        FrontendCheck.evaluateIisu(IisuInput(installed, folder, component, text, folders))

    @Test
    fun iisuFindsConsolesWithoutTheEmulator() {
        val patched = IisuJson.patchEmuladores(emuladores, listOf("snes"), component).content
        val finding = iisu(text = patched).last()
        assertEquals(Detail.IISU_EMULATORS_MISSING, finding.detail)
        assertEquals(listOf("psx"), finding.missing)
        assertEquals(2, finding.total)
        assertEquals(Repair.CONFIGURE_IISU, finding.repair)

        val all = IisuJson.patchEmuladores(emuladores, listOf("snes", "psx"), component).content
        assertEquals(Detail.IISU_EMULATORS_OK, iisu(text = all).last().detail)
        // Registered for the other build only: not this one.
        val other = IisuJson.patchEmuladores(emuladores, listOf("snes", "psx"), release).content
        assertEquals(listOf("psx", "snes"), iisu(text = other).last().missing)
    }

    @Test
    fun iisuFileAndFolderProblems() {
        assertTrue(iisu(installed = false, folder = Folder.NOT_SET).isEmpty())
        assertEquals(Detail.IISU_WRONG_FOLDER, iisu(folder = Folder.WRONG).single().detail)
        assertEquals(Repair.PICK_IISU_FOLDER, iisu(folder = Folder.NO_ACCESS).single().repair)

        val missing = iisu(text = null).last()
        assertEquals(Detail.IISU_FILE_MISSING, missing.detail)
        assertEquals(Repair.CONFIGURE_IISU, missing.repair)
        // Without iiSU there is no APK to start a missing file from.
        assertEquals(Repair.NONE, iisu(installed = false, text = null).last().repair)

        for (broken in listOf("", "{ not json", "[]", "{ \"other\": 1 }")) {
            val finding = iisu(text = broken).last()
            assertEquals(Detail.IISU_FILE_CORRUPT, finding.detail)
            assertEquals(Repair.NONE, finding.repair)
        }
        assertEquals(Detail.IISU_NO_CONSOLES, iisu(folders = listOf("gba")).last().detail)
    }

    // ---- Shortcuts -----------------------------------------------------------------------------

    @Test
    fun shortcutsAreCountedPerConsole() {
        val missing = FrontendCheck.evaluateShortcuts(true, listOf(Shortcut("SNES", true), Shortcut("GBA", false), Shortcut("PS1", false)))
        assertEquals(Status.WARN, missing.status)
        assertEquals(listOf("GBA", "PS1"), missing.missing)
        assertEquals(3, missing.total)
        assertEquals(Repair.DEPLOY_SHORTCUTS, missing.repair)

        assertEquals(Detail.SHORTCUTS_OK, FrontendCheck.evaluateShortcuts(true, listOf(Shortcut("SNES", true))).detail)
        assertEquals(Detail.SHORTCUTS_NO_FOLDER, FrontendCheck.evaluateShortcuts(false, emptyList()).detail)
        assertEquals(Status.OK, FrontendCheck.evaluateShortcuts(true, emptyList()).status)
    }
}
