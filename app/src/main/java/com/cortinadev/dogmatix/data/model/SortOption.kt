package com.cortinadev.dogmatix.data.model

/** Library ordering; the DAO's ORDER BY switches on [ordinal], so new options go at the end. */
enum class SortOption {
    NAME_ASC, NAME_DESC, SIZE_DESC, SIZE_ASC,
    /** By console name, then title. Only offered while more than one console is in play. */
    CONSOLE,
    /** By decade of first release (game_traits), then title; games without a year go last. */
    DECADE_ASC, DECADE_DESC;

    val byDecade: Boolean get() = this == DECADE_ASC || this == DECADE_DESC
}
