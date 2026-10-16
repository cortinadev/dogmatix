package com.cortinadev.dogmatix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cortinadev.dogmatix.data.model.LibraryGame
import com.cortinadev.dogmatix.data.service.CoverRepository
import com.cortinadev.dogmatix.di.CoverEntryPoint
import com.cortinadev.dogmatix.util.ConsoleFormatter
import dagger.hilt.android.EntryPointAccessors

@Composable
fun rememberCoverRepository(): CoverRepository {
    val app = LocalContext.current.applicationContext
    return remember(app) { EntryPointAccessors.fromApplication(app, CoverEntryPoint::class.java).coverRepository() }
}

/**
 * The cover URL of a game: the one already in memory on the first frame, else looked up in the
 * background. Null while unknown, and when the game has no cover.
 */
@Composable
fun rememberCoverUrl(consoleId: String, fileName: String, romName: String): String? {
    val covers = rememberCoverRepository()
    var url by remember(consoleId, fileName) { mutableStateOf(covers.cached(consoleId, fileName)) }
    LaunchedEffect(consoleId, fileName) {
        if (url == null) url = runCatching { covers.coverUrl(consoleId, fileName, romName) }.getOrNull()
    }
    return url
}

/** The cover URL of a whole game (see [CoverRepository.gameCoverUrl]): its preferred version's, or another version's. */
@Composable
fun rememberGameCoverUrl(game: LibraryGame): String? {
    val covers = rememberCoverRepository()
    val rom = game.primary.file
    var url by remember(rom.consoleId, rom.fileName) { mutableStateOf(covers.cached(rom.consoleId, rom.fileName)) }
    LaunchedEffect(rom.consoleId, rom.fileName) {
        if (url == null) url = runCatching { covers.gameCoverUrl(game) }.getOrNull()
    }
    return url
}

/** Square cover of a game with versions: falls back to another version's box art. */
@Composable
fun GameCover(game: LibraryGame, size: Dp, modifier: Modifier = Modifier) {
    CoverSquare(rememberGameCoverUrl(game), game.consoleId, size, modifier)
}

/** Square cover of a library game, looked up by console and file name (see [CoverSquare]). */
@Composable
fun GameCover(consoleId: String, fileName: String, romName: String, size: Dp, modifier: Modifier = Modifier) {
    CoverSquare(rememberCoverUrl(consoleId, fileName, romName), consoleId, size, modifier)
}

/**
 * A square of [size] in the console's colour with its short name; the box art, cropped to fill
 * the square, is drawn over it once [url] has loaded.
 */
@Composable
fun CoverSquare(url: String?, consoleId: String, size: Dp, modifier: Modifier = Modifier) {
    val tint = tagPalette().console(consoleId)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 6))
            .background(tint.background),
        contentAlignment = Alignment.Center
    ) {
        Text(
            ConsoleFormatter.getConsoleShortName(consoleId),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = (size.value / 3.6f).coerceIn(8f, 14f).sp),
            color = tint.content,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
        if (url != null) {
            AsyncImage(
                model = rememberImageModel(url),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size)
            )
        }
    }
}
