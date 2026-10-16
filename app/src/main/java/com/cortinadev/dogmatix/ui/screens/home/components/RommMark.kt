package com.cortinadev.dogmatix.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.LibraryGame
import com.cortinadev.dogmatix.ui.components.rememberRommCatalog
import com.cortinadev.dogmatix.ui.theme.OnAccent

/** Whether the row's game (any of its versions) is on the RomM server; false while RomM is not set up. */
@Composable
fun rememberInRomm(item: DownloadableFileWithTags, game: LibraryGame?): Boolean {
    val catalog = rememberRommCatalog()
    val index by catalog.index.collectAsState()
    val auth by catalog.imageAuth.collectAsState()
    if (auth == null) return false
    return remember(index, auth, item.file.id, game) {
        catalog.isOnServer(game?.files?.map { it.file } ?: listOf(item.file), index)
    }
}

/** Small server glyph inside a muted circle, next to ✓ and ★: the game is on the RomM server. */
@Composable
fun RommBadge() {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(R.drawable.ic_server),
            contentDescription = stringResource(R.string.romm_in_server),
            tint = OnAccent,
            modifier = Modifier.size(11.dp)
        )
    }
}
