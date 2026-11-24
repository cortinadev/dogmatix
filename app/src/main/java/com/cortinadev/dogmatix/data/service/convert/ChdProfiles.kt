package com.cortinadev.dogmatix.data.service.convert

/**
 * How each system wants its CHD, from what its emulators read (checked in November 2026):
 *  - CD systems (PS1, Saturn, Sega CD, PC Engine CD, Dreamcast, 3DO, Neo Geo CD): `createcd` from the
 *    .cue/.gdi (or .iso), cdlz+cdzl, 8-frame hunks. Read by DuckStation, Beetle/Mednafen, Flycast,
 *    Genesis Plus GX, PicoDrive and every libchdr since 2012.
 *  - PS2: `createcd` even for DVD games. NetherSX2/AetherSX2 predate `createdvd` and do not open
 *    DVD CHDs; PCSX2 2.x reads both kinds.
 *  - PSP: `createdvd` with 2048-byte hunks, which PPSSPP asks for (chdman's 4096 default since 0.263
 *    makes it warn about a "bad CHD"). Older RetroArch PPSSPP cores may not read DVD CHDs at all.
 * No profile uses zstd (AetherSX2's libchdr does not know it) nor FLAC (not implemented here).
 */
enum class ChdProfile(val media: Media, val hunkBytes: Int?) {
    CD(Media.CD, null),
    PS2(Media.CD, null),
    PSP(Media.DVD, 2048);

    enum class Media { CD, DVD }

    fun options(compression: ChdCompression = ChdCompression.BEST, threads: Int = ChdOptions.defaultThreads()) =
        ChdOptions(compression, hunkBytes, threads)

    companion object {
        private val cdSystems = setOf(
            "psx", "ps1", "playstation", "saturn", "segacd", "megacd", "sega_cd", "mega_cd", "pcenginecd",
            "pce-cd", "tg-cd", "turbografxcd", "dreamcast", "dc", "3do", "neogeocd", "pcfx", "cdi", "amigacd32",
            "cd32", "jaguarcd",
        )

        /** Profile for an ES-DE style system folder (psx, ps2, psp, dreamcast…), or null if CHD does not apply. */
        fun forSystem(system: String): ChdProfile? = when (val s = system.trim().lowercase()) {
            "ps2", "playstation2", "playstation_2" -> PS2
            "psp", "playstation_portable" -> PSP
            in cdSystems -> CD
            else -> null
        }

        /** Image types each profile converts from (lowercase extensions). */
        fun inputExtensions(profile: ChdProfile): Set<String> = when (profile) {
            CD -> setOf("cue", "gdi", "iso")
            PS2 -> setOf("iso", "cue")
            PSP -> setOf("iso")
        }
    }
}
