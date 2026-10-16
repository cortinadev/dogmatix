package com.cortinadev.dogmatix.util

import java.net.URLEncoder

/**
 * Genre and release year of whole systems from libretro-database, which needs no API key:
 * `metadat/genre/<System>.dat` and `metadat/releaseyear/<System>.dat` are clrmamepro DATs with
 * one `game ( comment "<No-Intro name>" genre "…" … )` block per release. Only cartridge systems
 * (and the PSP) have them; disc systems fall back to the details lookups. The system is the
 * libretro-thumbnails one ([LibretroThumbnails.systemFor]) with spaces instead of `_`.
 * Pure JVM for the tests; the download is `GameTraitsService`.
 */
object LibretroMetadat {

    private const val BASE = "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/"

    enum class Field(val folder: String, val key: String) {
        GENRE("genre", "genre"),
        YEAR("releaseyear", "releaseyear")
    }

    /** `Nintendo_-_Super_Nintendo_Entertainment_System` → `Nintendo - Super Nintendo Entertainment System`. */
    fun databaseName(system: LibretroThumbnails.System): String = system.repo.replace('_', ' ')

    fun url(system: LibretroThumbnails.System, field: Field): String =
        BASE + field.folder + "/" + URLEncoder.encode(databaseName(system) + ".dat", "UTF-8").replace("+", "%20")

    /** (No-Intro name, value of [field]) of every `game ( … )` block that has both. */
    fun parse(lines: Sequence<String>, field: Field): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var inGame = false
        var name: String? = null
        var value: String? = null
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("game (") || line == "game" -> { inGame = true; name = null; value = null }
                !inGame -> Unit
                line == ")" -> {
                    if (name != null && value != null) out += name to value
                    inGame = false
                }
                line.startsWith("comment ") -> name = quoted(line) ?: name
                line.startsWith("name ") && name == null -> name = quoted(line)
                line.startsWith(field.key + " ") -> value = quoted(line)
            }
        }
        return out
    }

    private fun quoted(line: String): String? {
        val start = line.indexOf('"')
        val end = line.lastIndexOf('"')
        return if (start >= 0 && end > start) line.substring(start + 1, end).trim().takeIf { it.isNotEmpty() } else null
    }

    /** What the DATs say about one game: [LibraryGenre] bits (0 = unknown) and the first release year. */
    data class Trait(val genres: Int, val year: Int?)

    /**
     * Per library game, keyed like the index groups the console's files ([groupKeyOf]): the
     * genres of all its releases OR-ed and the earliest year.
     */
    fun traitsByGroup(genres: List<Pair<String, String>>, years: List<Pair<String, String>>): Map<String, Trait> {
        val masks = HashMap<String, Int>()
        genres.forEach { (name, genre) ->
            val bit = LibraryGenre.fromName(genre)?.mask ?: return@forEach
            masks.merge(groupKeyOf(name), bit, Int::or)
        }
        val firstYears = HashMap<String, Int>()
        years.forEach { (name, text) ->
            val year = LibraryDecades.parseYear(text) ?: return@forEach
            firstYears.merge(groupKeyOf(name), year, ::minOf)
        }
        return (masks.keys + firstYears.keys).associateWith { Trait(masks[it] ?: 0, firstYears[it]) }
    }

    /** The library's group key for a DAT name, computed the way the indexer does it for a file name. */
    fun groupKeyOf(datName: String): String = VersionGrouping.groupKey(FileParsingUtils.extractNameAndTags(datName).first)
}
