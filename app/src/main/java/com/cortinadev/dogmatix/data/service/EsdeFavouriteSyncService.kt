package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.dao.FavouriteDao
import com.cortinadev.dogmatix.data.local.entity.FavouriteEntity
import com.cortinadev.dogmatix.data.model.ResolvedDownloadPath
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.repository.FavouritesRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.EsdeFavouriteSync
import com.cortinadev.dogmatix.util.EsdeXml
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps Dogmatix's ★ and ES-DE's favourites in step (see [EsdeFavouriteSync]) once ES-DE has
 * been set up from Settings. Nothing to press for it in normal use:
 *  - a ★ changed here (library, details, a sources import…) is written into the gamelist of
 *    its system a moment later, for the files of that game that are on disk, and so is a
 *    starred game when its download finishes;
 *  - the full two-way sync — ES-DE's favourites become ★ here, removals on either side are
 *    carried over — runs once a few seconds after the app starts and at the end of
 *    "Configure ES-DE"; Settings also has a "Sync favourites with ES-DE" row.
 *
 * Only systems Dogmatix configured (a `custom_systems/es_systems.xml` block for the console
 * folder) take part. ES-DE reads gamelists when it starts, so it shows the changes after a
 * restart; when it saves, it merges entry by entry, so these edits survive it.
 */
