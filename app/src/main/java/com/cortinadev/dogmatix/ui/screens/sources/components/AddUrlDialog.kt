package com.cortinadev.dogmatix.ui.screens.sources.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.ContentType
import com.cortinadev.dogmatix.data.model.ScrapeRecipe
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.cortinadev.dogmatix.data.service.RommPlatform
import com.cortinadev.dogmatix.ui.screens.settings.ThemedSwitch
import com.cortinadev.dogmatix.ui.screens.sources.ScrapeTestState
import com.cortinadev.dogmatix.util.IaSource
import com.cortinadev.dogmatix.util.MirrorUrls
import com.cortinadev.dogmatix.util.RecipeProblem
import com.cortinadev.dogmatix.util.RommSource
import com.cortinadev.dogmatix.util.ScrapeRecipes
import com.cortinadev.dogmatix.util.SourceKind
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.WideDialogProperties
import com.cortinadev.dogmatix.ui.components.wideDialog
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.components.scanFailureText

/** What the source dialog gives back: [mirrors] only for a web listing, [scrape] only for a page with selectors. */
data class UrlFormValues(
    val url: String,
    val contentType: ContentType,
    val mirrors: List<String> = emptyList(),
    val scrape: ScrapeRecipe? = null
)

/**
 * Adds a source to a console or, when [existing] is given, edits one in place.
 * Opens with the URL field focused; B cancels. A web address can be read as a folder listing
 * (with mirrors) or, with the switch on, as a page with CSS selectors that "Test" tries on the
 * first page before saving.
 */
@Composable
fun AddUrlDialog(
    existing: UrlEntry? = null,
    /** Platforms of the RomM server from Settings; one chip each sets the URL to `romm://<slug>`. */
    rommPlatforms: List<RommPlatform> = emptyList(),
    scrapeTest: ScrapeTestState = ScrapeTestState.Idle,
    onTestScrape: (String, ScrapeRecipe) -> Unit = { _, _ -> },
    onResetScrapeTest: () -> Unit = {},
    onDismiss: () -> Unit,
    onConfirm: (UrlFormValues) -> Unit
) {
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var contentType by remember { mutableStateOf(existing?.contentType ?: ContentType.GAME) }
    var mirrors by remember { mutableStateOf(existing?.mirrors.orEmpty().joinToString("\n")) }
    val saved = existing?.scrape
    var recipeOn by remember { mutableStateOf(saved != null) }
    var item by remember { mutableStateOf(saved?.item.orEmpty()) }
    var link by remember { mutableStateOf(saved?.link.orEmpty()) }
    var name by remember { mutableStateOf(saved?.name.orEmpty()) }
    var size by remember { mutableStateOf(saved?.size.orEmpty()) }
    var pages by remember { mutableStateOf(saved?.pages.orEmpty()) }
    var ext by remember { mutableStateOf(saved?.ext.orEmpty()) }
    val urlFocus = rememberInitialFocus()
    val editing = existing != null

    val trimmed = url.trim()
    // Not a .torrent over http, romm://, an Internet Archive item or a magnet: something a page or a
    // listing can be read from (an archive.org ZIP viewer is one). Judged by how the address is stored.
    val stored = IaSource.normalize(trimmed)?.source ?: trimmed
    val webListing = MirrorUrls.isWeb(stored) && SourceKind.of(stored) == SourceKind.WEB
    val hasPagePlaceholder = ScrapeRecipes.hasPlaceholder(trimmed)
    val recipe = if (webListing && recipeOn) ScrapeRecipe(
        item = item.trim(), link = link.trim(), name = name.trim(), size = size.trim(),
        pages = if (hasPagePlaceholder) pages.trim() else "",
        ext = ext.trim().removePrefix(".").trim()
    ) else null
    val canSave = url.isNotBlank() && (recipe == null || recipe.item.isNotBlank())
    // Any edit makes the last result stale (and drops a test under way).
    LaunchedEffect(trimmed, recipeOn, item, link, name, size, pages, ext) { onResetScrapeTest() }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri -> uri?.let { url = it.toString() } }
    )

    AlertDialog(
        modifier = Modifier.wideDialog().closeOnGamepadB(onDismiss),
        properties = WideDialogProperties,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editing) R.string.dialog_edit_source else R.string.dialog_add_source)) },
        text = {
            // Scrolls: in landscape the mirrors box pushes the content type chips below the fold.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // The recipe fields make the dialog long enough already.
                if (recipe == null) {
                    Text(stringResource(R.string.dialog_add_source_hint))
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.dialog_url_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).focusRequester(urlFocus),
                        placeholder = { Text(stringResource(R.string.dialog_url_placeholder)) }
                    )

                    val pickSource = rememberFocusSource()
                    IconButton(
                        onClick = { filePicker.launch("application/x-bittorrent") },
                        interactionSource = pickSource,
                        modifier = Modifier.focusRing(pickSource, 20.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder),
                            contentDescription = stringResource(R.string.dialog_upload_torrent)
                        )
                    }
                }

                if (rommPlatforms.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.dialog_from_romm), style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    RommPlatformChips(platforms = rommPlatforms, selected = RommSource.slugOf(url)) { url = RommSource.sourceFor(it.slug) }
                }

                if (webListing) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ScrapeToggleRow(checked = recipeOn) { recipeOn = !recipeOn }
                }

                if (recipe != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    RecipeField(item, { item = it }, R.string.dialog_scrape_item, R.string.dialog_scrape_item_hint, Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecipeField(link, { link = it }, R.string.dialog_scrape_link, R.string.dialog_scrape_link_hint, Modifier.weight(1f))
                        RecipeField(name, { name = it }, R.string.dialog_scrape_name, R.string.dialog_scrape_name_hint, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecipeField(size, { size = it }, R.string.dialog_scrape_size, R.string.dialog_scrape_size_hint, Modifier.weight(1f))
                        RecipeField(ext, { ext = it }, R.string.dialog_scrape_ext, R.string.dialog_scrape_ext_hint, Modifier.weight(1f))
                    }
                    if (hasPagePlaceholder) {
                        RecipeField(pages, { pages = it }, R.string.dialog_scrape_pages, R.string.dialog_scrape_pages_hint, Modifier.fillMaxWidth())
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val running = scrapeTest is ScrapeTestState.Running
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Stays enabled while running: a button that turns disabled under the
                        // cursor drops the focus, and the D-pad would have nowhere to go from there.
                        DialogButton(
                            text = stringResource(if (running) R.string.romm_testing else R.string.settings_test),
                            onClick = { if (!running) onTestScrape(trimmed, recipe) },
                            enabled = canSave
                        )
                        if (running) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    ScrapeTestResult(scrapeTest)
                }

                // Reserve addresses only make sense for a web listing (a page recipe has none).
                if (webListing && !recipeOn) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = mirrors,
                        onValueChange = { mirrors = it },
                        label = { Text(stringResource(R.string.dialog_mirrors_label)) },
                        supportingText = { Text(stringResource(R.string.dialog_mirrors_hint)) },
                        minLines = 1,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(stringResource(R.string.dialog_content_type), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                ContentTypeSelection(selectedType = contentType, onTypeSelected = { contentType = it })
            }
        },
        confirmButton = {
            DialogButton(
                text = stringResource(if (editing) R.string.dialog_save else R.string.dialog_add),
                onClick = {
                    onConfirm(
                        UrlFormValues(
                            trimmed, contentType,
                            mirrors = if (webListing && !recipeOn) MirrorUrls.parse(mirrors, stored) else emptyList(),
                            scrape = recipe
                        )
                    )
                    onDismiss()
                },
                enabled = canSave
            )
        },
        dismissButton = {
            DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss)
        }
    )
}

