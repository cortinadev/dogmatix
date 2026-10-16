package com.cortinadev.dogmatix.util

/**
 * What RAWG calls genres and platforms, for "similar games of the same type": the genre names
 * the details lookup stores (RAWG's own, or TheGamesDB's) map to a RAWG genre slug, and library
 * consoles map to RAWG platform ids. Pure JVM for the tests.
 */
object RawgCatalog {

    private data class Genre(val slug: String, val names: List<String>, val specific: Boolean)

    // `names`: RAWG's name first, then TheGamesDB's equivalents (as stored in game_metadata).
    private val genres = listOf(
        Genre("platformer", listOf("Platformer", "Platform"), true),
        Genre("fighting", listOf("Fighting"), true),
        Genre("racing", listOf("Racing", "Vehicle Simulation"), true),
        Genre("shooter", listOf("Shooter"), true),
        Genre("role-playing-games-rpg", listOf("RPG", "Role-Playing"), true),
        Genre("puzzle", listOf("Puzzle", "Quiz"), true),
        Genre("sports", listOf("Sports"), true),
        Genre("strategy", listOf("Strategy"), true),
        Genre("simulation", listOf("Simulation", "Life Simulation", "Flight Simulator", "Construction and Management Simulation"), true),
        Genre("board-games", listOf("Board Games", "Board"), true),
        Genre("card", listOf("Card"), true),
        Genre("educational", listOf("Educational", "Education"), true),
        Genre("massively-multiplayer", listOf("Massively Multiplayer", "MMO"), true),
        // Broad ones: used only when the game has nothing more specific.
        Genre("adventure", listOf("Adventure"), false),
        Genre("action", listOf("Action"), false),
        Genre("arcade", listOf("Arcade"), false),
        Genre("family", listOf("Family", "Party"), false),
        Genre("casual", listOf("Casual"), false),
        Genre("indie", listOf("Indie"), false)
    )

    private fun genreOf(name: String): Genre? = genres.firstOrNull { g -> g.names.any { it.equals(name.trim(), ignoreCase = true) } }

    /** The genre that best describes a game: the first specific one (platformer before action). */
    fun pickGenre(names: List<String>): String? {
        val known = names.mapNotNull(::genreOf)
        return (known.firstOrNull { it.specific } ?: known.firstOrNull())?.slug
    }

    /** Every name a stored genre may have for [slug] (RAWG's and TheGamesDB's). */
    fun genreNames(slug: String): List<String> = genres.firstOrNull { it.slug == slug }?.names.orEmpty()

    private data class Platform(val id: Int, val words: List<String>, val excludes: List<String> = emptyList())

    private val platforms = listOf(
        Platform(49, listOf("nes", "famicom", "nintendo_entertainment_system")),
        Platform(79, listOf("snes", "sfc", "super_famicom", "super_nintendo", "super_nintendo_entertainment_system")),
        Platform(83, listOf("n64", "nintendo_64")),
        Platform(105, listOf("gamecube", "gc", "ngc")),
        Platform(11, listOf("wii"), excludes = listOf("wii_u", "wiiu")),
        Platform(10, listOf("wii_u", "wiiu")),
        Platform(7, listOf("switch")),
        Platform(26, listOf("gb", "gameboy", "game_boy")),
        Platform(43, listOf("gbc", "gameboy_color", "game_boy_color")),
        Platform(24, listOf("gba", "gameboy_advance", "game_boy_advance")),
        Platform(9, listOf("nds", "nintendo_ds")),
        Platform(8, listOf("3ds", "nintendo_3ds")),
        Platform(27, listOf("psx", "ps1", "playstation")),
        Platform(15, listOf("ps2", "playstation_2")),
        Platform(16, listOf("ps3", "playstation_3")),
        Platform(17, listOf("psp", "playstation_portable")),
        Platform(19, listOf("vita", "ps_vita", "playstation_vita")),
        Platform(74, listOf("sms", "master_system", "mastersystem")),
        Platform(167, listOf("md", "genesis", "mega_drive", "megadrive")),
        Platform(119, listOf("scd", "mega_cd", "megacd", "sega_cd", "segacd")),
        Platform(117, listOf("32x")),
        Platform(107, listOf("saturn")),
        Platform(106, listOf("dreamcast", "dc")),
        Platform(77, listOf("gg", "game_gear", "gamegear")),
        Platform(80, listOf("xbox")),
        Platform(14, listOf("xbox_360", "x360")),
        Platform(23, listOf("2600", "atari_2600")),
        Platform(28, listOf("7800", "atari_7800")),
        Platform(46, listOf("lynx", "atari_lynx")),
        Platform(112, listOf("jaguar", "atari_jaguar")),
        Platform(111, listOf("3do")),
        Platform(12, listOf("neo_geo", "neogeo"), excludes = listOf("neo_geo_pocket", "ngp", "ngpc"))
    )

    /** RAWG platform id of a library console (longest matching word wins), or null. */
    fun platformFor(consoleId: String): Int? {
        val id = ConsoleIdMatch.normalize(consoleId)
        return platforms
            .filterNot { p -> p.excludes.any { ConsoleIdMatch.has(id, it) } }
            .map { it to ConsoleIdMatch.longestMatch(id, it.words) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first?.id
    }
}
