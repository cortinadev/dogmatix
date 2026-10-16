package com.cortinadev.dogmatix.util

import java.util.Locale

/**
 * The library's short, stable list of genres (the Genre filter and the recommendations).
 * libretro-database, RAWG and TheGamesDB each name genres their own way; [fromName] folds their
 * spellings onto these. Stored as a bit mask in `game_traits.genres`, so [bit] must never change.
 * [rawgSlug] is the RAWG genre asked for "popular on your platforms". Pure JVM for the tests.
 */
enum class LibraryGenre(val bit: Int, val rawgSlug: String?) {
    ACTION(0, "action"),
    ADVENTURE(1, "adventure"),
    PLATFORM(2, "platformer"),
    RPG(3, "role-playing-games-rpg"),
    SHOOTER(4, "shooter"),
    FIGHTING(5, "fighting"),
    RACING(6, "racing"),
    SPORTS(7, "sports"),
    PUZZLE(8, "puzzle"),
    STRATEGY(9, "strategy"),
    SIMULATION(10, "simulation"),
    BOARD(11, "board-games"),
    MUSIC(12, null),
    OTHER(13, null);

    val mask: Int get() = 1 shl bit

    companion object {
        // Lower-case spellings (without "(…)" nor a trailing "game(s)") of the three databases.
        private val aliases: Map<String, LibraryGenre> = buildMap {
            fun add(genre: LibraryGenre, vararg names: String) = names.forEach { put(it, genre) }
            LibraryGenre.entries.forEach { put(it.name.lowercase(Locale.ROOT), it) }
            add(ACTION, "beat'em up", "beat 'em up", "beat em up", "arcade", "hack and slash", "stealth")
            add(ADVENTURE, "point and click", "point-and-click", "visual novel", "interactive fiction", "horror")
            add(PLATFORM, "platformer")
            add(RPG, "role-playing", "role playing", "mmo", "mmorpg", "massively multiplayer")
            add(SHOOTER, "shoot'em up", "shoot 'em up", "shmup", "lightgun shooter", "light gun shooter", "first-person shooter", "fps")
            add(RACING, "driving", "vehicle simulation")
            add(SPORTS, "sport", "sports with animals", "hunting and fishing")
            add(PUZZLE, "thinking", "quiz", "trivia")
            add(STRATEGY, "tactics", "tactical", "real-time strategy")
            add(SIMULATION, "life simulation", "flight simulator", "construction and management simulation", "sandbox")
            add(BOARD, "board", "card", "gambling", "casino", "pinball")
            add(MUSIC, "music / dancing", "rhythm", "dancing")
            add(OTHER, "compilation", "miscellaneous", "various", "educational", "education", "adult", "casual", "family", "party")
        }

        private val parenthesised = Regex("\\([^)]*\\)")
        private val spaces = Regex("\\s+")

        /** The genre a database calls [raw] ("Role-playing (RPG)", "Platformer", "Board Games"…); null when it names none ("Indie", "Demo"). */
        fun fromName(raw: String): LibraryGenre? {
            val name = raw.replace(parenthesised, " ").replace('’', '\'').replace(spaces, " ").trim().lowercase(Locale.ROOT)
            if (name.isEmpty()) return null
            return aliases[name] ?: aliases[name.removeSuffix(" games").removeSuffix(" game").trim()]
        }

        /** Mask of every genre among [names] (database spellings). */
        fun maskOf(names: Iterable<String>): Int = names.mapNotNull(::fromName).fold(0) { mask, genre -> mask or genre.mask }

        fun maskOfGenres(genres: Collection<LibraryGenre>): Int = genres.fold(0) { mask, genre -> mask or genre.mask }

        /** The genres a stored mask holds, in list order. */
        fun inMask(mask: Int): List<LibraryGenre> = entries.filter { mask and it.mask != 0 }

        /** A deep-link value: a genre id (`rpg`) or any spelling [fromName] knows. */
        fun resolve(value: String): LibraryGenre? =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: fromName(value)
    }
}

/** Release years and decades for the Decade filter. */
object LibraryDecades {

    /** The decades the filter offers (games before 1970 are not a thing on these consoles). */
    val options: List<Int> = listOf(1970, 1980, 1990, 2000, 2010, 2020)

    /** The year a date starts with ("1996", "1996-06-23"); null outside 1950..2100. */
    fun parseYear(text: String?): Int? {
        val digits = text?.trim()?.take(4) ?: return null
        if (digits.length != 4 || !digits.all { it.isDigit() }) return null
        return digits.toInt().takeIf { it in 1950..2100 }
    }

    fun decadeOf(year: Int): Int = year / 10 * 10

    /** Two digits of a decade, "80" for 1980, "00" for 2000 (short labels). */
    fun twoDigits(decade: Int): String = (decade % 100).toString().padStart(2, '0')

    /**
     * A deep-link value: `1990`, `1990s`, `1995` (its decade), `90s`, `'90s` or `90`; two digits
     * below 50 are this century. Null when it is none of those.
     */
    fun parseDecade(value: String): Int? {
        val text = value.trim().lowercase(Locale.ROOT).trimStart('\'', '’').removeSuffix("s")
        if (text.isEmpty() || !text.all { it.isDigit() }) return null
        return when (text.length) {
            2 -> text.toInt().let { if (it < 50) 2000 + it else 1900 + it }.let(::decadeOf)
            4 -> parseYear(text)?.let(::decadeOf)
            else -> null
        }
    }
}
