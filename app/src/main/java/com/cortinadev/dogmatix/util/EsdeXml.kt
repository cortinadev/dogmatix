package com.cortinadev.dogmatix.util

/**
 * Text surgery on ES-DE's configuration files, so `EsdeConfigService` can add Dogmatix without
 * disturbing what the user already has: existing custom systems are patched in place, missing
 * ones are copied from the bundled definitions, and nothing is ever removed.
 *
 * Everything works on the XML as text (ES-DE's own files are stable, hand-written XML); pure
 * JVM so it can be unit-tested.
 */
object EsdeXml {

    const val EMULATOR_NAME = "DOGMATIX"

    /** Label of the Dogmatix `<command>`; `<altemulator>` entries refer to it. */
    const val COMMAND_LABEL = "Dogmatix"

    const val SHORTCUT_DESC = "Browse and download games for this platform with Dogmatix."

    private const val COMMAND =
        "<command label=\"$COMMAND_LABEL\">%EMULATOR_$EMULATOR_NAME% " +
            "%ACTION%=android.intent.action.VIEW %DATA%=%ROMPROVIDER%</command>"

    /**
     * `custom_systems/es_find_rules.xml` content with the DOGMATIX emulator registered for
     * [packageEntry] (`package/activity`), or null when [existing] already has it or cannot
     * be merged. Custom find rules complement the bundled ones, so other emulators are safe.
     */
    fun findRulesWithDogmatix(existing: String?, packageEntry: String): String? {
        if (existing != null && existing.contains("\"$EMULATOR_NAME\"")) return null
        val block = "    <emulator name=\"$EMULATOR_NAME\">\n" +
            "        <rule type=\"androidpackage\">\n" +
            "            <entry>$packageEntry</entry>\n" +
            "        </rule>\n" +
            "    </emulator>\n"
        if (existing == null) {
            return "<?xml version=\"1.0\"?>\n<!-- Added by Dogmatix -->\n<ruleList>\n$block</ruleList>\n"
        }
        val idx = existing.lastIndexOf("</ruleList>")
        if (idx < 0) return null
        return existing.substring(0, idx) + block + existing.substring(idx)
    }

    data class SystemsPatch(
        /** Full new file content (meaningful only when [changed]). */
        val content: String,
        /** Folder names that now have a system with .dgmtx + the Dogmatix command. */
        val configured: List<String>,
        /** Folder names with no matching system in the bundled or custom definitions. */
        val missing: List<String>,
        val changed: Boolean
    )

    /**
     * `custom_systems/es_systems.xml` with a Dogmatix-enabled `<system>` for every folder in
     * [folders]: a block already present in [existing] is patched in place (a custom block
     * *replaces* the bundled system, so it must be the one to gain `.dgmtx`); otherwise the
     * block is copied from [bundled] and appended.
     */
    fun patchCustomSystems(existing: String?, bundled: String, folders: Collection<String>): SystemsPatch {
        var content = existing
            ?: ("<?xml version=\"1.0\"?>\n" +
                "<!-- Systems with Dogmatix shortcuts. Added by Dogmatix; your edits are kept. -->\n" +
                "<systemList>\n</systemList>\n")
        val configured = mutableListOf<String>()
        val missing = mutableListOf<String>()
        var changed = false
        for (folder in folders) {
            val custom = systemBlock(content, folder)
            if (custom != null) {
                val patched = withDogmatix(custom)
                if (patched != custom) {
                    content = content.replace(custom, patched)
                    changed = true
                }
                configured.add(folder)
                continue
            }
            val fromBundled = systemBlock(bundled, folder)
            val end = content.lastIndexOf("</systemList>")
            if (fromBundled == null || end < 0) {
                missing.add(folder)
                continue
            }
            content = content.substring(0, end) + "    " + withDogmatix(fromBundled) + "\n" + content.substring(end)
            configured.add(folder)
            changed = true
        }
        return SystemsPatch(content, configured, missing, changed)
    }

    /**
     * Gamelist content with the shortcut's entry (name, description and
     * `<altemulator>Dogmatix</altemulator>`, so only the shortcut launches Dogmatix while the
     * system default stays with the real emulator). Null when the entry is already there or
     * [existing] cannot be merged.
     */
    fun gamelistWithShortcut(existing: String?, fileName: String, name: String): String? {
        val entry = "\t<game>\n" +
            "\t\t<path>./$fileName</path>\n" +
            "\t\t<name>$name</name>\n" +
            "\t\t<desc>$SHORTCUT_DESC</desc>\n" +
            "\t\t<altemulator>$COMMAND_LABEL</altemulator>\n" +
            "\t</game>\n"
        if (existing == null) return "<?xml version=\"1.0\"?>\n<gameList>\n$entry</gameList>\n"
        if (existing.contains("<path>./$fileName</path>")) return null
        val idx = existing.lastIndexOf("</gameList>")
        if (idx < 0) return null
        return existing.substring(0, idx) + entry + existing.substring(idx)
    }

    /** The `<system>` block whose `<name>` or `<path>` basename is [folder] (case-insensitive). */
    fun systemBlock(xml: String, folder: String): String? =
        systemBlocks(xml).firstOrNull { block ->
            tagText(block, "name")?.equals(folder, ignoreCase = true) == true ||
                tagText(block, "path")?.substringAfterLast('/')?.equals(folder, ignoreCase = true) == true
        }

    /** [block] with `.dgmtx` among the extensions and the Dogmatix command as the *last* one. */
    fun withDogmatix(block: String): String {
        var b = block
        if (!b.contains(".dgmtx")) b = b.replaceFirst("<extension>", "<extension>.dgmtx .DGMTX ")
        if (!b.contains("%EMULATOR_$EMULATOR_NAME%")) {
            val afterLastCommand = b.lastIndexOf("</command>")
            b = if (afterLastCommand >= 0) {
                val cut = afterLastCommand + "</command>".length
                b.substring(0, cut) + "\n        " + COMMAND + b.substring(cut)
            } else {
                val end = b.lastIndexOf("</system>")
                if (end < 0) return b
                b.substring(0, end) + "    " + COMMAND + "\n    " + b.substring(end)
            }
        }
        return b
    }

    private fun systemBlocks(xml: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = xml.indexOf("<system>", from)
            if (start < 0) break
            val end = xml.indexOf("</system>", start)
            if (end < 0) break
            out.add(xml.substring(start, end + "</system>".length))
            from = end
        }
        return out
    }

    private fun tagText(block: String, tag: String): String? {
        val start = block.indexOf("<$tag>")
        if (start < 0) return null
        val end = block.indexOf("</$tag>", start)
        if (end < 0) return null
        return block.substring(start + tag.length + 2, end).trim()
    }
}
