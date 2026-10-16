package com.cortinadev.dogmatix.data.repository

import android.content.Context
import androidx.core.net.toUri
import com.cortinadev.dogmatix.data.local.dao.ConsoleDao
import com.cortinadev.dogmatix.data.local.dao.ManufacturerDao
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.local.entity.ManufacturerEntity
import com.cortinadev.dogmatix.data.model.Console
import com.cortinadev.dogmatix.data.model.ContentType
import com.cortinadev.dogmatix.data.model.Manufacturer
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.cortinadev.dogmatix.data.service.DatabaseScrapingService
import com.cortinadev.dogmatix.data.service.SourcesImportUndo
import com.cortinadev.dogmatix.data.service.TorrentHandleRegistry
import com.cortinadev.dogmatix.util.ConsoleAliasInfo
import com.cortinadev.dogmatix.util.ColorMath
import com.cortinadev.dogmatix.util.ConsoleAliasRegistry
import com.cortinadev.dogmatix.util.ConsoleFolderAliases
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.SourceConsole
import com.cortinadev.dogmatix.util.SourceManufacturer
import com.cortinadev.dogmatix.util.SourcesJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the app does with sources (manufacturer → console → URL entries) lives here:
 * the domain view of the Room tables, editing, and the JSON export / import.
 * ViewModels do not talk to the DAOs directly.
 */
