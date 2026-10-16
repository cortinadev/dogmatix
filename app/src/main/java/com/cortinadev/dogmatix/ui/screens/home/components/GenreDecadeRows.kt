package com.cortinadev.dogmatix.ui.screens.home.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.util.LibraryDecades
import com.cortinadev.dogmatix.util.LibraryGenre

/**
 * The Genre and Decade rows of the filter panel (multi-select, like Region or Tag). A game
 * without known genre or year never passes an active one (see `GameTraitsService`).
 */
@Composable
fun genreDecadeRows(
    genres: Set<LibraryGenre>,
    decades: Set<Int>,
    onGenres: (Set<LibraryGenre>) -> Unit,
    onDecades: (Set<Int>) -> Unit
): List<FilterRowSpec> = listOf(
    FilterRowSpec(
        label = stringResource(R.string.filter_genre),
        options = LibraryGenre.entries.map { FilterOption(it.name, genreLabel(it), genreShortLabel(it)) },
        selected = genres.map { it.name }.toSet(),
        onSelectionChange = { ids -> onGenres(LibraryGenre.entries.filter { it.name in ids }.toSet()) }
    ),
    FilterRowSpec(
        label = stringResource(R.string.filter_decade),
        options = LibraryDecades.options.map {
            FilterOption(it.toString(), stringResource(R.string.decade_long, it), stringResource(R.string.decade_short, LibraryDecades.twoDigits(it)))
        },
        selected = decades.map { it.toString() }.toSet(),
        onSelectionChange = { ids -> onDecades(ids.mapNotNull { it.toIntOrNull() }.toSet()) }
    )
)

@Composable
fun genreLabel(genre: LibraryGenre): String = stringResource(
    when (genre) {
        LibraryGenre.ACTION -> R.string.genre_action
        LibraryGenre.ADVENTURE -> R.string.genre_adventure
        LibraryGenre.PLATFORM -> R.string.genre_platform
        LibraryGenre.RPG -> R.string.genre_rpg
        LibraryGenre.SHOOTER -> R.string.genre_shooter
        LibraryGenre.FIGHTING -> R.string.genre_fighting
        LibraryGenre.RACING -> R.string.genre_racing
        LibraryGenre.SPORTS -> R.string.genre_sports
        LibraryGenre.PUZZLE -> R.string.genre_puzzle
        LibraryGenre.STRATEGY -> R.string.genre_strategy
        LibraryGenre.SIMULATION -> R.string.genre_simulation
        LibraryGenre.BOARD -> R.string.genre_board
        LibraryGenre.MUSIC -> R.string.genre_music
        LibraryGenre.OTHER -> R.string.genre_other
    }
)

/** For the compact panel, where the value sits between ◀ ▶. */
@Composable
private fun genreShortLabel(genre: LibraryGenre): String = when (genre) {
    LibraryGenre.RPG -> stringResource(R.string.genre_rpg_short)
    LibraryGenre.PUZZLE -> stringResource(R.string.genre_puzzle_short)
    LibraryGenre.BOARD -> stringResource(R.string.genre_board_short)
    LibraryGenre.MUSIC -> stringResource(R.string.genre_music_short)
    else -> genreLabel(genre)
}
