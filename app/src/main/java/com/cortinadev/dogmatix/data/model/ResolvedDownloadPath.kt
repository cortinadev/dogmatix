package com.cortinadev.dogmatix.data.model

/** Where a console's downloads will end up, resolved from settings + what exists on disk. */
data class ResolvedDownloadPath(
    /** Human-readable path, e.g. ".../ROMS/gb". */
    val displayPath: String,
    /** Sub-folder inside the download directory (empty when downloading to the root or a custom dir). */
    val subPath: String,
    val source: Source,
    /**
     * Other existing folders in the download directory that also match this console
     * (e.g. both `gba` and `Gameboy Advance`). Only set for [Source.DETECTED]; the user can
     * merge them into one or leave them alone.
     */
    val alternatives: List<String> = emptyList()
) {
    enum class Source {
        /** Per-console directory picked by the user in Sources. */
        CUSTOM,
        /** Existing folder in the download directory that matches the console (e.g. "gb", "psx"). */
        DETECTED,
        /** Default folder that will be created on first download. */
        WILL_CREATE,
        /** "Separate by console" is off: files go to the download directory root. */
        ROOT,
        /** No download directory configured yet. */
        UNSET
    }
}
