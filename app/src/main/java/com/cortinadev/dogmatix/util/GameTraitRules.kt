package com.cortinadev.dogmatix.util

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Genre and Decade as rule fields of smart collections: kept in [CollectionRules.extras] as JSON
 * arrays ([LibraryGenre] names, decades such as 1990) and turned into the same `game_traits`
 * conditions as the library's Genre / Decade filters. A game without traits never passes.
 */
object GameTraitRules {
    const val GENRES = "genres"
    const val DECADES = "decades"

    fun genresOf(rules: CollectionRules): Set<LibraryGenre> = decodeGenres(rules.extras[GENRES])

    fun decadesOf(rules: CollectionRules): Set<Int> = decodeDecades(rules.extras[DECADES])

    /** [rules] with these genres / decades (an empty set drops the field). */
    fun withGenres(rules: CollectionRules, genres: Set<LibraryGenre>): CollectionRules =
        rules.copy(extras = put(rules.extras, GENRES, genres.map { it.name }.sorted().takeIf { it.isNotEmpty() }?.let { names ->
            JsonArray().apply { names.forEach(::add) }.toString()
        }))

    fun withDecades(rules: CollectionRules, decades: Set<Int>): CollectionRules =
        rules.copy(extras = put(rules.extras, DECADES, decades.sorted().takeIf { it.isNotEmpty() }?.let { values ->
            JsonArray().apply { values.forEach(::add) }.toString()
        }))

    fun genreCondition(raw: String): SqlCondition? {
        val mask = LibraryGenre.maskOfGenres(decodeGenres(raw)).takeIf { it != 0 } ?: return null
        return SqlCondition(
            "EXISTS (SELECT 1 FROM game_traits g_genre WHERE g_genre.consoleId = df.consoleId AND g_genre.groupKey = df.groupKey AND (g_genre.genres & ?) != 0)",
            listOf(mask)
        )
    }

    fun decadeCondition(raw: String): SqlCondition? {
        val decades = decodeDecades(raw).sorted().takeIf { it.isNotEmpty() } ?: return null
        return SqlCondition(
            "EXISTS (SELECT 1 FROM game_traits g_decade WHERE g_decade.consoleId = df.consoleId AND g_decade.groupKey = df.groupKey " +
                "AND (g_decade.year / 10) * 10 IN (${decades.joinToString(",") { "?" }}))",
            decades
        )
    }

    fun decodeGenres(raw: String?): Set<LibraryGenre> = array(raw)
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.asString }
        .mapNotNull(LibraryGenre::resolve)
        .toSet()

    fun decodeDecades(raw: String?): Set<Int> = array(raw)
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isNumber }?.asInt }
        .filter { it in LibraryDecades.options }
        .toSet()

    private fun array(raw: String?): List<com.google.gson.JsonElement> =
        runCatching { JsonParser.parseString(raw ?: return emptyList()) as? JsonArray }.getOrNull()?.toList().orEmpty()

    private fun put(extras: Map<String, String>, key: String, value: String?): Map<String, String> =
        if (value == null) extras - key else extras + (key to value)
}
