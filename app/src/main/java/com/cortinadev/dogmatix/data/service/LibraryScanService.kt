package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import com.cortinadev.dogmatix.data.local.dao.ConsoleDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.CatalogRow
import com.cortinadev.dogmatix.util.CollectionDuplicates
import com.cortinadev.dogmatix.util.CollectionGames
import com.cortinadev.dogmatix.util.CollectionHealth
import com.cortinadev.dogmatix.util.ConsoleFolderAliases
import com.cortinadev.dogmatix.util.DiskFile
import com.cortinadev.dogmatix.util.DiskFolder
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.LibraryKeys
import com.cortinadev.dogmatix.util.LibraryMoveRules
import com.cortinadev.dogmatix.util.LibraryRoot
import com.cortinadev.dogmatix.util.LibrarySnapshot
import com.cortinadev.dogmatix.util.MultiDisc
import com.cortinadev.dogmatix.util.TrashRules
import com.cortinadev.dogmatix.util.VersionGrouping
import com.cortinadev.dogmatix.util.VolumeUsage
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LibraryScanService"

/** A scan in progress: files found so far and the console folder being read. */
data class ScanProgress(val files: Int, val folder: String, val partial: Boolean)

/**
 * Reads the ROM folders for the Collection tools: the download directory (each first-level folder
 * matched to a console, like [LibraryIndexService]) and the per-console directories, two folder
 * levels below each console folder so `<game>.m3u/` sets are seen. The result ([snapshot]) also
 * carries what the catalogue says about those games and the usage of each storage volume.
 *
 * On demand ([analyze], cancelable), cached on disk so opening the tab never walks the library,
 * and refreshed per console after downloads finish. Deleting and moving go through
 * [CollectionFileOps], which tells this service what changed.
 */
