package com.cortinadev.dogmatix.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Surgery on iiSU's `emuladores.json`, so `IisuConfigService` can add Dogmatix as one more
 * emulator without disturbing the consoles and emulators the user already has: only the
 * consoles whose folder we deploy a shortcut into are touched, and inside them only the
 * `.dgmtx` extension and a `DOGMATIX` entry are added. Nothing is ever removed.
 *
 * The document (see the commented `assets/iiSu/emuladores_default.jsonc` inside the iiSU APK;
 * the actual defaults are the plain-JSON `assets/emuladores_default.json`):
 * ```
 * { "consoles": [ { "shortName": "xbox", "longName": "Microsoft Xbox",
 *                   "romExtensions": [".iso", ".ISO"],
 *                   "emulators": [ { "id": "X1-BOX", "name": "X1 BOX (Standalone)",
 *                                    "routeType": "uri",
 *                                    "commands": [ { "description": "…", "command": "…" } ],
 *                                    "packages": ["com.izzy2lost.x1box"] } ] } ] }
 * ```
 * `shortName` doubles as the console's folder name, which is what ties a console to the folder
 * a Dogmatix shortcut was written into.
 *
 * Pure JVM (Gson only, no Android types) so it can be unit-tested.
 */
object IisuJson {

    /** Id of the emulator entry Dogmatix adds; also how an existing entry is recognised. */
    const val EMULATOR_ID = "DOGMATIX"

    /** Name iiSU shows in the emulator picker. */
    private const val EMULATOR_NAME = "Dogmatix"

    /** Label of the launch command inside the entry. */
    private const val COMMAND_DESCRIPTION = "Search for more games"

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    data class Patch(
        /** Full document: re-serialized when [changed], byte-identical to the input otherwise. */
        val content: String,
        /** Folder names that now have a console accepting `.dgmtx` with the Dogmatix command. */
        val configured: List<String>,
        /** Folder names with no matching console in the document. */
        val missing: List<String>,
        val changed: Boolean
    )

    /**
     * [existing] with a Dogmatix emulator on every console whose `shortName` is in [folders].
     *
     * [component] is the launch target as `package/fully.qualified.Activity`. It must be
     * spelled out in full: iiSU rebuilds the class from the chosen package when the command
     * uses `%PACKAGE%`, which breaks any build whose application id is not the class's package.
     *
     * Throws on a document that is not an `emuladores.json` — malformed JSON (Gson's
     * `JsonSyntaxException`), a non-object root, a missing `consoles` list, or a list-valued
     * member of the wrong type ([IllegalArgumentException]). Callers must treat that as "the
     * file is damaged" and write nothing.
     */
    fun patchEmuladores(existing: String, folders: Collection<String>, component: String): Patch {
        val root = JsonParser.parseString(existing) as? JsonObject
            ?: throw IllegalArgumentException("not a JSON object")
        val consoles = root.get("consoles") as? JsonArray
            ?: throw IllegalArgumentException("no 'consoles' list")

        val byFolder = HashMap<String, JsonObject>()
        for (element in consoles) {
            val console = element as? JsonObject ?: continue
            val shortName = console.get("shortName")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            byFolder.putIfAbsent(shortName.lowercase(), console)
        }

        // One template for the whole pass; deep-copied wherever it actually lands in the tree
        // (Gson elements are mutable, sharing one instance across consoles would alias them).
        val entry = dogmatixEmulator(component)
        val configured = mutableListOf<String>()
        val missing = mutableListOf<String>()
        var changed = false
        for (folder in folders) {
            val console = byFolder[folder.lowercase()]
            if (console == null) {
                missing.add(folder)
                continue
            }
            if (addDgmtxExtension(console)) changed = true
            if (setDogmatixEmulator(console, entry)) changed = true
            configured.add(folder)
        }
        // Serializing would reflow the document (and drop anything Gson's lenient parser merely
        // tolerated, like comments), so an untouched document is returned exactly as it came.
        return Patch(if (changed) gson.toJson(root) else existing, configured, missing, changed)
    }

    /** Adds `.dgmtx`/`.DGMTX` to the console's `romExtensions`. True when it changed anything. */
    private fun addDgmtxExtension(console: JsonObject): Boolean {
        val extensions = console.listMember("romExtensions")
        val present = extensions.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.toSet()
        var changed = false
        // iiSU matches extensions case-sensitively, so both spellings have to be listed.
        for (extension in listOf(".${DgmtxFile.EXTENSION}", ".${DgmtxFile.EXTENSION.uppercase()}")) {
            if (extension !in present) {
                extensions.add(extension)
                changed = true
            }
        }
        return changed
    }

    /**
     * Adds (or refreshes) the Dogmatix [entry] in the console's `emulators`. It goes last so
     * the console keeps whichever emulator it had as its first choice; iiSU's per-ROM "Override
     * Emulator" is what points the shortcut itself at Dogmatix.
     */
    private fun setDogmatixEmulator(console: JsonObject, entry: JsonObject): Boolean {
        val emulators = console.listMember("emulators")
        val index = emulators.indexOfFirst {
            (it as? JsonObject)?.get("id")?.takeIf { id -> id.isJsonPrimitive }?.asString == EMULATOR_ID
        }
        return when {
            index < 0 -> {
                emulators.add(entry.deepCopy())
                true
            }
            emulators[index] == entry -> false
            else -> {
                emulators.set(index, entry.deepCopy())
                true
            }
        }
    }

    /**
     * The member as a mutable list. An absent or `null` member becomes an empty list (both are
     * how "none" is spelled in the wild); any other type means the document is damaged.
     */
    private fun JsonObject.listMember(name: String): JsonArray = when (val member = get(name)) {
        null, is JsonNull -> JsonArray().also { add(name, it) }
        is JsonArray -> member
        else -> throw IllegalArgumentException("'$name' is not a list")
    }

    private fun dogmatixEmulator(component: String): JsonObject = JsonObject().apply {
        addProperty("id", EMULATOR_ID)
        addProperty("name", EMULATOR_NAME)
        // "uri" hands us %ROM_URI%, the content:// URI iiSU already holds a read grant for and
        // passes on with FLAG_GRANT_READ_URI_PERMISSION; "path" would need raw file access.
        addProperty("routeType", "uri")
        add("commands", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("description", COMMAND_DESCRIPTION)
                addProperty("command", "$component -a android.intent.action.VIEW -d %ROM_URI%")
            })
        })
        add("packages", JsonArray().apply { add(component.substringBefore('/')) })
    }
}
