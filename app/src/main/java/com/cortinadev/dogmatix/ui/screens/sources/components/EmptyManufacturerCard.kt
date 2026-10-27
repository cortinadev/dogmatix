package com.cortinadev.dogmatix.ui.screens.sources.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens

/**
 * A manufacturer left without consoles (the list only shows consoles, so it would be invisible
 * otherwise): its name, "No consoles" and the button that deletes it, the only focusable part.
 */
@Composable
fun EmptyManufacturerCard(
    name: String,
    onDelete: () -> Unit,
    focusRequester: FocusRequester
) {
    val source = rememberFocusSource()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = LocalDogmatixTokens.current.card)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.sources_manufacturer_no_consoles),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDelete,
                interactionSource = source,
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .focusRing(source, 24.dp)
            ) {
                Icon(
                    painterResource(R.drawable.ic_trash),
                    contentDescription = stringResource(R.string.sources_delete_manufacturer),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
