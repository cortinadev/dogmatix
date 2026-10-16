package com.cortinadev.dogmatix.ui.screens.collection.collections

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.NameDialog
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.ToolRow

/**
 * "Collection" in the details card: ticks the game in or out of the manual collections (A on a
 * row), or makes a new one with it. Opens over the card, which stays open; B closes it. Focus
 * starts on "New collection…", the one row that is always there.
 */
@Composable
fun AddToCollectionDialog(
    consoleId: String,
    groupKey: String,
    gameName: String,
    onDismiss: () -> Unit,
    viewModel: AddToCollectionViewModel = hiltViewModel(key = "add-to-collection")
) {
    LaunchedEffect(consoleId, groupKey) { viewModel.bind(consoleId, groupKey) }
    val collections by viewModel.collections.collectAsState()
    val memberOf by viewModel.memberOf.collectAsState()
    var naming by remember { mutableStateOf(false) }
    val newFocus = rememberInitialFocus()

    if (naming) {
        NameDialog(
            stringResource(R.string.collections_new), "", stringResource(R.string.collections_create),
            { viewModel.createWithGame(it) }, { naming = false }
        )
    }
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.collections_add_title, gameName)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                item(key = "new") {
                    ToolRow(
                        title = stringResource(R.string.collections_new_ellipsis),
                        onClick = { naming = true },
                        modifier = Modifier.focusRequester(newFocus)
                    )
                }
                items(collections.orEmpty(), key = { it.id }) { collection ->
                    ToolRow(title = collection.name, onClick = { viewModel.toggle(collection.id) }) {
                        if (collection.id in memberOf) {
                            Icon(painterResource(R.drawable.ic_check), stringResource(R.string.collections_in), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.details_close), onDismiss) }
    )
}
