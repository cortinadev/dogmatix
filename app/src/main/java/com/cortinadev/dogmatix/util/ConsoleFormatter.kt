package com.cortinadev.dogmatix.util

object ConsoleFormatter {
    
    fun formatConsoleField(input: String): String {
        // Extract console name (everything after the first underscore)
        val consoleName = if (input.contains("_")) {
            input.substringAfter("_")
        } else {
            input
        }
        
        val formatted = consoleName
            .replace("_", " ")
            .let { StringUtils.capitalizeWords(it) }
        
        return splitLongConsoleName(formatted)
    }

    fun getConsoleFolderName(consoleId: String): String {
        val consoleName = if (consoleId.contains("_")) {
            consoleId.substringAfter("_")
        } else {
            consoleId
        }

        return consoleName.replace("_", " ").let { StringUtils.capitalizeWords(it) }
    }
    
    private fun splitLongConsoleName(name: String): String {
        val words = name.split(" ")
        
        // If 4 or more words, split into two lines
        if (words.size >= 4) {
            val midPoint = words.size / 2
            val firstLine = words.take(midPoint).joinToString(" ")
            val secondLine = words.drop(midPoint).joinToString(" ")
            return "$firstLine\n$secondLine"
        }
        
        // If 3 words and total length > 15, split after first word
        if (words.size == 3 && name.length > 15) {
            return "${words[0]}\n${words[1]} ${words[2]}"
        }
        
        return name
    }
    
    private val shortNames = mapOf(
        "nintendo_entertainment_system" to "NES",
        "super_nintendo_entertainment_system" to "SNES",
        "gameboy" to "GB",
        "gameboy_color" to "GBC",
        "gameboy_advance" to "GBA",
        "nintendo_64" to "N64",
        "nintendo_ds" to "NDS",
        "nintendo_3ds" to "3DS",
        "gamecube" to "GC",
        "playstation" to "PS1",
        "playstation_2" to "PS2",
        "playstation_portable" to "PSP",
        "master_system" to "SMS",
        "genesis" to "MD",
        "dreamcast" to "DC",
        "cd" to "SCD",
        "proper_romsets" to "Romsets"
    )

    /** Compact label for chips: the configured one, else a known abbreviation, else the folder name. */
    fun getConsoleShortName(consoleId: String): String =
        ConsoleAliasRegistry.shortNameFor(consoleId) ?: getDefaultShortName(consoleId)

    /** Built-in abbreviation for [consoleId] (what the app uses when none is configured). */
    fun getDefaultShortName(consoleId: String): String =
        shortNames[consoleKey(consoleId)] ?: getConsoleFolderName(consoleId)

    /**
     * Table key for [consoleId]. Ids are usually `manufacturer_console` (`nintendo_gameboy_advance`
     * → `gameboy_advance`), but imported sources may use the bare console name
     * (`super_nintendo_entertainment_system`); those are keys already and must not be trimmed,
     * or SNES would inherit NES's entry.
     */
    fun consoleKey(consoleId: String): String {
        val id = consoleId.lowercase()
        if (id in shortNames || ConsoleFolderAliases.hasDefaultsFor(id)) return id
        return id.substringAfter("_", id)
    }

    fun getConsoleDisplayName(consoleId: String): String {
        return formatConsoleField(consoleId)
    }
}
