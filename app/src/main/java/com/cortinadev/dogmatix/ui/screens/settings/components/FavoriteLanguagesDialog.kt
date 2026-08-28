package com.cortinadev.dogmatix.ui.screens.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.defaultFavoriteLanguages
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource

/**
 * Picks which language tags the Library filter lists before "Show more".
 * Changes are saved on every tap, so closing the dialog is enough.
 */
@Composable
fun FavoriteLanguagesDialog(
    available: List<String>,
    favorites: Set<String>,
    onChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    // Favorites not (yet) in the library are still listed so they can be unticked.
    val options = (available + favorites).distinct().sorted()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_favorite_languages)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_favorite_languages_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (options.isEmpty()) {
                    Text(stringResource(R.string.settings_favorite_languages_empty), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(options, key = { it }) { tag ->
                            val checked = tag in favorites
                            LanguageRow(tag, checked) { onChange(if (checked) favorites - tag else favorites + tag) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) }
        },
        dismissButton = {
            TextButton(onClick = { onChange(defaultFavoriteLanguages()) }) { Text(stringResource(R.string.settings_reset_default)) }
        }
    )
}

@Composable
private fun LanguageRow(label: String, checked: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(scheme.surfaceContainer)
            .focusRing(source, 6.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (checked) scheme.primary else scheme.surfaceContainerHighest)
        )
        Text(
            label,
            style = if (checked) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface
        )
    }
}
