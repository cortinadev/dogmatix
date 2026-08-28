package com.cortinadev.dogmatix.util

/** Per-console overrides of the short label and folder aliases, as stored with the console. */
data class ConsoleAliasInfo(val shortName: String, val folderAliases: List<String>)

/**
 * Process-wide view of the short names / folder aliases saved on each console row, so the
 * static helpers ([ConsoleFormatter.getConsoleShortName], [ConsoleFolderAliases]) can honour
 * what the user configured or imported without every call site needing the database.
 * Fed from Room by the application (see `DogmatixApplication`); empty values fall back to the
 * built-in tables.
 */
object ConsoleAliasRegistry {
    @Volatile
    var overrides: Map<String, ConsoleAliasInfo> = emptyMap()

    fun shortNameFor(consoleId: String): String? = overrides[consoleId]?.shortName?.takeIf { it.isNotBlank() }
    fun aliasesFor(consoleId: String): List<String> = overrides[consoleId]?.folderAliases.orEmpty()

    /** Aliases stored as one comma-separated column. */
    fun parseAliases(column: String): List<String> =
        column.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun serializeAliases(aliases: List<String>): String =
        aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
}
