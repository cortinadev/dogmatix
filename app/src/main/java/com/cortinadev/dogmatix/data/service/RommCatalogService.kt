package com.cortinadev.dogmatix.data.service

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.RommDao
import com.cortinadev.dogmatix.data.local.dao.RommLibraryFile
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.RommGameEntity
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.CertPin
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.LibraryKeys
import com.cortinadev.dogmatix.util.RommCatalogRom
import com.cortinadev.dogmatix.util.RommCovers
import com.cortinadev.dogmatix.util.RommEntry
import com.cortinadev.dogmatix.util.RommGameInfo
import com.cortinadev.dogmatix.util.RommIndex
import com.cortinadev.dogmatix.util.RommMatching
import com.cortinadev.dogmatix.util.RommOwnedGame
import com.cortinadev.dogmatix.util.RommPlayState
import com.cortinadev.dogmatix.util.RommSource
import com.cortinadev.dogmatix.util.RommUserProps
import com.cortinadev.dogmatix.util.VersionGrouping
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RommCatalog"
private const val PAGE_SIZE = 250
/** Safety stop for a server that keeps sending full pages: 250 × 400 = 100 000 ROMs. */
private const val MAX_PAGES = 400
/** Coming back to the app re-reads the catalogue at most this often; "Refresh" always does. */
private const val AUTO_REFRESH_MS = 30L * 60 * 1000
/** "Upload what's missing" re-reads the catalogue first when it is older than this. */
private const val UPLOAD_FRESH_MS = 5L * 60 * 1000
/** RomM lists an upload once it has scanned it: wait a little before reading it back. */
private const val AFTER_UPLOAD_MS = 20_000L
/** …and once more after RomM's own rescan of a changed folder (5 min by default). */
private const val AFTER_UPLOAD_AGAIN_MS = 6L * 60 * 1000

/** Server and `Authorization` header for Coil requests of the RomM server's images. */
data class RommImageAuth(val baseUrl: String, val header: String) {
    fun covers(url: String): Boolean = url.startsWith("$baseUrl/", ignoreCase = true)
}

data class RommCatalogState(
    /** URL, token and at least one console → platform mapping are set. */
    val configured: Boolean = false,
    val refreshing: Boolean = false,
    val games: Int = 0,
    /** When the catalogue was last read; 0 = never. */
    val refreshedAt: Long = 0L,
    val error: String? = null
)

/** A library row paired with a RomM id. */
data class RommLibraryRef(val consoleId: String, val fileName: String, val groupKey: String, val name: String)

/**
 * The RomM server's catalogue: every ROM of the platforms mapped in Settings → RomM, with its
 * files, hashes, cover and the account's play status, kept in Room (`romm_games`) and as an
 * in-memory [RommIndex] the library rows are paired with ([RommMatching]). Read again when the
 * RomM settings change, when the app comes back to the foreground (at most every
 * [AUTO_REFRESH_MS]), after uploads and on demand.
 */