/** The switch between a folder listing and a page with selectors: the whole row takes the focus, A toggles. */
@Composable
private fun ScrapeToggleRow(checked: Boolean, onToggle: () -> Unit) {
    val source = rememberFocusSource()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .focusRing(source)
            .clickable(interactionSource = source, indication = null, onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.dialog_scrape_toggle), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.dialog_scrape_toggle_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // The row is the target; the switch only shows the state (and still answers a touch).
        Box(modifier = Modifier.focusProperties { canFocus = false }) {
            ThemedSwitch(checked = checked, onChange = { onToggle() })
        }
    }
}

@Composable
private fun RecipeField(value: String, onChange: (String) -> Unit, @StringRes label: Int, @StringRes hint: Int, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        placeholder = { Text(stringResource(hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        modifier = modifier
    )
}

/** What "Test" found on the first page. Text only: it never takes the focus. */
@Composable
private fun ScrapeTestResult(state: ScrapeTestState) {
    val scheme = MaterialTheme.colorScheme
    val lines: List<String>
    var isError = false
    when (state) {
        ScrapeTestState.Idle, ScrapeTestState.Running -> return
        is ScrapeTestState.Result -> lines = when {
            state.matched == 0 -> listOf(stringResource(R.string.dialog_scrape_test_none))
            state.total == 0 -> listOf(stringResource(R.string.dialog_scrape_test_no_links, state.matched))
            else -> listOf(pluralStringResource(R.plurals.dialog_scrape_test_items, state.total, state.total)) +
                state.sample.map { row -> if (row.fileSize > 0) "${row.name} · ${formatBytes(row.fileSize)}" else row.name }
        }
        is ScrapeTestState.Error -> {
            isError = true
            lines = listOf(
                when (val problem = state.problem) {
                    is RecipeProblem.BadSelector -> stringResource(R.string.dialog_scrape_test_bad_selector, problem.css)
                    is RecipeProblem.BadPages -> stringResource(R.string.dialog_scrape_test_bad_pages, problem.spec)
                    RecipeProblem.NoItem -> stringResource(R.string.dialog_scrape_item)
                    null -> scanFailureText(state.failure, state.httpCode, state.detail)
                }
            )
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        lines.forEachIndexed { i, line ->
            Text(
                line,
                style = if (i == 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = if (isError) scheme.error else if (i == 0) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = if (i == 0) 3 else 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RommPlatformChips(platforms: List<RommPlatform>, selected: String?, onPick: (RommPlatform) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        platforms.forEach { platform ->
            val source = rememberFocusSource()
            FilterChip(
                selected = selected != null && (platform.slug.equals(selected, true) || platform.fsSlug.equals(selected, true)),
                onClick = { onPick(platform) },
                label = { Text(platform.label, maxLines = 1) },
                interactionSource = source,
                modifier = Modifier.focusRing(source, 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContentTypeSelection(
    selectedType: ContentType,
    onTypeSelected: (ContentType) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ContentType.entries.forEach { type ->
            val source = rememberFocusSource()
            FilterChip(
                selected = selectedType == type,
                onClick = { onTypeSelected(type) },
                label = { Text(text = type.name.lowercase().replaceFirstChar { it.uppercase() }, maxLines = 1) },
                interactionSource = source,
                modifier = Modifier.focusRing(source, 8.dp)
            )
        }
    }
}
