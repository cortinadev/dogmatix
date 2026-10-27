package com.cortinadev.dogmatix.util

/**
 * Built-in catalogue of manufacturers and consoles offered by the "Add console" dialog, so a
 * console can be added with a couple of presses instead of typing its name, chip label and
 * folder aliases. Pure JVM.
 *
 * [KnownConsole.key] is the console part of the id (`nintendo_` + `gameboy_advance`): the ids
 * follow the original `consoles.json` where it had the console, so the built-in tables
 * ([ConsoleFormatter], [ConsoleFolderAliases], [RommPlatformMapper], [LibretroThumbnails]) keep
 * matching; the rest use the words [LibretroThumbnails] and [ConsoleFamily] already recognise.
 * The aliases start with the folder names ES-DE uses, then other frontends' spellings.
 */
object KnownConsoles {

    data class KnownConsole(val key: String, val name: String, val shortName: String, val aliases: List<String>)

    data class KnownManufacturer(val name: String, val consoles: List<KnownConsole>) {
        /** Same id [SourcesRepository.addManufacturer] gives a manufacturer with this name. */
        val id: String = SourcesJson.slug(name)
    }

    private fun c(key: String, name: String, shortName: String, vararg aliases: String) =
        KnownConsole(key, name, shortName, aliases.toList())

