package com.cortinadev.dogmatix.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * "Check frontends" in Settings: whether what the frontend setups wrote is still in place, one
 * finding per point, and which existing setup repairs each warning. ES-DE: folder access, the
 * find rule (for this build), the system overrides, the shortcut's gamelist entry and its cover.
 * iiSU: the Dogmatix emulator in `emuladores.json`. And the `.dgmtx` shortcut in every console
 * folder, which is all Daijishō needs (its own configuration is private and cannot be read).
 *
 * The service reads the files; the rules live here, pure JVM, for the tests.
 */
object FrontendCheck {

    enum class Frontend { ESDE, IISU, SHORTCUTS }

    enum class Status { OK, WARN }

    /** What "Repair" runs for a warning: always one of the setups Settings already has. */
    enum class Repair { NONE, PICK_ESDE_FOLDER, CONFIGURE_ESDE, PICK_IISU_FOLDER, CONFIGURE_IISU, DEPLOY_SHORTCUTS }

    /** The sentence a finding shows; the screen maps each one to a string. */
    enum class Detail {
        ESDE_FOLDER_OK, ESDE_FOLDER_NOT_SET, ESDE_FOLDER_NO_ACCESS, ESDE_NOT_INSTALLED,
        ESDE_FIND_RULE_OK, ESDE_FIND_RULE_MISSING, ESDE_FIND_RULE_OTHER_BUILD,
        ESDE_SYSTEMS_OK, ESDE_SYSTEMS_MISSING, ESDE_NO_SYSTEMS,
        ESDE_GAMELISTS_OK, ESDE_GAMELISTS_MISSING,
        ESDE_COVERS_OK, ESDE_COVERS_MISSING,
        IISU_FOLDER_OK, IISU_FOLDER_NOT_SET, IISU_FOLDER_NO_ACCESS, IISU_WRONG_FOLDER, IISU_NOT_INSTALLED,
        IISU_FILE_MISSING, IISU_FILE_CORRUPT, IISU_NO_CONSOLES, IISU_EMULATORS_OK, IISU_EMULATORS_MISSING,
        SHORTCUTS_OK, SHORTCUTS_MISSING, SHORTCUTS_NO_FOLDER, SHORTCUTS_NO_CONSOLES
    }

    /** A frontend folder picked in Settings: not yet, picked but unusable now, not the frontend's, or fine. */
    enum class Folder { NOT_SET, NO_ACCESS, WRONG, OK }

    data class Finding(
        val frontend: Frontend,
        val status: Status,
        val detail: Detail,
        val repair: Repair = Repair.NONE,
        /** Counted details: how many there are in all, and which are missing (systems, consoles, builds). */
        val total: Int = 0,
        val missing: List<String> = emptyList()
    )

    // ---- ES-DE --------------------------------------------------------------------------------

    data class EsdeInput(
        val installed: Boolean,
        val folder: Folder,
        /** This build's `package/activity`. */
        val component: String,
        /** `custom_systems/es_find_rules.xml`; null when there is none. */
        val findRules: String? = null,
        /** `custom_systems/es_systems.xml`; null when there is none. */
        val customSystems: String? = null,
        /** ES-DE system of each console folder, see [esdeSystems]. */
        val systems: Map<String, String> = emptyMap(),
        /** Systems whose `gamelist.xml` lists the shortcut (see [listsShortcut]). */
        val listed: Set<String> = emptySet(),
        /** Systems whose `downloaded_media/<system>/covers/` holds the shortcut's cover. */
        val covers: Set<String> = emptySet()
    )

    /**
     * The ES-DE system (lower-case `<name>`) of each console folder that has one, among the
     * user's custom systems or ES-DE's own [bundled] ones: the folders the setup configures.
     */
    fun esdeSystems(folders: Collection<String>, bundled: String?, custom: String?): Map<String, String> =
        folders.mapNotNull { folder ->
            val block = custom?.let { EsdeXml.systemBlock(it, folder) } ?: bundled?.let { EsdeXml.systemBlock(it, folder) }
            block?.let { folder to (EsdeXml.tagText(it, "name") ?: folder).lowercase() }
        }.toMap()