@Singleton
class SourcesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val manufacturerDao: ManufacturerDao,
    private val consoleDao: ConsoleDao,
    private val torrentHandleRegistry: TorrentHandleRegistry,
    private val databaseScrapingService: DatabaseScrapingService,
    // frontends: stars travel in the export and come back (added) on import
    private val favouritesRepository: FavouritesRepository,
    // coleccion-gestion
    private val importUndo: javax.inject.Provider<SourcesImportUndo>
) {

    /** Manufacturers with their consoles and parsed URL entries; consoles without a manufacturer are dropped. */
    val manufacturers: Flow<List<Manufacturer>> = combine(
        manufacturerDao.getAllManufacturers(),
        consoleDao.getAllConsoles()
    ) { manufacturers, consoles ->
        manufacturers.map { m ->
            Manufacturer(
                id = m.id, name = m.name,
                consoles = consoles.filter { it.manufacturerId == m.id }.map { it.toModel() }
            )
        }
    }

    /** Configured short names / folder aliases per console id, for [ConsoleAliasRegistry]. */
    val aliasOverrides: Flow<Map<String, ConsoleAliasInfo>> = consoleDao.getAllConsoles().map { consoles ->
        consoles.filter { it.shortName.isNotBlank() || it.folderAliases.isNotBlank() }
            .associate { it.id to ConsoleAliasInfo(it.shortName, ConsoleAliasRegistry.parseAliases(it.folderAliases)) }
    }

    /** Colours picked per console (console id → ARGB); consoles without one are left out. */
    val consoleColors: Flow<Map<String, Int>> = consoleDao.getAllConsoles().map { consoles ->
        consoles.mapNotNull { c -> ColorMath.parseHex(c.color)?.let { c.id to it } }.toMap()
    }

    suspend fun isEmpty(): Boolean = consoleDao.getAllConsoles().first().isEmpty()

    suspend fun getConsole(consoleId: String): Console? = consoleDao.getConsoleById(consoleId)?.toModel()

    suspend fun getConsoleEntity(consoleId: String): ConsoleEntity? = consoleDao.getConsoleById(consoleId)

    // ---- Manufacturers & consoles -------------------------------------------------------------

    suspend fun addManufacturer(name: String): String {
        val id = SourcesJson.slug(name)
        if (manufacturerDao.getManufacturerById(id) == null) {
            manufacturerDao.insertManufacturer(ManufacturerEntity(id = id, name = name.trim()))
        }
        return id
    }

    suspend fun addConsole(manufacturerId: String, name: String, shortName: String = "", folderAliases: List<String> = emptyList(), color: String = ""): String {
        val id = "${manufacturerId}_${SourcesJson.slug(name)}"
        if (consoleDao.getConsoleById(id) == null) {
            consoleDao.insertConsole(ConsoleEntity(
                id = id, name = name.trim(), manufacturerId = manufacturerId, urls = "[]",
                shortName = shortName.trim(), folderAliases = ConsoleAliasRegistry.serializeAliases(folderAliases),
                color = normalizeColor(color)
            ))
        }
        return id
    }

    suspend fun renameManufacturer(manufacturerId: String, name: String) {
        val m = manufacturerDao.getManufacturerById(manufacturerId) ?: return
        manufacturerDao.updateManufacturer(m.copy(name = name.trim()))
    }

    /** Updates the display name, chip label, folder aliases and colour (empty values mean "use the built-in default"). */
    suspend fun updateConsole(consoleId: String, name: String, shortName: String, folderAliases: List<String>, color: String) {
        val c = consoleDao.getConsoleById(consoleId) ?: return
        consoleDao.updateConsole(c.copy(
            name = name.trim(),
            shortName = shortName.trim(),
            folderAliases = ConsoleAliasRegistry.serializeAliases(folderAliases),
            color = normalizeColor(color)
        ))
    }

    /** Deletes the console; its indexed files go with it (Room cascade) and torrent handles are released. */
    suspend fun deleteConsole(consoleId: String) {
        val console = consoleDao.getConsoleById(consoleId) ?: return
        releaseSources(SourcesJson.parseUrlEntries(console.urls))
        consoleDao.deleteConsoleById(consoleId)
    }

    /** Deletes the manufacturer and every console under it (consoles have no FK to manufacturers). */
    suspend fun deleteManufacturer(manufacturerId: String) {
        consoleDao.getConsolesByManufacturerOnce(manufacturerId).forEach { deleteConsole(it.id) }
        manufacturerDao.deleteManufacturerById(manufacturerId)
    }

    // ---- URL entries --------------------------------------------------------------------------

    /**
     * Appends a source. `content://` URIs (a picked .torrent) are copied into internal storage;
     * magnets are trimmed to the tracker limit. Returns false if the entry could not be stored.
     */
    suspend fun addUrl(consoleId: String, url: String, contentType: ContentType, folders: List<String> = emptyList(), mirrors: List<String> = emptyList()): Boolean {
        val console = consoleDao.getConsoleById(consoleId) ?: return false
        val stored = normalizeUrl(url) ?: return false
        val urls = SourcesJson.parseUrlEntries(console.urls) + UrlEntry(stored, contentType, folders, mirrors = mirrors)
        consoleDao.updateConsole(console.copy(urls = SourcesJson.serializeUrlEntries(urls)))
        return true
    }

    /** Replaces the entry at [index]; a changed torrent source releases the old handle. */
    suspend fun updateUrl(consoleId: String, index: Int, url: String, contentType: ContentType, mirrors: List<String> = emptyList()): Boolean {
        val console = consoleDao.getConsoleById(consoleId) ?: return false
        val urls = SourcesJson.parseUrlEntries(console.urls).toMutableList()
        val old = urls.getOrNull(index) ?: return false
        val stored = if (url.trim() == old.url) old.url else normalizeUrl(url) ?: return false
        if (stored != old.url) releaseSource(old.url)
        urls[index] = old.copy(url = stored, contentType = contentType, mirrors = mirrors)
        consoleDao.updateConsole(console.copy(urls = SourcesJson.serializeUrlEntries(urls)))
        return true
    }

    /** Turns the entry at [index] on or off; a source going off releases its torrent handle. */
    suspend fun setUrlEnabled(consoleId: String, index: Int, enabled: Boolean) {
        val console = consoleDao.getConsoleById(consoleId) ?: return
        val urls = SourcesJson.parseUrlEntries(console.urls).toMutableList()
        val old = urls.getOrNull(index) ?: return
        if (old.enabled == enabled) return
        if (!enabled) releaseSource(old.url)
        urls[index] = old.copy(enabled = enabled)
        consoleDao.updateConsole(console.copy(urls = SourcesJson.serializeUrlEntries(urls)))
    }

    suspend fun deleteUrl(consoleId: String, index: Int) {
        val console = consoleDao.getConsoleById(consoleId) ?: return
        val urls = SourcesJson.parseUrlEntries(console.urls).toMutableList()
        if (index !in urls.indices) return
        releaseSource(urls.removeAt(index).url)
        consoleDao.updateConsole(console.copy(urls = SourcesJson.serializeUrlEntries(urls)))
    }

    // ---- Export / import ----------------------------------------------------------------------

    /**
     * Writes the current sources as a JSON document to the app cache and returns the file.
     * Local `.torrent` copies are not portable and are left out.
     */
    suspend fun exportToFile(): File = withContext(Dispatchers.IO) {
        val starred = favouritesRepository.all().groupBy({ it.consoleId }, { it.fileName })
        val doc = manufacturers.first().map { m ->
            SourceManufacturer(
                id = m.id, name = m.name,
                consoles = m.consoles.map { c ->
                    // Always written out, so the file documents which abbreviation / folders apply to each console.
                    SourceConsole(
                        id = c.id, name = c.name,
                        urls = c.urls.filterNot { it.url.startsWith("/") },
                        shortName = ConsoleFormatter.getConsoleShortName(c.id),
                        folderAliases = c.folderAliases.ifEmpty { ConsoleFolderAliases.defaultAliasesFor(c.id) },
                        // Only a picked colour: the family default follows the app, not the file.
                        color = c.color.ifBlank { null },
                        favourites = starred[c.id].orEmpty().sorted()
                    )
                }
            )
        }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        File(dir, "dogmatix-sources.json").apply { writeText(SourcesJson.serializeDocument(doc)) }
    }

    /**
     * Replaces every source with the contents of the document at [uri] (either the bundled
     * `consoles.json` layout or an export). Indexed files are cleared: the caller rescans.
     * Favourites in the document are added to the current ones (never replace them; a document
     * without favourites leaves them alone). Returns the number of consoles imported.
     */
    suspend fun importFromUri(uri: String): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri.toUri())?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("Cannot open $uri")
        // coleccion-gestion: what is replaced is kept so the history can put it back.
        val previous = runCatching { importUndo.get().snapshot(snapshotDocument()) }.getOrNull()
        val count = importFromText(text)
        runCatching { importUndo.get().recordImport(uri, count, previous) }
        count
    }

    /**
     * coleccion-gestion: [importFromUri] without the file — replaces every source with [text]
     * (also how undoing an import restores the previous ones). Indexed files are cleared.
     */
    suspend fun importFromText(text: String, allowEmpty: Boolean = false): Int = withContext(Dispatchers.IO) {
        val doc = SourcesJson.parseDocument(text)
        require(allowEmpty || doc.isNotEmpty()) { "No sources found in file" }

        // Release the torrents of everything we are about to drop.
        consoleDao.getAllConsoles().first().forEach { releaseSources(SourcesJson.parseUrlEntries(it.urls)) }
        databaseScrapingService.clearAllData()
        consoleDao.clearAll()
        manufacturerDao.clearAll()

        manufacturerDao.insertManufacturers(doc.map { ManufacturerEntity(id = it.id, name = it.name) })
        val consoles = doc.flatMap { m ->
            m.consoles.map { c ->
                val urls = c.urls.mapNotNull { u ->
                    val stored = when {
                        u.url.startsWith("magnet:") -> FileParsingUtils.optimizeMagnetUri(u.url)
                        u.url.startsWith("/") && !File(u.url).exists() -> null   // stale local torrent path
                        else -> u.url
                    }
                    stored?.let { u.copy(url = it) }
                }
                ConsoleEntity(
                    id = c.id, name = c.name, manufacturerId = m.id, urls = SourcesJson.serializeUrlEntries(urls),
                    shortName = c.shortName.orEmpty(), folderAliases = ConsoleAliasRegistry.serializeAliases(c.folderAliases),
                    color = c.color.orEmpty()
                )
            }
        }
        consoleDao.insertConsoles(consoles)
        favouritesRepository.addAll(doc.flatMap { m -> m.consoles.flatMap { c -> c.favourites.map { c.id to it } } })
        consoles.size
    }

    /**
     * coleccion-gestion: the sources exactly as stored (configured short names, aliases and
     * colours, local `.torrent` paths included), for the undo of an import.
     */
    suspend fun snapshotDocument(): List<SourceManufacturer> = manufacturers.first().map { m ->
        SourceManufacturer(
            id = m.id, name = m.name,
            consoles = m.consoles.map { c ->
                SourceConsole(
                    id = c.id, name = c.name, urls = c.urls,
                    shortName = c.shortName.ifBlank { null }, folderAliases = c.folderAliases, color = c.color.ifBlank { null }
                )
            }
        )
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private fun ConsoleEntity.toModel() = Console(
        id = id, name = name, urls = SourcesJson.parseUrlEntries(urls),
        shortName = shortName, folderAliases = ConsoleAliasRegistry.parseAliases(folderAliases), color = color
    )

    /** `#RRGGBB`, or empty for anything that is not a colour (= family default). */
    private fun normalizeColor(color: String): String = ColorMath.parseHex(color)?.let(ColorMath::toHex).orEmpty()

    private suspend fun normalizeUrl(url: String): String? {
        val trimmed = url.trim()
        return when {
            trimmed.isEmpty() -> null
            trimmed.startsWith("content://") -> copyTorrentToInternalStorage(trimmed)
            trimmed.startsWith("magnet:") -> FileParsingUtils.optimizeMagnetUri(trimmed)
            else -> trimmed
        }
    }

    private suspend fun copyTorrentToInternalStorage(uriString: String): String? = withContext(Dispatchers.IO) {
        try {
            val destFile = File(context.filesDir, "torrents/uploaded_${System.currentTimeMillis()}.torrent")
            destFile.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uriString.toUri())?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            destFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun releaseSources(entries: List<UrlEntry>) = entries.forEach { releaseSource(it.url) }

    /** Drops the libtorrent handle of a magnet / .torrent source and deletes our copy of the file. */
    private suspend fun releaseSource(url: String) {
        when {
            url.startsWith("magnet:") -> torrentHandleRegistry.releaseHandle(url)
            url.endsWith(".torrent") -> {
                torrentHandleRegistry.releaseHandle(url)
                withContext(Dispatchers.IO) {
                    File(url).takeIf { it.exists() && it.absolutePath.startsWith(context.filesDir.absolutePath) }?.delete()
                }
            }
        }
    }
}
