package com.cortinadev.dogmatix.util

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Box art from the libretro-thumbnails repositories on GitHub (what RetroArch shows), which needs
 * no API key: covers are files named after the No-Intro / Redump name of the game, so a well-named
 * ROM leads straight to its URL. Pure JVM for the tests; the network side is `ThumbnailService`.
 */
object LibretroThumbnails {

    /** One libretro-thumbnails repository and the console-id words that select it. */
    data class System(val repo: String, val words: List<String>, val excludes: List<String> = emptyList())

    val systems: List<System> = listOf(
        System("Nintendo_-_Nintendo_Entertainment_System", listOf("nes", "famicom", "nintendo_entertainment_system")),
        System("Nintendo_-_Family_Computer_Disk_System", listOf("fds", "famicom_disk_system")),
        System("Nintendo_-_Super_Nintendo_Entertainment_System", listOf("snes", "sfc", "super_famicom", "super_nintendo", "super_nintendo_entertainment_system")),
        System("Nintendo_-_Nintendo_64", listOf("n64", "nintendo_64")),
        System("Nintendo_-_GameCube", listOf("gamecube", "gc", "ngc")),
        System("Nintendo_-_Wii", listOf("wii"), excludes = listOf("wii_u", "wiiu")),
        System("Nintendo_-_Game_Boy", listOf("gb", "gameboy", "game_boy")),
        System("Nintendo_-_Game_Boy_Color", listOf("gbc", "gameboy_color", "game_boy_color")),
        System("Nintendo_-_Game_Boy_Advance", listOf("gba", "gameboy_advance", "game_boy_advance")),
        System("Nintendo_-_Nintendo_DS", listOf("nds", "nintendo_ds")),
        System("Nintendo_-_Nintendo_3DS", listOf("3ds", "nintendo_3ds")),
        System("Nintendo_-_Virtual_Boy", listOf("virtual_boy", "virtualboy")),
        System("Nintendo_-_Pokemon_Mini", listOf("pokemon_mini", "pokemini")),
        System("Sega_-_Master_System_-_Mark_III", listOf("sms", "master_system", "mastersystem")),
        System("Sega_-_Mega_Drive_-_Genesis", listOf("md", "genesis", "mega_drive", "megadrive")),
        System("Sega_-_Mega-CD_-_Sega_CD", listOf("scd", "mega_cd", "megacd", "sega_cd", "segacd")),
        System("Sega_-_32X", listOf("32x")),
        System("Sega_-_Saturn", listOf("saturn")),
        System("Sega_-_Dreamcast", listOf("dreamcast", "dc")),
        System("Sega_-_Game_Gear", listOf("gg", "game_gear", "gamegear")),
        System("Sega_-_SG-1000", listOf("sg_1000", "sg1000")),
        System("Sony_-_PlayStation", listOf("psx", "ps1", "playstation")),
        System("Sony_-_PlayStation_2", listOf("ps2", "playstation_2")),
        System("Sony_-_PlayStation_Portable", listOf("psp", "playstation_portable")),
        System("NEC_-_PC_Engine_-_TurboGrafx_16", listOf("pce", "pc_engine", "pcengine", "turbografx_16", "tg16")),
        System("NEC_-_PC_Engine_CD_-_TurboGrafx-CD", listOf("pce_cd", "pc_engine_cd", "turbografx_cd")),
        System("SNK_-_Neo_Geo_Pocket", listOf("ngp", "neo_geo_pocket")),
        System("SNK_-_Neo_Geo_Pocket_Color", listOf("ngpc", "neo_geo_pocket_color")),
        System("Bandai_-_WonderSwan", listOf("ws", "wonderswan")),
        System("Bandai_-_WonderSwan_Color", listOf("wsc", "wonderswan_color")),
        System("Atari_-_2600", listOf("2600", "atari_2600")),
        System("Atari_-_7800", listOf("7800", "atari_7800")),
        System("Atari_-_Lynx", listOf("lynx", "atari_lynx")),
        System("Atari_-_Jaguar", listOf("jaguar", "atari_jaguar")),
        System("Coleco_-_ColecoVision", listOf("coleco", "colecovision")),
        System("Mattel_-_Intellivision", listOf("intellivision")),
        System("Microsoft_-_MSX", listOf("msx")),
        System("Microsoft_-_MSX2", listOf("msx2")),
        System("The_3DO_Company_-_3DO", listOf("3do"))
    )

    /** The repository for a library console; the longest matching word wins (`game_boy_advance` over `game_boy`). */
    fun systemFor(consoleId: String): System? {
        val id = ConsoleIdMatch.normalize(consoleId)
        return systems
            .filterNot { s -> s.excludes.any { ConsoleIdMatch.has(id, it) } }
            .map { it to ConsoleIdMatch.longestMatch(id, it.words) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    private val extension = Regex("\\.[A-Za-z0-9]{1,5}$")
    private val discTag = Regex("\\s*\\((?:Disc|Disk|CD)\\s*[^)]*\\)", RegexOption.IGNORE_CASE)
    private val titleAndRegion = Regex("^[^(]*\\([^)]*\\)")
    private val forbidden = Regex("[&*/:`<>?\\\\|\"]")

    /**
     * File names to try for a ROM, best guess first: the whole name, then without its disc tag,
     * then only the title and the first group (`Game (USA) (Rev 1) (En,Fr)` → `Game (USA)`).
     */
    fun candidates(romName: String): List<String> {
        val stem = romName.trim().replace(extension, "").trim()
        if (stem.isEmpty()) return emptyList()
        val noDisc = stem.replace(discTag, "").trim()
        val short = titleAndRegion.find(noDisc)?.value?.trim()
        return listOfNotNull(stem, noDisc, short).filter { it.isNotEmpty() }.map(::thumbnailName).distinct()
    }

    /**
     * The name to look a library row up by: its file name, decoded (web listings keep it
     * URL-encoded) and without folders, because libretro names thumbnails after the full No-Intro /
     * Redump name; the row's display name has the region and tags stripped. [displayName] when
     * there is no file name.
     */
    fun lookupName(fileName: String, displayName: String): String {
        val decoded = runCatching { URLDecoder.decode(fileName.replace("+", "%2B"), "UTF-8") }.getOrDefault(fileName)
        return decoded.substringAfterLast('/').trim().ifEmpty { displayName }
    }

    /** The first name a box art lookup tries (no extension): two dumps of one release share it. */
    fun lookupStem(fileName: String, displayName: String): String =
        candidates(lookupName(fileName, displayName)).firstOrNull() ?: displayName

    /** libretro's naming rule: these characters become `_` in thumbnail file names. */
    fun thumbnailName(name: String): String = name.replace(forbidden, "_")

    fun boxartUrl(system: System, name: String): String =
        "https://raw.githubusercontent.com/libretro-thumbnails/${system.repo}/master/Named_Boxarts/" +
            URLEncoder.encode("$name.png", "UTF-8").replace("+", "%20")

    private val pngSignature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    /**
     * GitHub serves a symlinked thumbnail as a short text file holding the target's file name.
     * Returns that name without `.png`, or null when [head] (the start of the body) is an image.
     */
    fun symlinkTarget(head: ByteArray): String? {
        if (head.isEmpty() || head.size > 300) return null
        if (head.size >= pngSignature.size && pngSignature.indices.all { head[it] == pngSignature[it] }) return null
        val text = head.toString(Charsets.UTF_8).trim()
        if ('\n' in text || !text.endsWith(".png", ignoreCase = true)) return null
        return text.substringAfterLast('/').dropLast(4).takeIf { it.isNotBlank() }
    }
}
