package com.cortinadev.dogmatix.util

/**
 * The default colour of a console's chip and cover placeholder, by family, used until the user
 * picks one for the console in Sources (`ConsoleEntity.color`). Pure JVM for the tests.
 */
enum class ConsoleFamily(val defaultArgb: Int) {
    NINTENDO(0xFFFF5A5F.toInt()),
    NINTENDO_HANDHELD(0xFF8FCF3C.toInt()),
    PLAYSTATION(0xFF5B8CFF.toInt()),
    SEGA(0xFF2FA8FF.toInt()),
    XBOX(0xFF5CC93B.toInt()),
    ATARI(0xFFFF8A3D.toInt()),
    NEC(0xFFFFB547.toInt()),
    SNK(0xFFE7C24A.toInt()),
    BANDAI(0xFFE86BD6.toInt()),
    ARCADE(0xFFA57BFF.toInt()),
    COMPUTER(0xFF2ED6B8.toInt()),
    OTHER(0xFF9AA0AE.toInt());

    companion object {
        // Checked in this order: handhelds before Nintendo, arcade boards and computers before their makers.
        private val rules: List<Pair<ConsoleFamily, List<String>>> = listOf(
            NINTENDO_HANDHELD to listOf(
                "gameboy", "game_boy", "gb", "gbc", "gba", "nintendo_ds", "nds", "ds", "dsi", "3ds",
                "virtual_boy", "virtualboy", "pokemon_mini"
            ),
            ARCADE to listOf("arcade", "mame", "fbneo", "fba", "cps1", "cps2", "cps3", "naomi", "atomiswave"),
            // Before the makers: an MSX from Microsoft or Sony is still a computer.
            COMPUTER to listOf(
                "amiga", "commodore", "c64", "msx", "msx2", "dos", "zx", "spectrum", "amstrad", "cpc",
                "apple", "pc98", "x68000", "scummvm", "computer"
            ),
            NINTENDO to listOf("nintendo", "nes", "famicom", "snes", "n64", "gamecube", "gc", "ngc", "wii", "switch"),
            PLAYSTATION to listOf("sony", "playstation", "psx", "ps1", "ps2", "ps3", "ps4", "psp", "vita"),
            SEGA to listOf(
                "sega", "genesis", "mega_drive", "megadrive", "master_system", "game_gear", "saturn",
                "dreamcast", "32x", "sg_1000", "mega_cd", "sega_cd"
            ),
            XBOX to listOf("microsoft", "xbox"),
            ATARI to listOf("atari", "lynx", "jaguar"),
            NEC to listOf("nec", "pc_engine", "pce", "turbografx", "turbografx_16", "pc_fx", "supergrafx"),
            SNK to listOf("snk", "neo_geo", "neogeo", "ngp", "ngpc"),
            BANDAI to listOf("bandai", "wonderswan")
        )

        fun of(consoleId: String): ConsoleFamily {
            val id = ConsoleIdMatch.normalize(consoleId)
            return rules.firstOrNull { (_, words) -> words.any { ConsoleIdMatch.has(id, it) } }?.first ?: OTHER
        }
    }
}