    fun evaluateEsde(input: EsdeInput): List<Finding> {
        // Neither installed nor set up: nothing to report.
        if (!input.installed && input.folder == Folder.NOT_SET) return emptyList()
        when (input.folder) {
            Folder.NOT_SET -> return listOf(warn(Frontend.ESDE, Detail.ESDE_FOLDER_NOT_SET, Repair.PICK_ESDE_FOLDER))
            Folder.NO_ACCESS, Folder.WRONG -> return listOf(warn(Frontend.ESDE, Detail.ESDE_FOLDER_NO_ACCESS, Repair.PICK_ESDE_FOLDER))
            Folder.OK -> Unit
        }
        val out = mutableListOf(ok(Frontend.ESDE, Detail.ESDE_FOLDER_OK))
        // The setup starts from the installed ES-DE's own systems: without it, nothing can repair.
        val configure = if (input.installed) Repair.CONFIGURE_ESDE else Repair.NONE
        if (!input.installed) out += warn(Frontend.ESDE, Detail.ESDE_NOT_INSTALLED)

        val entries = input.findRules?.let(EsdeXml::findRuleEntries)
        out += when {
            entries == null -> warn(Frontend.ESDE, Detail.ESDE_FIND_RULE_MISSING, configure)
            input.component in entries -> ok(Frontend.ESDE, Detail.ESDE_FIND_RULE_OK)
            else -> warn(Frontend.ESDE, Detail.ESDE_FIND_RULE_OTHER_BUILD, configure, missing = entries.map { it.substringBefore('/') })
        }

        val systems = input.systems.values.distinct().sorted()
        if (systems.isEmpty()) return out + warn(Frontend.ESDE, Detail.ESDE_NO_SYSTEMS)
        out += counted(
            Frontend.ESDE, systems, Detail.ESDE_SYSTEMS_OK, Detail.ESDE_SYSTEMS_MISSING, configure
        ) { system -> input.customSystems?.let { EsdeXml.systemBlock(it, system) }?.let(::launchesDogmatix) == true }
        out += counted(
            Frontend.ESDE, systems, Detail.ESDE_GAMELISTS_OK, Detail.ESDE_GAMELISTS_MISSING, configure
        ) { it in input.listed }
        out += counted(Frontend.ESDE, systems, Detail.ESDE_COVERS_OK, Detail.ESDE_COVERS_MISSING, configure) { it in input.covers }
        return out
    }

    /** Whether a system's gamelist has an entry for the shortcut file (what the setup adds). */
    fun listsShortcut(gamelist: String): Boolean =
        EsdeFavouriteSync.key(DgmtxFile.SHORTCUT_FILE) in EsdeFavouriteSync.favourites(gamelist)

    /** A system block that lists `.dgmtx` files and has the Dogmatix command (what the setup adds). */
    private fun launchesDogmatix(block: String): Boolean =
        block.contains(".${DgmtxFile.EXTENSION}") && block.contains("%EMULATOR_${EsdeXml.EMULATOR_NAME}%")

    // ---- iiSU ---------------------------------------------------------------------------------

    data class IisuInput(
        val installed: Boolean,
        val folder: Folder,
        val component: String,
        /** `Emuladores/emuladores.json`; null when there is none, "" when it could not be read. */
        val emuladores: String? = null,
        /** Console folder names (iiSU's `shortName` is the folder name). */
        val folders: Collection<String> = emptyList()
    )

    fun evaluateIisu(input: IisuInput): List<Finding> {
        if (!input.installed && input.folder == Folder.NOT_SET) return emptyList()
        when (input.folder) {
            Folder.NOT_SET -> return listOf(warn(Frontend.IISU, Detail.IISU_FOLDER_NOT_SET, Repair.PICK_IISU_FOLDER))
            Folder.NO_ACCESS -> return listOf(warn(Frontend.IISU, Detail.IISU_FOLDER_NO_ACCESS, Repair.PICK_IISU_FOLDER))
            Folder.WRONG -> return listOf(warn(Frontend.IISU, Detail.IISU_WRONG_FOLDER, Repair.PICK_IISU_FOLDER))
            Folder.OK -> Unit
        }
        val out = mutableListOf(ok(Frontend.IISU, Detail.IISU_FOLDER_OK))
        if (!input.installed) out += warn(Frontend.IISU, Detail.IISU_NOT_INSTALLED)
        // Without the file the setup starts from the defaults inside the iiSU APK.
        val text = input.emuladores
            ?: return out + warn(Frontend.IISU, Detail.IISU_FILE_MISSING, if (input.installed) Repair.CONFIGURE_IISU else Repair.NONE)
        // A damaged file is refused by the setup too, so there is nothing to repair it with.
        val consoles = runCatching { iisuConsoles(text) }.getOrNull()
            ?: return out + warn(Frontend.IISU, Detail.IISU_FILE_CORRUPT)
        val matching = input.folders.map { it.lowercase() }.distinct().filter { it in consoles }.sorted()
        if (matching.isEmpty()) return out + warn(Frontend.IISU, Detail.IISU_NO_CONSOLES)
        out += counted(Frontend.IISU, matching, Detail.IISU_EMULATORS_OK, Detail.IISU_EMULATORS_MISSING, Repair.CONFIGURE_IISU) {
            hasDogmatix(consoles.getValue(it), input.component)
        }
        return out
    }

