package com.cortinadev.dogmatix.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.ColorPickerDialog
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.TagChip
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.components.tagPalette
import com.cortinadev.dogmatix.util.TagColorKind

/** Label and sample chip text of each tag group. */
internal val TagColorKind.labelRes: Int
    get() = when (this) {
        TagColorKind.REGION -> R.string.tag_color_region
        TagColorKind.LANGUAGE -> R.string.tag_color_language
        TagColorKind.REVISION -> R.string.tag_color_revision
        TagColorKind.RELEASE -> R.string.tag_color_release
    }

private val TagColorKind.sample: String
    get() = when (this) {
        TagColorKind.REGION -> "USA"
        TagColorKind.LANGUAGE -> "En"
        TagColorKind.REVISION -> "Rev 1"
        TagColorKind.RELEASE -> "Beta"
    }

/**
 * Settings → Tag colours: one row per tag group with its sample chip; A opens the colour picker
 * for that group. "Reset" puts every group back on its default. B closes.
 */
@Composable
internal fun TagColorsDialog(
    onPick: (TagColorKind, Int?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = tagPalette()
    var editing by remember { mutableStateOf<TagColorKind?>(null) }
    val firstRow = rememberInitialFocus()

    editing?.let { kind ->
        ColorPickerDialog(
            title = stringResource(kind.labelRes),
            initial = palette.kindColor(kind),
            sample = kind.sample,
            strong = false,
            onPick = { onPick(kind, it) },
            onDismiss = { editing = null }
        )
    }

    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_tag_colors)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TagColorKind.entries.forEachIndexed { index, kind ->
                    val source = rememberFocusSource()
                    Row(
                        modifier = (if (index == 0) Modifier.focusRequester(firstRow) else Modifier)
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .focusRing(source)
                            .clickable(interactionSource = source, indication = null) { editing = kind }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(modifier = Modifier.size(20.dp).clip(CircleShape).background(palette.kindColor(kind)))
                        Text(stringResource(kind.labelRes), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        TagChip(kind.sample, colors = palette.tint(palette.kindColor(kind), strong = false))
                    }
                }
            }
        },
        confirmButton = {
            DialogButton(text = stringResource(R.string.details_close), onClick = onDismiss)
        },
        dismissButton = {
            DialogButton(text = stringResource(R.string.tag_colors_reset), onClick = onReset)
        }
    )
}