@OptIn(FlowPreview::class)
@Singleton
class LibraryScanService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val consoleDao: ConsoleDao,
    private val fileDao: DownloadableFileDao,
    private val downloadService: DownloadService,
    private val gson: Gson
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheFile = File(context.filesDir, "collection_snapshot.json")
    /** One walk at a time: an analysis and a refresh after a download never interleave. */
    private val walkMutex = Mutex()
    private var job: Job? = null

    private val _snapshot = MutableStateFlow<LibrarySnapshot?>(null)
    val snapshot: StateFlow<LibrarySnapshot?> = _snapshot.asStateFlow()

    /** Null while idle. */
    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = _progress.asStateFlow()

    /** Consoles that received a download since the last refresh. */
    private val dirtyConsoles = ConcurrentHashMap.newKeySet<String>()
    private val dirty = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The cache file is rewritten once a batch of changes settles, not once per change. */
    private val saveRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        scope.launch {
            _snapshot.compareAndSet(null, load())
            // Another ROMs folder makes the analysis someone else's: it waits for a new one.
            combine(settingsRepository.downloadDirectory, settingsRepository.consoleDownloadDirectories) { root, custom ->
                (custom.values + root).filter { it.isNotBlank() }.toSet()
            }.distinctUntilChanged().collect { folders ->
                val current = _snapshot.value ?: return@collect
                if (current.roots.mapTo(HashSet()) { it.uri } != folders && job?.isActive != true) {
                    _snapshot.value = null
                    runCatching { cacheFile.delete() }
                }
            }
        }
        scope.launch {
            downloadService.finished.collect { fileName ->
                downloadService.entityFor(fileName)?.consoleId?.let { dirtyConsoles += it }
                dirty.tryEmit(Unit)
            }
        }
        scope.launch { saveRequests.debounce(2_000).collect { _snapshot.value?.let(::save) } }
        // A burst of finished downloads refreshes once, and only the consoles they went to.
        scope.launch {
            dirty.debounce(5_000).collect {
                if (_snapshot.value == null || job?.isActive == true) return@collect
                val consoles = dirtyConsoles.toSet().also { dirtyConsoles.removeAll(it) }
                if (consoles.isNotEmpty()) walkMutex.withLock { runCatching { scan(consoles) }.onFailure { Log.w(TAG, "Refresh failed: ${it.message}") } }
            }
        }
    }

    /** Reads the whole library again (the "Analyze" button). A running analysis is left alone. */
    fun analyze() {
        if (job?.isActive == true) return
        job = scope.launch {
            walkMutex.withLock {
                runCatching { scan(null) }.onFailure { if (it !is kotlinx.coroutines.CancellationException) Log.e(TAG, "Scan failed", it) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /**
     * Drops what [CollectionFileOps] deleted or moved from the snapshot right away (the screens
     * update at once) and re-reads the consoles it touched in the background.
     */
    fun applyChanges(removedUris: Set<String>, removedFolders: Set<String> = emptySet(), touchedConsoles: Set<String> = emptySet()) {
        _snapshot.update { current ->
            current?.copy(
                files = current.files.filterNot { it.uri in removedUris },
                folders = current.folders.filterNot { it.uri in removedFolders },
                // Freed space shows at once (one statfs per volume).
                volumes = volumes(current.roots.map { it.uri })
            )
        }
        if (touchedConsoles.isNotEmpty()) {
            dirtyConsoles += touchedConsoles
            dirty.tryEmit(Unit)
        }
        saveRequests.tryEmit(Unit)
    }

    /**
     * Drops from the snapshot what was found through a library folder that cannot be read any
     * more (deleted or moved, grant lost, card out), so a cached analysis never offers to delete
     * or move what is not there; the folder then shows in Health, as after a new analysis.
     * [candidates]: the trees to check (an operation that found them blocked), or null for every
     * tree the snapshot still reads as accessible (opening Collection). One provider query per
     * tree, the same test the analysis uses; call it off the main thread.
     */
    fun forgetUnreachable(candidates: Collection<String>? = null) {
        val current = _snapshot.value ?: return
        val roots = candidates?.toSet() ?: current.roots.filter { it.accessible }.mapTo(HashSet()) { it.uri }
        // What is no SAF tree cannot be judged here: it stays.
        val unreachable = roots.filterTo(HashSet()) { uri -> rootDocument(uri)?.let { SafDocs.stat(context, it) == null } == true }
        if (unreachable.isEmpty()) return
        Log.i(TAG, "Library folders no longer readable, dropped from the analysis: $unreachable")
        _snapshot.update { it?.withoutRoots(unreachable) }
        saveRequests.tryEmit(Unit)
    }

    /** Walks everything ([only] null) or just the folders of [only] and merges them into the snapshot. */
    private suspend fun scan(consoles: Set<String>?) {
        val previous = _snapshot.value
        if (consoles != null && previous == null) return
        // Without per-console folders every download lands loose in the root: only a full walk sees it.
        val only = consoles?.takeIf { settingsRepository.separateByConsole.first() }
        _progress.value = ScanProgress(0, "", partial = only != null)
        try {
            val root = settingsRepository.downloadDirectory.first()
            val custom = settingsRepository.consoleDownloadDirectories.first().filterValues { it.isNotBlank() }
            val consoleIds = consoleDao.getAllConsoles().first().map { it.id }
            val walk = Walk(consoleIds, only)
            val roots = ArrayList<LibraryRoot>()

            val customDocs = custom.mapNotNull { (consoleId, uri) ->
                val doc = rootDocument(uri)
                if (doc == null) roots += LibraryRoot(uri, consoleId, accessible = false, permission = hasGrant(uri))
                doc?.let { Triple(consoleId, uri, it) }
            }
            walk.skip += customDocs.map { DocumentsContract.getDocumentId(it.third) }
            for ((consoleId, uri, doc) in customDocs) {
                val entries = list(doc)
                roots += LibraryRoot(uri, consoleId, accessible = entries != null, permission = hasGrant(uri))
                if (entries == null || (only != null && consoleId !in only)) continue
                val name = DocumentsContract.getTreeDocumentId(uri.toUri()).substringAfterLast(':').substringAfterLast('/').ifEmpty { consoleId }
                walk.folders += DiskFolder(name, "", doc.toString(), "", consoleId, name, uri, 0, entries.isEmpty())
                walk.dir(doc, entries, consoleId, name, uri, "", 0)
            }
            if (root.isNotBlank()) {
                val doc = rootDocument(root)
                val entries = doc?.let { list(it) }
                roots += LibraryRoot(root, null, accessible = entries != null, permission = hasGrant(root))
                if (doc != null && entries != null) walk.root(doc, entries, root)
            }

            // A refresh replaces what it walked: the console folders of [only], not the loose files of the root.
            val files = if (only != null && previous != null) {
                previous.files.filterNot { it.consoleId in only && it.topFolder.isNotEmpty() } + walk.files
            } else walk.files
            val folders = if (only != null && previous != null) {
                previous.folders.filterNot { it.consoleId in only } + walk.folders
            } else walk.folders
            _progress.value = ScanProgress(files.size, "", partial = only != null)
            val (matched, catalog) = matchCatalogue(files, previous?.catalog.orEmpty(), only)
            val read = readSheets(matched, previous)
            val hashed = hash(read, previous)
            val snapshot = LibrarySnapshot(
                files = hashed,
                folders = folders,
                volumes = volumes(roots.map { it.uri }),
                roots = if (only != null && previous != null) previous.roots else roots,
                catalog = catalog,
                catalogGames = fileDao.gameCountsByConsole().associate { it.consoleId to it.games },
                catalogExtensions = fileDao.extensionsByConsole()
                    .groupBy({ it.consoleId }, { it.fileExtension.trim().trimStart('.').lowercase(Locale.ROOT) })
                    .mapValues { (_, exts) -> exts.filter { it.isNotEmpty() }.toSet() },
                scannedAt = if (only != null && previous != null) previous.scannedAt else System.currentTimeMillis()
            )
            _snapshot.value = snapshot
            save(snapshot)
            Log.i(TAG, "Scanned ${snapshot.files.size} files in ${snapshot.folders.size} folders" + (only?.let { " (refresh of $it)" } ?: ""))
        } finally {
            _progress.value = null
        }
    }

    /**
     * Never part of the library: hidden names (the trash, `.dogmatix-trash`, and other dot
     * folders) and the half-copied `*.dogmatix-part` of a library move in progress.
     */
    private fun skipped(name: String): Boolean = TrashRules.isHidden(name) || LibraryMoveRules.isPart(name)

    /** State of one walk. Folders are read once each, with one provider query per folder. */
    private inner class Walk(private val consoleIds: List<String>, private val only: Set<String>?) {
        val files = ArrayList<DiskFile>()
        val folders = ArrayList<DiskFolder>()
        /** Document ids walked as per-console directories: never walked again from the root. */
        val skip = HashSet<String>()

        /** The download directory: each first-level folder is a console folder; loose files belong to none. */
        suspend fun root(doc: Uri, entries: List<Entry>, rootUri: String) {
            for (entry in entries) {
                if (skipped(entry.name)) continue
                if (entry.isDirectory) {
                    if (entry.documentId in skip) continue
                    val consoleId = consoleIds.firstOrNull { ConsoleFolderAliases.matches(it, entry.name) }
                    if (only != null && consoleId !in only) continue
                    val children = list(entry.uri) ?: continue
                    folders += DiskFolder(entry.name, "", entry.uri.toString(), doc.toString(), consoleId, entry.name, rootUri, 0, children.isEmpty())
                    dir(entry.uri, children, consoleId, entry.name, rootUri, "", 0)
                } else if (only == null) {
                    files += file(entry, doc, null, "", rootUri, "", 0)
                }
            }
        }

        /** A console folder ([depth] 0) or a folder below it, down to [CollectionHealth.MAX_DEPTH]. */
        suspend fun dir(doc: Uri, entries: List<Entry>, consoleId: String?, top: String, rootUri: String, prefix: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            _progress.value = ScanProgress(files.size, top, partial = only != null)
            for (entry in entries) {
                if (skipped(entry.name)) continue
                if (entry.isDirectory) {
                    if (depth + 1 > CollectionHealth.MAX_DEPTH || entry.documentId in skip) continue
                    val children = list(entry.uri) ?: continue
                    val path = prefix + entry.name
                    folders += DiskFolder(entry.name, path, entry.uri.toString(), doc.toString(), consoleId, top, rootUri, depth + 1, children.isEmpty())
                    dir(entry.uri, children, consoleId, top, rootUri, "$path/", depth + 1)
                } else {
                    files += file(entry, doc, consoleId, top, rootUri, prefix, depth)
                }
            }
        }

        private fun file(entry: Entry, parent: Uri, consoleId: String?, top: String, rootUri: String, prefix: String, depth: Int) = DiskFile(
            name = entry.name,
            path = prefix + entry.name,
            size = entry.size,
            lastModified = entry.modified,
            uri = entry.uri.toString(),
            parentUri = parent.toString(),
            consoleId = consoleId,
            topFolder = top,
            rootUri = rootUri,
            depth = depth,
            groupKey = VersionGrouping.groupKey(entry.name)
        )
    }

    /**
     * Ties each file to its catalogue row (same name, or same name without extension for what an
     * archive unpacked to). A loose file in the root takes the console of its row when only one
     * console lists it. Returns the files and the catalogue rows of the games on disk.
     */
    private suspend fun matchCatalogue(files: List<DiskFile>, previous: List<CatalogRow>, only: Set<String>?): Pair<List<DiskFile>, List<CatalogRow>> {
        val walked = { file: DiskFile -> only == null || (file.consoleId in only && file.topFolder.isNotEmpty()) }
        val wanted = files.filter { CollectionGames.isGameFile(it) && walked(it) }
        val rows = ArrayList<CatalogRow>()
        wanted.filter { it.consoleId != null }.groupBy { it.consoleId!! }.forEach { (consoleId, inConsole) ->
            inConsole.map { it.groupKey }.distinct().chunked(500).forEach { keys -> rows += fileDao.catalogRowsForConsole(consoleId, keys) }
        }
        val loose = wanted.filter { it.consoleId == null }
        loose.map { it.groupKey }.distinct().chunked(500).forEach { keys -> rows += fileDao.catalogRowsForGroups(keys) }

        val byGroup = rows.groupBy { it.groupKey }
        val catalog = ((if (only != null) previous else emptyList()) + rows).distinctBy { it.id }
        val matched = files.map { file ->
            if (!walked(file)) return@map file
            val candidates = byGroup[file.groupKey].orEmpty().filter { file.consoleId == null || it.consoleId == file.consoleId }
            if (candidates.isEmpty()) return@map file
            val name = file.name.lowercase(Locale.ROOT)
            val base = LibraryKeys.baseName(name)
            val exact = candidates.filter { leaf(it.fileName) == name }
            val hits = exact.ifEmpty { candidates.filter { LibraryKeys.baseName(leaf(it.fileName)) == base } }
            val consoleId = file.consoleId ?: hits.map { it.consoleId }.distinct().singleOrNull()
            file.copy(catalogId = hits.firstOrNull { it.consoleId == consoleId }?.id, consoleId = consoleId)
        }
        // Rows of loose files that matched no console stay out: they only bloat the cache.
        val consoles = matched.mapNotNullTo(HashSet()) { it.consoleId }
        return matched to catalog.filter { it.consoleId in consoles }
    }

    /** Reads what each playlist and sheet refers to (small text files only); unchanged ones keep what was read. */
    private fun readSheets(files: List<DiskFile>, previous: LibrarySnapshot?): List<DiskFile> {
        val known = previous?.files?.associateBy { it.uri }.orEmpty()
        var read = 0
        return files.map { file ->
            val ext = file.extension
            if (ext != "m3u" && ext != "cue" && ext != "gdi") return@map file
            known[file.uri]?.takeIf { it.size == file.size && it.lastModified == file.lastModified && it.references != null }
                ?.let { return@map file.copy(references = it.references) }
            if (file.size <= 0 || file.size > MAX_SHEET_BYTES || read >= MAX_SHEETS) return@map file
            read++
            val text = runCatching {
                context.contentResolver.openInputStream(file.uri.toUri())?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull() ?: return@map file
            val references = when (ext) {
                "cue" -> MultiDisc.cueFiles(text)
                "gdi" -> MultiDisc.gdiFiles(text)
                else -> text.lineSequence().map { it.trim().removePrefix("\uFEFF") }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            }
            file.copy(references = references)
        }
    }

    /**
     * A sampled hash (size, plus the first, middle and last 64 KiB; small files whole) for game
     * files that share their size with another one, in any console: what tells a renamed copy, or
     * the same set in another console's folder, from another game. At most [MAX_HASHES] new ones
     * per scan, spent in [CollectionDuplicates.hashCandidates] order; unchanged files keep the
     * hash of the previous scan.
     */
    private suspend fun hash(files: List<DiskFile>, previous: LibrarySnapshot?): List<DiskFile> {
        val known = previous?.files?.associateBy { it.uri }.orEmpty()
        val hashes = HashMap<String, String?>()
        var computed = 0
        for (file in CollectionDuplicates.hashCandidates(files)) {
            val kept = known[file.uri]?.takeIf { it.size == file.size && it.lastModified == file.lastModified }?.hash
            if (kept != null) {
                hashes[file.uri] = kept
                continue
            }
            if (computed >= MAX_HASHES) continue
            currentCoroutineContext().ensureActive()
            computed++
            hashes[file.uri] = runCatching { sampledHash(file.uri.toUri(), file.size) }.getOrNull()
        }
        return files.map { file -> if (file.uri in hashes) file.copy(hash = hashes[file.uri]) else file }
    }

    private fun sampledHash(uri: Uri, size: Long): String? {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(size.toString().toByteArray())
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                val buffer = ByteBuffer.allocate(HASH_CHUNK)
                val offsets = if (size <= WHOLE_HASH_BYTES) (0 until size step HASH_CHUNK.toLong()).toList()
                else listOf(0L, size / 2, (size - HASH_CHUNK).coerceAtLeast(0))
                for (offset in offsets) {
                    buffer.clear()
                    channel.position(offset)
                    while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
                    buffer.flip()
                    digest.update(buffer)
                }
            }
        } ?: return null
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Usage of every mounted volume; [libraryUris] mark the ones the library lives on. */
    private fun volumes(libraryUris: List<String>): List<VolumeUsage> {
        val libraryVolumes = libraryUris.mapNotNull { uri ->
            runCatching { DocumentsContract.getTreeDocumentId(uri.toUri()).substringBefore(':').lowercase(Locale.ROOT) }.getOrNull()
        }.toSet()
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return manager.storageVolumes.filter { it.state == Environment.MEDIA_MOUNTED }.mapNotNull { volume ->
            val id = if (volume.isPrimary) VolumeUsage.PRIMARY else volume.uuid ?: return@mapNotNull null
            val dir = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> volume.directory
                volume.isPrimary -> Environment.getExternalStorageDirectory()
                else -> File("/storage/$id")
            } ?: return@mapNotNull null
            val stat = runCatching { StatFs(dir.path) }.getOrNull() ?: return@mapNotNull null
            VolumeUsage(id, volume.getDescription(context), volume.isRemovable, stat.totalBytes, stat.availableBytes, id.lowercase(Locale.ROOT) in libraryVolumes)
        }
    }

    private fun hasGrant(uri: String): Boolean = runCatching {
        val parsed = uri.toUri()
        context.contentResolver.persistedUriPermissions.any { it.uri == parsed && it.isReadPermission && it.isWritePermission }
    }.getOrDefault(false)

    /** Document URI of the folder a persisted tree URI points at. */
    private fun rootDocument(treeUri: String): Uri? = runCatching {
        val uri = treeUri.toUri()
        if (!DocumentsContract.isTreeUri(uri)) return null
        DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    }.getOrNull()

    /** One child of a folder, read with its siblings in a single provider query. */
    private data class Entry(val name: String, val isDirectory: Boolean, val uri: Uri, val documentId: String, val size: Long, val modified: Long)

    /**
     * The children of [dir] with name, type, size and date from one query; null when the folder
     * cannot be listed (gone, or the grant was lost). Hidden files are included (they keep a
     * folder from counting as empty); the walk skips them.
     */
    private fun list(dir: Uri): List<Entry>? = try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getDocumentId(dir))
        context.contentResolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
            val out = ArrayList<Entry>(cursor.count)
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                out += Entry(
                    name = name,
                    isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    uri = DocumentsContract.buildDocumentUriUsingTree(dir, id),
                    documentId = id,
                    size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                    modified = if (cursor.isNull(4)) 0L else cursor.getLong(4)
                )
            }
            out
        }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot list $dir: ${e.message}")
        null
    }

    private fun load(): LibrarySnapshot? = runCatching {
        if (!cacheFile.exists()) return null
        gson.fromJson(cacheFile.readText(), LibrarySnapshot::class.java)?.takeIf { it.version == LibrarySnapshot.VERSION }
    }.onFailure { Log.w(TAG, "Cached analysis unreadable: ${it.message}") }.getOrNull()

    private fun save(snapshot: LibrarySnapshot) {
        runCatching {
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(gson.toJson(snapshot))
            tmp.renameTo(cacheFile)
        }.onFailure { Log.w(TAG, "Could not cache the analysis: ${it.message}") }
    }

    private fun leaf(fileName: String): String =
        FileParsingUtils.decodeUrlEncodedFileName(fileName).replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        const val MAX_SHEET_BYTES = 256 * 1024L
        const val MAX_SHEETS = 3_000
        const val WHOLE_HASH_BYTES = 4L * 1024 * 1024
        const val HASH_CHUNK = 64 * 1024
        const val MAX_HASHES = 400
    }
}