    /** Consoles of an `emuladores.json` by lower-case `shortName`; throws when it is not one. */
    private fun iisuConsoles(text: String): Map<String, JsonObject> {
        val root = JsonParser.parseString(text) as? JsonObject ?: throw IllegalArgumentException("not an object")
        val consoles = root.get("consoles") as? JsonArray ?: throw IllegalArgumentException("no consoles")
        val out = HashMap<String, JsonObject>()
        for (element in consoles) {
            val console = element as? JsonObject ?: continue
            val shortName = console.get("shortName")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            out.putIfAbsent(shortName.lowercase(), console)
        }
        return out
    }

    /** The console takes `.dgmtx` files and has a DOGMATIX emulator launching this build. */
    private fun hasDogmatix(console: JsonObject, component: String): Boolean {
        val extensions = (console.get("romExtensions") as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
        if (".${DgmtxFile.EXTENSION}" !in extensions) return false
        val emulators = (console.get("emulators") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        return emulators.any { emulator ->
            emulator.get("id")?.takeIf { it.isJsonPrimitive }?.asString == IisuJson.EMULATOR_ID &&
                (emulator.get("commands") as? JsonArray).orEmpty().any { command ->
                    ((command as? JsonObject)?.get("command")?.takeIf { it.isJsonPrimitive }?.asString ?: "").startsWith("$component ")
                }
        }
    }

    private fun JsonArray?.orEmpty(): List<com.google.gson.JsonElement> = this?.toList() ?: emptyList()

    // ---- Shortcuts ----------------------------------------------------------------------------

    /** A console with somewhere to download to (chip name), and whether its `.dgmtx` is there. */
    data class Shortcut(val console: String, val present: Boolean)

    /** [downloadFolderSet]: a download folder or a per-console one exists, so shortcuts have somewhere to go. */
    fun evaluateShortcuts(downloadFolderSet: Boolean, shortcuts: List<Shortcut>): Finding = when {
        shortcuts.isEmpty() && !downloadFolderSet -> warn(Frontend.SHORTCUTS, Detail.SHORTCUTS_NO_FOLDER)
        shortcuts.isEmpty() -> ok(Frontend.SHORTCUTS, Detail.SHORTCUTS_NO_CONSOLES)
        else -> counted(
            Frontend.SHORTCUTS, shortcuts.map { it.console }, Detail.SHORTCUTS_OK, Detail.SHORTCUTS_MISSING, Repair.DEPLOY_SHORTCUTS
        ) { name -> shortcuts.any { it.console == name && it.present } }
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private fun ok(frontend: Frontend, detail: Detail) = Finding(frontend, Status.OK, detail)

    private fun warn(frontend: Frontend, detail: Detail, repair: Repair = Repair.NONE, missing: List<String> = emptyList()) =
        Finding(frontend, Status.WARN, detail, repair, missing = missing)

    /** One finding for a list of things that each must pass [ready]: OK when all do, else a warning naming the rest. */
    private fun counted(
        frontend: Frontend,
        items: List<String>,
        okDetail: Detail,
        warnDetail: Detail,
        repair: Repair,
        ready: (String) -> Boolean
    ): Finding {
        val missing = items.filterNot(ready)
        return if (missing.isEmpty()) Finding(frontend, Status.OK, okDetail, total = items.size)
        else Finding(frontend, Status.WARN, warnDetail, repair, total = items.size, missing = missing)
    }
}