@Singleton
class RommCatalogService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val client: RommClient,
    private val dao: RommDao,
    private val libraryIndex: LibraryIndexService,
    private val uploads: RommUploadService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()

    private data class Config(val url: String, val token: String, val map: Map<String, Int>) {
        val ready: Boolean get() = url.isNotEmpty() && token.isNotEmpty() && map.isNotEmpty()
        /** A digest of what the catalogue depends on; the token itself is never stored. */
        val signature: String
            get() = sha1("$url\n$token\n" + map.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" })
    }

    private val config = MutableStateFlow(Config("", "", emptyMap()))

    private val _index = MutableStateFlow(RommIndex.EMPTY)
    /** The catalogue to pair library rows with; empty while RomM is not set up or not read yet. */
    val index: StateFlow<RommIndex> = _index.asStateFlow()

    private val _state = MutableStateFlow(RommCatalogState())
    val state: StateFlow<RommCatalogState> = _state.asStateFlow()

    /**
     * What Coil needs to load this server's images; null when RomM is not set up.
     * Self-signed HTTPS also needs the TLS trust of the RomM transport in the app's ImageLoader
     * (`DogmatixApplication.newImageLoader`): that is the hook for it.
     */
    val imageAuth: StateFlow<RommImageAuth?> = config
        .map { c -> if (c.url.isNotEmpty() && c.token.isNotEmpty()) RommImageAuth(c.url, RommClient.authHeader(c.token)) else null }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The configured server (no trailing slash), "" when none. */
    val baseUrl: String get() = config.value.url

    init {
        scope.launch {
            combine(settings.rommUrl, settings.rommToken, settings.rommPlatformMap) { url, token, map ->
                Config(url.trim().trimEnd('/'), token.trim(), map)
            }.distinctUntilChanged().collectLatest(::onConfig)
        }
        scope.launch {
            // An upload landed: read the catalogue back so the marks and "what's missing" follow.
            uploads.uploads.map { all -> all.values.count { it.status == UploadStatus.DONE } }
                .distinctUntilChanged()
                .drop(1)
                .filter { it > 0 }
                .collectLatest {
                    delay(AFTER_UPLOAD_MS)
                    refreshNow()
                    delay(AFTER_UPLOAD_AGAIN_MS - AFTER_UPLOAD_MS)
                    refreshNow()
                }
        }
        scope.launch {
            // Trusting (or forgetting) the server's certificate in Settings → RomM: read it again
            // instead of keeping the refusal until the next change or "Refresh".
            settings.rommCertPin.distinctUntilChanged().drop(1).collect { if (config.value.ready) refreshNow() }
        }
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(ForegroundWatcher())
    }

    fun refresh() {
        if (_state.value.refreshing) return
        scope.launch { refreshNow() }
    }

    fun refreshIfStale() {
        scope.launch { refreshIfStaleNow() }
    }

    /** Reads the whole catalogue again. False when RomM is not set up or the server could not be read. */
    suspend fun refreshNow(): Boolean = refreshLock.withLock {
        val c = config.value
        if (!c.ready) return@withLock false
        _state.update { it.copy(refreshing = true, error = null) }
        try {
            val byPlatform = c.map.values.distinct().associateWith { fetchPlatform(it) }
            val now = System.currentTimeMillis()
            val rows = c.map.flatMap { (consoleId, platformId) ->
                byPlatform[platformId].orEmpty().map { toEntity(consoleId, platformId, it, now) }
            }
            // The settings changed while the server was read: what came back is for the old ones
            // (the change starts a refresh of its own).
            if (config.value != c) {
                _state.update { it.copy(refreshing = false) }
                return@withLock false
            }
            dao.replaceGames(rows)
            settings.setRommCatalogState(c.signature, now)
            _index.value = RommIndex(rows.map(::entryOf))
            _state.value = RommCatalogState(configured = true, games = rows.size, refreshedAt = now)
            Log.i(TAG, "RomM catalogue: ${rows.size} ROM(s) for ${c.map.size} console(s)")
            true
        } catch (e: CancellationException) {
            _state.update { it.copy(refreshing = false) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the RomM catalogue: ${e.javaClass.simpleName}")
            val reason = if (CertPin.isCertificateFailure(e)) context.getString(R.string.romm_catalog_cert_error)
            else (e.message ?: e.javaClass.simpleName).take(160)
            _state.update { it.copy(refreshing = false, error = reason) }
            false
        }
    }

    /** The server entry of a library game ([files]: every file of its versions, one console). */
    fun entryForGame(files: List<DownloadableFileEntity>, index: RommIndex = _index.value): RommEntry? {
        val first = files.firstOrNull() ?: return null
        if (index.isEmpty) return null
        files.firstNotNullOfOrNull { RommMatching.romIdFromUrl(baseUrl, it.downloadUrl) }?.let { id ->
            index.forRomId(id).let { entries -> entries.firstOrNull { it.consoleId == first.consoleId } ?: entries.firstOrNull() }?.let { return it }
        }
        return index.forGame(first.consoleId, first.groupKey, files.map { it.fileName })
    }

    /**
     * Whether a library game ([files]: every file of its versions) is on the server: one of its
     * rows comes from it, or any version pairs with a server ROM. False while RomM is not set up.
     */
    fun isOnServer(files: List<DownloadableFileEntity>, index: RommIndex = _index.value): Boolean {
        if (files.isEmpty() || imageAuth.value == null) return false
        val base = baseUrl
        if (files.any { RommSource.isDownloadFrom(base, it.downloadUrl) }) return true
        return entryForGame(files, index) != null
    }

    /** The server entry a star (console + file name) stands for, by file and else by title. */
    fun entryForStar(consoleId: String, fileName: String, index: RommIndex = _index.value): RommEntry? =
        index.forFile(consoleId, fileName) ?: index.forGame(consoleId, RommMatching.groupKeyOf(fileName), emptyList())

    /** The RomM cover of a library game, when RomM is set up and the game is on the server. */
    fun coverUrl(consoleId: String, fileName: String, name: String? = null): String? {
        val auth = imageAuth.value ?: return null
        val index = _index.value
        if (index.isEmpty) return null
        val entry = index.forFile(consoleId, fileName)
            ?: name?.let { index.forGame(consoleId, VersionGrouping.groupKey(it), emptyList()) }
            ?: return null
        return RommCovers.url(auth.baseUrl, entry.coverSmall.ifEmpty { entry.coverLarge })
    }

    suspend fun gameInfo(romId: Int): RommGameInfo = client.gameInfo(romId)

    /** Saves the play status; returns the props the server now holds. Throws when it could not. */
    suspend fun setPlayState(romId: Int, state: RommPlayState, current: RommUserProps): RommUserProps = withContext(Dispatchers.IO) {
        val saved = client.updateProps(romId, state.changesFrom(current)) ?: state.applyTo(current)
        dao.setUserStatus(romId, RommPlayState.of(saved).name)
        saved
    }

    /** [setPlayState] that outlives the screen (the details card closed with a change pending). */
    fun setPlayStateLater(romId: Int, state: RommPlayState, current: RommUserProps) {
        scope.launch {
            runCatching { setPlayState(romId, state, current) }
                .onFailure { Log.w(TAG, "Could not save the RomM status of ROM $romId: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * The games on disk (✓) of the mapped consoles that the server lacks under any version.
     * Rows that come from the server never count. Reads the catalogue first when it is not fresh;
     * throws when there is no catalogue to compare with.
     */
    suspend fun missingOnServer(): List<RommOwnedGame> = withContext(Dispatchers.IO) {
        val c = config.value
        if (!c.ready) return@withContext emptyList()
        if (System.currentTimeMillis() - _state.value.refreshedAt > UPLOAD_FRESH_MS && !refreshNow() && _index.value.isEmpty) {
            throw RommException(_state.value.error ?: "Could not read the RomM catalogue")
        }
        val owned = libraryIndex.ownedKeys.value
        val scopes = HashMap<String, Set<String>>()
        val games = dao.libraryFiles(c.map.keys.toList())
            .filter { !RommSource.isDownloadFrom(c.url, it.downloadUrl) && isOwned(it, owned, scopes) }
            .groupBy { it.consoleId to it.groupKey }
            .map { (key, files) -> RommOwnedGame(key.first, key.second, files.first().name, files.map { it.fileName }.distinct()) }
        // Uploaded in this session: on the server already, though RomM lists it only after a scan.
        val uploaded = uploads.uploads.value.filterValues { it.status == UploadStatus.DONE }.keys
        RommMatching.missingOnServer(games, _index.value).filterNot { game -> game.fileNames.any { it in uploaded } }
    }

    /** A library row for each RomM id the library has a game of (exact file first, else the title). */
    suspend fun libraryRefsFor(romIds: Collection<Int>): Map<Int, RommLibraryRef> = withContext(Dispatchers.IO) {
        val index = _index.value
        val out = HashMap<Int, RommLibraryRef>()
        romIds.distinct().flatMap { index.forRomId(it) }.groupBy { it.consoleId }.forEach { (consoleId, entries) ->
            val rows = entries.map { it.groupKey }.distinct().chunked(400).flatMap { dao.libraryFilesOfGames(consoleId, it) }
            val byGroup = rows.groupBy { it.groupKey }
            for (entry in entries) {
                if (entry.romId in out) continue
                val candidates = byGroup[entry.groupKey].orEmpty()
                val releases = entry.fileKeys.mapTo(HashSet(), RommMatching::releaseOfKey)
                val row = candidates.firstOrNull { RommMatching.fileKey(it.fileName) in entry.fileKeys }
                    ?: candidates.firstOrNull { RommMatching.releaseKey(it.fileName) in releases }
                    ?: candidates.minByOrNull { it.fileName }
                    ?: continue
                out[entry.romId] = RommLibraryRef(row.consoleId, row.fileName, row.groupKey, row.name)
            }
        }
        out
    }

    private suspend fun onConfig(c: Config) {
        val previous = config.value
        config.value = c
        if (!c.ready) {
            _index.value = RommIndex.EMPTY
            _state.value = RommCatalogState()
            if (settings.rommCatalogSignature.first().isNotEmpty()) {
                dao.clearGames()
                settings.setRommCatalogState("", 0L)
            }
            return
        }
        _state.update { it.copy(configured = true) }
        if (settings.rommCatalogSignature.first() == c.signature) {
            if (_index.value.isEmpty) loadFromRoom()
            refreshIfStaleNow()
            return
        }
        // Another server or account: what is cached says nothing about it (another mapping keeps
        // showing the old marks until the new catalogue is in).
        if (previous.url != c.url || previous.token != c.token) _index.value = RommIndex.EMPTY
        delay(1_500)   // settings arrive one keystroke / ◀ ▶ press at a time
        refreshNow()
    }

    private suspend fun loadFromRoom() {
        val rows = dao.allGames()
        _index.value = RommIndex(rows.map(::entryOf))
        _state.update { it.copy(games = rows.size, refreshedAt = settings.rommCatalogRefreshedAt.first()) }
    }

    private suspend fun refreshIfStaleNow() {
        if (!config.value.ready || _state.value.refreshing) return
        if (System.currentTimeMillis() - settings.rommCatalogRefreshedAt.first() >= AUTO_REFRESH_MS) refreshNow()
    }

    private suspend fun fetchPlatform(platformId: Int): List<RommCatalogRom> {
        val out = ArrayList<RommCatalogRom>()
        var offset = 0
        repeat(MAX_PAGES) {
            val page = client.catalogPage(platformId, offset, PAGE_SIZE)
            out += page.roms
            if (page.itemCount < PAGE_SIZE) return out
            offset += PAGE_SIZE
        }
        return out
    }

    private fun toEntity(consoleId: String, platformId: Int, rom: RommCatalogRom, now: Long): RommGameEntity {
        val entry = RommMatching.entryOf(consoleId, rom)
        return RommGameEntity(
            consoleId = consoleId,
            romId = rom.romId,
            platformId = platformId,
            fsName = rom.fsName,
            name = rom.name,
            groupKey = entry.groupKey,
            files = rom.fileNames.joinToString("\n"),
            hashes = rom.hashes.joinToString(" "),
            coverPath = rom.coverSmall,
            coverLargePath = rom.coverLarge,
            userStatus = entry.playState.name,
            fetchedAt = now
        )
    }

    private fun entryOf(row: RommGameEntity) = RommEntry(
        romId = row.romId,
        consoleId = row.consoleId,
        fsName = row.fsName,
        name = row.name,
        groupKey = row.groupKey,
        fileKeys = (listOf(row.fsName) + row.files.split('\n')).filter { it.isNotBlank() }.map(RommMatching::fileKey).toSet(),
        hashes = row.hashes.split(' ').filter { it.isNotBlank() }.toSet(),
        coverSmall = row.coverPath,
        coverLarge = row.coverLargePath,
        playState = RommPlayState.fromName(row.userStatus)
    )

    /** Same test as the ✓ of the library ([LibraryKeys.isOwned]), with each console's scopes worked out once. */
    private fun isOwned(row: RommLibraryFile, keys: Set<String>, scopes: MutableMap<String, Set<String>>): Boolean {
        if (keys.isEmpty()) return false
        val consoleScopes = scopes.getOrPut(row.consoleId) { LibraryKeys.scopesFor(row.consoleId) }
        val name = FileParsingUtils.decodeUrlEncodedFileName(row.fileName).lowercase()
        val base = LibraryKeys.baseName(name)
        return consoleScopes.any { "$it|$name" in keys || "$it|$base" in keys }
    }

    /** "Opening the app": the first activity coming to the foreground. */
    private inner class ForegroundWatcher : Application.ActivityLifecycleCallbacks {
        private var started = 0
        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0) refreshIfStale()
        }
        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}

private fun sha1(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