@Singleton
class EsdeFavouriteSyncService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val consoleRepository: ConsoleRepository,
    private val pathResolver: ConsoleDownloadPathResolver,
    private val favourites: FavouritesRepository,
    private val favouriteDao: FavouriteDao,
    private val fileDao: DownloadableFileDao,
    private val downloadService: DownloadService,
    private val multiDiscService: MultiDiscService
) {

    data class Result(val starred: Int, val unstarred: Int, val marked: Int, val unmarked: Int) {
        val changedHere: Int get() = starred + unstarred
        val changedInEsde: Int get() = marked + unmarked
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val starChanges = Channel<Set<String>>(Channel.UNLIMITED)

    init {
        scope.launch { watchStars() }
        scope.launch { watchDownloads() }
        scope.launch { pushStars() }
        scope.launch {
            delay(STARTUP_DELAY_MS)
            runCatching { sync() }.onFailure { Log.w(TAG, "Startup sync failed: ${it.message}") }
        }
    }

    /**
     * Two-way sync of every system. Null when ES-DE is not set up; throws
     * [FrontendSetupException.FolderNotWritable] when its folder is no longer usable.
     */
    suspend fun sync(): Result? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val uri = settingsRepository.esdeDirectory.first().takeIf { it.isNotBlank() } ?: return@withLock null
            val esde = open(uri) ?: throw FrontendSetupException.FolderNotWritable()
            // The last sync only counts for the same ES-DE folder: another one starts afresh.
            val folderMark = FOLDER_MARK + uri
            val known = settingsRepository.esdeFavouritesSynced.first().let { if (folderMark in it) it - folderMark else emptySet() }
            val starredKeys = favouriteDao.getAll().mapTo(HashSet()) { it.key }
            val synced = HashSet<String>()
            val done = HashSet<String>()
            val star = mutableListOf<Pair<String, String>>()
            val unstar = mutableListOf<Pair<String, String>>()
            var marked = 0
            var unmarked = 0
            for (system in systemsOf(esde)) {
                val state = runCatching { load(esde, system, starredKeys) }
                    .onFailure { Log.w(TAG, "Skipping ${system.name}: ${it.message}") }
                    .getOrNull() ?: continue
                val prefix = "${system.name}|"
                val plan = EsdeFavouriteSync.reconcile(
                    state.gamelist, state.onDisk, state.rows,
                    known.filter { it.startsWith(prefix) }.mapTo(HashSet()) { it.removePrefix(prefix) }
                )
                if (plan.gamelist != null && runCatching { write(esde, system, state, plan.gamelist) }.isFailure) continue
                plan.star.mapTo(star) { it.consoleId to it.fileName }
                plan.unstar.mapTo(unstar) { it.consoleId to it.fileName }
                marked += plan.marked
                unmarked += plan.unmarked
                plan.synced.mapTo(synced) { prefix + it }
                done += system.name
            }
            val starred = favourites.addAll(star)
            favourites.removeAll(unstar)
            // Systems that could not be read keep what they had.
            settingsRepository.setEsdeFavouritesSynced(known.filterNot { it.substringBefore('|') in done }.toSet() + synced + folderMark)
            Result(starred, unstar.distinct().size, marked, unmarked)
        }
    }

    // ---- ★ changed here → ES-DE --------------------------------------------------------------

    /** Every change to the `favourites` table, as the `consoleId|fileName` keys that flipped. */
    private suspend fun watchStars() {
        var previous: Set<String>? = null
        favouriteDao.observeAll().map { list -> list.mapTo(HashSet()) { it.key } }.collect { current ->
            val before = previous
            previous = current
            if (before == null) return@collect   // the first emission is what was already there
            val flipped = (current - before) + (before - current)
            if (flipped.isNotEmpty()) starChanges.trySend(flipped)
        }
    }

    /**
     * A starred game that has just landed on disk is marked in ES-DE like a ★ changed here. Only
     * starred ones: for any other game the push would un-mark what ES-DE has and Dogmatix lacks,
     * which is the full sync's to decide. Waits for the multi-disc step, so the discs of a set
     * are already in their `<game>.m3u/` folder.
     */
    private suspend fun watchDownloads() {
        multiDiscService.settled.collect { outcome ->
            val fileName = outcome.fileName
            runCatching {
                val file = downloadService.entityFor(fileName) ?: fileDao.getFileByFileName(fileName) ?: return@collect
                val starred = favouriteDao.getAll().mapTo(HashSet()) { it.key }
                val game = fileDao.getGameFileNames(file.consoleId, file.groupKey).ifEmpty { listOf(file.fileName) }
                if (game.any { FavouriteEntity.key(file.consoleId, it) in starred }) {
                    starChanges.trySend(setOf(FavouriteEntity.key(file.consoleId, file.fileName)))
                }
            }
        }
    }

    /** Writes the flipped ★ into ES-DE, a few at a time: pressing SELECT down a list is one write per system. */
    private suspend fun pushStars() {
        for (first in starChanges) {
            delay(PUSH_DELAY_MS)
            val keys = HashSet(first)
            while (true) keys += starChanges.tryReceive().getOrNull() ?: break
            runCatching { push(keys) }.onFailure { Log.w(TAG, "Writing favourites to ES-DE failed: ${it.message}") }
        }
    }

    private suspend fun push(flipped: Set<String>) = mutex.withLock {
        val uri = settingsRepository.esdeDirectory.first().takeIf { it.isNotBlank() } ?: return@withLock
        val esde = open(uri) ?: return@withLock
        val consoles = flipped.mapTo(HashSet()) { it.substringBefore('|') }
        val starredKeys = favouriteDao.getAll().mapTo(HashSet()) { it.key }
        for (system in systemsOf(esde).filter { s -> s.consoleIds.any { it in consoles } }) {
            val state = runCatching { load(esde, system, starredKeys) }.getOrNull() ?: continue
            val plan = EsdeFavouriteSync.push(state.gamelist, state.onDisk, state.rows, flipped)
            plan.gamelist?.let { runCatching { write(esde, system, state, it) } }
        }
    }

    // ---- Reading and writing one system ------------------------------------------------------

    /**
     * What one run reads once: the ES-DE folder, its `gamelists/` children and the download
     * root's children, by lower-case name (empty when there are none).
     */
    private class Esde(val dir: DocumentFile, val gamelists: Map<String, StorageHelper.Child>, val consoleFolders: Map<String, StorageHelper.Child>)

    /** An ES-DE system Dogmatix configured, with the consoles that download into its folder. */
    private class EsdeSystem(val name: String, val folder: String, val consoleIds: List<String>, val extensions: List<String>)

    private class State(
        val gamelist: String?,
        /** The system's own folder under `gamelists/`, when it exists. */
        val gamelistDir: Uri?,
        val onDisk: List<String>,
        val rows: List<EsdeFavouriteSync.Row>
    )

    private suspend fun open(uri: String): Esde? {
        val dir = StorageHelper.getDocumentFile(context, uri)
            ?.takeIf { runCatching { it.isDirectory && it.canWrite() }.getOrDefault(false) } ?: return null
        val gamelists = FrontendFolders.find(context, dir.uri, "gamelists")?.takeIf { it.isDirectory }
        val root = FrontendFolders.root(context, settingsRepository.downloadDirectory.first())
        return Esde(
            dir,
            gamelists?.let { FrontendFolders.children(context, it.uri) }.orEmpty(),
            root?.let { FrontendFolders.children(context, it) }.orEmpty()
        )
    }

    /** Console folders in the shared ROM tree that have a Dogmatix-configured ES-DE system. */
    private suspend fun systemsOf(esde: Esde): List<EsdeSystem> {
        val custom = FrontendFolders.readIfPresent(context, esde.dir.uri, "custom_systems/es_systems.xml") ?: return emptyList()
        val consoles = consoleRepository.getAllConsoles().first()
        val resolved = pathResolver.resolveAll(settingsRepository, consoles.map { it.id })
        return consoles
            .groupBy { console ->
                // Root and custom folders are not part of ES-DE's ROM tree (same rule as the setup).
                resolved.getValue(console.id).takeIf {
                    it.source == ResolvedDownloadPath.Source.DETECTED || it.source == ResolvedDownloadPath.Source.WILL_CREATE
                }?.subPath.orEmpty()
            }
            .filterKeys { it.isNotEmpty() }
            .mapNotNull { (folder, members) ->
                val block = EsdeXml.systemBlock(custom, folder) ?: return@mapNotNull null
                EsdeSystem(
                    name = EsdeXml.tagText(block, "name")?.lowercase() ?: folder.lowercase(),
                    folder = folder,
                    consoleIds = members.map { it.id },
                    extensions = EsdeFavouriteSync.systemExtensions(block)
                )
            }
    }

    /** Throws when the gamelist is there but cannot be read: the system is then left alone. */
    private suspend fun load(esde: Esde, system: EsdeSystem, starredKeys: Set<String>): State {
        val gamelistDir = esde.gamelists[system.name]?.takeIf { it.isDirectory }
        val gamelist = gamelistDir?.let { FrontendFolders.readIfPresent(context, it.uri, GAMELIST) }
        val folder = esde.consoleFolders[system.folder.lowercase()]?.takeIf { it.isDirectory }
        val onDisk = folder?.let { StorageHelper.listChildren(context, it.uri) }.orEmpty()
            .map { it.name }
            .filter { EsdeFavouriteSync.listsAsGame(it, system.extensions) }
        val rows = system.consoleIds.flatMap { consoleId ->
            fileDao.getFileNamesAndGroups(consoleId).map {
                EsdeFavouriteSync.Row(consoleId, it.fileName, it.groupKey, "$consoleId|${it.fileName}" in starredKeys)
            }
        }
        return State(gamelist, gamelistDir?.uri, onDisk, rows)
    }

    private fun write(esde: Esde, system: EsdeSystem, state: State, content: String) {
        val dir = state.gamelistDir?.let { DocumentFile.fromTreeUri(context, it) }
            ?: StorageHelper.createDirectory(esde.dir, "gamelists/${system.name}")
            ?: throw IOException("Could not create gamelists/${system.name}")
        // Read as missing but there after all (a listing that failed quietly): never replace it with ours.
        if (state.gamelist == null && StorageHelper.findFile(dir, GAMELIST) != null) throw IOException("$GAMELIST appeared")
        StorageHelper.writeTextSafely(context, dir, "", GAMELIST, content)
    }

    companion object {
        private const val TAG = "EsdeFavouriteSync"
        private const val GAMELIST = "gamelist.xml"
        /** Leaves the first seconds after launch to the UI and the library index. */
        private const val STARTUP_DELAY_MS = 8_000L
        private const val PUSH_DELAY_MS = 1_500L
        /** Entry of the synced set naming the ES-DE folder it belongs to (`system|path` entries never start with it). */
        private const val FOLDER_MARK = "@folder="
    }
}
