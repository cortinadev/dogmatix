package com.cortinadev.dogmatix.data.search

import com.cortinadev.dogmatix.data.model.Console
import com.cortinadev.dogmatix.data.model.Manufacturer
import com.cortinadev.dogmatix.data.repository.SourcesRepository
import com.cortinadev.dogmatix.util.ConsoleFolderAliases
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.SearchIndex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Sources: each console by its name, short name, folder aliases and manufacturer (Sources lists
 * consoles flat, the manufacturer is only their subtitle), and each address by its readable form
 * and its mirrors.
 */
class SourcesSearchProvider @Inject constructor(
    private val sources: SourcesRepository
) : SearchProvider {

    // Alphabetical like the Sources list, so equally good matches come in its order.
    override val entries: Flow<List<SearchEntry>> = sources.manufacturers.map { manufacturers ->
        manufacturers.flatMap { m -> m.consoles.map { m to it } }
            .sortedBy { (_, console) -> console.name.lowercase() }
            .flatMap { (m, console) -> entriesOf(m, console) }
    }

    private fun entriesOf(manufacturer: Manufacturer, console: Console): List<SearchEntry> {
        // The values in effect, as the export writes them: configured or built in.
        val short = ConsoleFormatter.getConsoleShortName(console.id)
        val aliases = console.folderAliases.ifEmpty { ConsoleFolderAliases.defaultAliasesFor(console.id) }
        val consoleEntry = SearchEntry(
            id = "console:${console.id}",
            group = SearchGroup.SOURCES,
            title = console.name,
            subtitle = "${manufacturer.name} · $short",
            keywords = listOf(short, manufacturer.name) + aliases,
            target = SearchTarget.Source(console.id)
        )
        val urlEntries = console.urls.mapIndexed { index, entry ->
            SearchEntry(
                id = "url:${console.id}:$index",
                group = SearchGroup.SOURCES,
                title = SearchIndex.readableUrl(entry.url),
                subtitle = console.name,
                keywords = entry.mirrors.map { SearchIndex.readableUrl(it) },
                target = SearchTarget.Source(console.id, index)
            )
        }
        return listOf(consoleEntry) + urlEntries
    }
}
