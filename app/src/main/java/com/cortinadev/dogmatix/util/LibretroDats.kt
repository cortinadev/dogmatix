package com.cortinadev.dogmatix.util

import java.net.URLEncoder
import java.util.Locale

/**
 * The No-Intro and Redump DATs that libretro-database republishes (ClrMamePro text, the ones
 * RetroArch's scanner uses), for the systems [LibretroThumbnails] knows: the thumbnail repository
 * `Nintendo_-_Game_Boy_Advance` is the DAT `Nintendo - Game Boy Advance.dat`. Cartridge systems
 * are under `metadat/no-intro`, disc systems under `metadat/redump`; PSP is in both.
 */
object LibretroDats {

    enum class Collection(val id: String, val folder: String) {
        NO_INTRO(DownloadCheck.NO_INTRO, "no-intro"),
        REDUMP(DownloadCheck.REDUMP, "redump")
    }

    private const val BASE = "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/"

    /** Thumbnail repositories whose DAT is Redump's (checked against the repository, Oct 2026). */
    private val discSystems = setOf(
        "Nintendo_-_GameCube", "Nintendo_-_Wii", "Sega_-_Mega-CD_-_Sega_CD", "Sega_-_Saturn",
        "Sega_-_Dreamcast", "Sony_-_PlayStation", "Sony_-_PlayStation_2",
        "NEC_-_PC_Engine_CD_-_TurboGrafx-CD", "The_3DO_Company_-_3DO"
    )
    /** UMDs are Redump's, the digital releases No-Intro's. */
    private val bothCollections = setOf("Sony_-_PlayStation_Portable")

    fun datName(system: LibretroThumbnails.System): String = system.repo.replace('_', ' ')

    fun collectionsFor(system: LibretroThumbnails.System): List<Collection> = when (system.repo) {
        in bothCollections -> listOf(Collection.REDUMP, Collection.NO_INTRO)
        in discSystems -> listOf(Collection.REDUMP)
        else -> listOf(Collection.NO_INTRO)
    }

    fun url(collection: Collection, datName: String): String =
        BASE + collection.folder + "/" + URLEncoder.encode("$datName.dat", "UTF-8").replace("+", "%20")

    /** The DATs to fetch for a library console, best first; empty for systems without one. */
    fun sourcesFor(consoleId: String): List<Pair<Collection, String>> {
        val system = LibretroThumbnails.systemFor(consoleId) ?: return emptyList()
        return collectionsFor(system).map { it to url(it, datName(system)) }
    }

    private val bracketed = Regex("""\s*[(\[][^)\]]*[)\]]""")

    /**
     * The system a DAT describes, from its header name: `Nintendo - Game Boy Advance`, also with
     * the date or `(Parent-Clone)` No-Intro adds to it. Null when it is not one of ours.
     */
    fun systemForDatName(headerName: String): LibretroThumbnails.System? {
        val wanted = normalized(headerName.replace(bracketed, ""))
        if (wanted.isEmpty()) return null
        return LibretroThumbnails.systems.firstOrNull { normalized(datName(it)) == wanted }
    }

    /** Of [consoleIds], the one an imported DAT is most likely for; null when none fits. */
    fun guessConsole(headerName: String, consoleIds: List<String>): String? {
        val system = systemForDatName(headerName) ?: return null
        return consoleIds.firstOrNull { LibretroThumbnails.systemFor(it) == system }
    }

    private fun normalized(name: String): String = name.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
}