    val manufacturers: List<KnownManufacturer> = listOf(
        KnownManufacturer("Nintendo", listOf(
            c("nintendo_entertainment_system", "Nintendo Entertainment System", "NES", "nes", "famicom", "fc"),
            c("famicom_disk_system", "Famicom Disk System", "FDS", "fds", "famicomdisksystem"),
            c("super_nintendo_entertainment_system", "Super Nintendo Entertainment System", "SNES", "snes", "sfc", "superfamicom", "supernintendo"),
            c("nintendo_64", "Nintendo 64", "N64", "n64", "nintendo64"),
            c("gamecube", "GameCube", "GC", "gc", "ngc", "gamecube"),
            c("wii", "Wii", "Wii", "wii"),
            c("gameboy", "Game Boy", "GB", "gb", "gameboy", "game boy"),
            c("gameboy_color", "Game Boy Color", "GBC", "gbc", "gameboycolor", "game boy color"),
            c("gameboy_advance", "Game Boy Advance", "GBA", "gba", "gameboyadvance", "game boy advance"),
            c("nintendo_ds", "Nintendo DS", "NDS", "nds", "ds"),
            c("nintendo_3ds", "Nintendo 3DS", "3DS", "3ds", "n3ds"),
            c("virtual_boy", "Virtual Boy", "VB", "virtualboy", "vb"),
            c("pokemon_mini", "Pokémon Mini", "PokeMini", "pokemini", "pokemonmini")
        )),
        KnownManufacturer("Sony", listOf(
            c("playstation", "PlayStation", "PS1", "psx", "ps1", "ps", "playstation"),
            c("playstation_2", "PlayStation 2", "PS2", "ps2", "playstation2"),
            c("playstation_3", "PlayStation 3", "PS3", "ps3", "playstation3"),
            c("playstation_portable", "PlayStation Portable", "PSP", "psp"),
            c("playstation_vita", "PlayStation Vita", "Vita", "psvita", "vita")
        )),
        KnownManufacturer("Sega", listOf(
            c("sg_1000", "SG-1000", "SG-1000", "sg-1000", "sg1000"),
            c("master_system", "Master System", "SMS", "mastersystem", "sms", "segamastersystem"),
            c("genesis", "Mega Drive / Genesis", "MD", "genesis", "megadrive", "md", "segagenesis", "segamegadrive"),
            c("cd", "Mega-CD / Sega CD", "SCD", "segacd", "megacd"),
            c("32x", "32X", "32X", "sega32x", "32x"),
            c("saturn", "Saturn", "SAT", "saturn", "segasaturn"),
            c("dreamcast", "Dreamcast", "DC", "dreamcast", "dc", "segadreamcast"),
            c("game_gear", "Game Gear", "GG", "gamegear", "gg")
        )),
        KnownManufacturer("Microsoft", listOf(
            c("xbox", "Xbox", "Xbox", "xbox"),
            c("msx", "MSX", "MSX", "msx"),
            c("msx2", "MSX2", "MSX2", "msx2")
        )),
        KnownManufacturer("NEC", listOf(
            c("pc_engine", "PC Engine / TurboGrafx-16", "PCE", "pcengine", "tg16", "turbografx16"),
            c("pc_engine_cd", "PC Engine CD / TurboGrafx-CD", "PCE-CD", "pcenginecd", "tg-cd", "turbografxcd"),
            c("supergrafx", "SuperGrafx", "SGX", "supergrafx", "sgfx"),
            c("pc_fx", "PC-FX", "PC-FX", "pcfx")
        )),
        KnownManufacturer("SNK", listOf(
            c("neo_geo", "Neo Geo", "NG", "neogeo", "neogeoaes", "neogeomvs"),
            c("neo_geo_cd", "Neo Geo CD", "NGCD", "neogeocd"),
            c("neo_geo_pocket", "Neo Geo Pocket", "NGP", "ngp"),
            c("neo_geo_pocket_color", "Neo Geo Pocket Color", "NGPC", "ngpc")
        )),
        KnownManufacturer("Atari", listOf(
            c("atari_2600", "Atari 2600", "2600", "atari2600"),
            c("atari_5200", "Atari 5200", "5200", "atari5200"),
            c("atari_7800", "Atari 7800", "7800", "atari7800"),
            c("lynx", "Lynx", "Lynx", "atarilynx", "lynx"),
            c("jaguar", "Jaguar", "Jaguar", "atarijaguar", "jaguar")
        )),
        KnownManufacturer("Bandai", listOf(
            c("wonderswan", "WonderSwan", "WS", "wonderswan", "ws"),
            c("wonderswan_color", "WonderSwan Color", "WSC", "wonderswancolor", "wsc")
        )),
        KnownManufacturer("Coleco", listOf(
            c("colecovision", "ColecoVision", "CV", "colecovision", "coleco")
        )),
        KnownManufacturer("Mattel", listOf(
            c("intellivision", "Intellivision", "INTV", "intellivision")
        )),
        KnownManufacturer("3DO", listOf(
            c("3do", "3DO Interactive Multiplayer", "3DO", "3do")
        )),
        KnownManufacturer("Commodore", listOf(
            c("commodore_64", "Commodore 64", "C64", "c64", "commodore64"),
            c("amiga", "Amiga", "Amiga", "amiga")
        )),
        KnownManufacturer("Sinclair", listOf(
            c("zx_spectrum", "ZX Spectrum", "ZX", "zxspectrum", "spectrum")
        )),
        KnownManufacturer("Amstrad", listOf(
            c("cpc", "Amstrad CPC", "CPC", "amstradcpc", "cpc")
        )),
        KnownManufacturer("Arcade", listOf(
            c("mame", "MAME", "MAME", "mame", "arcade"),
            c("fbneo", "FinalBurn Neo", "FBNeo", "fbneo", "fba")
        )),
        KnownManufacturer("PC", listOf(
            c("dos", "DOS", "DOS", "dos", "pc"),
            c("scummvm", "ScummVM", "ScummVM", "scummvm")
        ))
    )

    /** The known manufacturer with this id (`SourcesJson.slug` of its name), or null. */
    fun manufacturer(id: String): KnownManufacturer? = manufacturers.firstOrNull { it.id == id }

    /** Known consoles of the manufacturer with this id; empty for an unknown one. */
    fun consolesOf(manufacturerId: String): List<KnownConsole> = manufacturer(manufacturerId)?.consoles.orEmpty()

    /** Full console id a known console gets under [manufacturerId]. */
    fun idOf(manufacturerId: String, console: KnownConsole): String = "${manufacturerId}_${console.key}"

    /**
     * Known consoles of [manufacturerId] not yet present, judged by id, by the table key of the
     * existing id (an imported bare `super_nintendo_entertainment_system` counts) and by name.
     */
    fun missing(manufacturerId: String, existingConsoles: List<Pair<String, String>>): List<KnownConsole> {
        val ids = existingConsoles.map { it.first.lowercase() }.toSet()
        val keys = ids.map { ConsoleFormatter.consoleKey(it) }.toSet()
        val names = existingConsoles.map { it.second.trim().lowercase() }.toSet()
        return consolesOf(manufacturerId).filter { console ->
            idOf(manufacturerId, console) !in ids && console.key !in keys && console.name.lowercase() !in names
        }
    }
}
