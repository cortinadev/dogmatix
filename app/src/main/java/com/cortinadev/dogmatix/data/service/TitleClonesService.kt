package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.BuildConfig
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.model.VersionPrefs
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.util.TitleClones
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One library game across its translated titles (see [TitleClones]). For every console in the
 * library it fetches the system's Retool clone list (once a month, kept in `noBackupFilesDir`
 * because the library's grouping rests on it; a 404 leaves an empty file, no network keeps the
 * old copy or, with none, leaves the console as it is and tries again in 10 minutes), installs it
 * in [TitleClones] — new rows are written with their game's key from then on (SourceRowWriter) —
 * and re-keys the rows already indexed ([DownloadableFileDao.regroup]). A game's key is the title
 * it shows for the preferred region and languages ([TitleClones.gameKeys]), so the A→Z list sorts
 * by it. Runs on start, after every rescan that changed the library, when Settings → Library →
 * "Group translated titles" changes (off: every title is its own game again) and when the
 * preferred region or languages change. Never during a rescan, never blocking one.
 */
@Singleton
class TitleClonesService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: DownloadableFileDao,
    private val settings: SettingsRepository,
    private val rescanState: RescanStateHolder
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val mutex = Mutex()
    private val userAgent = "Dogmatix/${BuildConfig.VERSION_NAME}"

    private val _version = MutableStateFlow(0)
    /** Bumped when rows changed game, so lists and shelves query again. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** List name → when its download last failed: no retry for a while. */
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** Parsed lists, by file name and modification time (a rescan does not parse them again). */
    private val parsed = ConcurrentHashMap<String, Pair<Long, Map<String, String>>>()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // DataStore flows emit on any setting's change: only these ones regroup.
            val prefs = combine(settings.preferredRegion, settings.favoriteLanguages) { region, languages -> VersionPrefs(region, languages) }
                .distinctUntilChanged()
            combine(settings.unifyTitles.distinctUntilChanged(), prefs, rescanState.lastRescanTime) { enabled, p, _ -> enabled to p }
                .collectLatest { (enabled, p) ->
                    while (true) {
                        rescanState.isRescanning.first { !it }
                        val missing = try {
                            refresh(enabled, p)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Regrouping titles failed", e)
                            1
                        }
                        // Lists that could not be had (offline): another go later.
                        if (missing == 0) break
                        delay(RETRY_AFTER_MS)
                    }
                }
        }
    }

    /** Regroups every console; returns how many lists could not be had. */
    private suspend fun refresh(enabled: Boolean, prefs: VersionPrefs): Int = mutex.withLock {
        TitleClones.enabled = enabled
        var changed = 0
        var missing = 0
        for (consoleId in dao.indexedConsoles()) {
            val map = if (enabled) listFor(consoleId) else emptyMap()
            if (map == null) { missing++; continue }
            val rows = dao.titleRows(consoleId)
            if (enabled) TitleClones.install(consoleId, map, gameKeys(consoleId, map, rows, prefs))
            val plan = TitleClones.plan(rows) { TitleClones.canonical(consoleId, it) }
            if (plan.isEmpty) continue
            dao.regroup(consoleId, plan)
            changed += plan.updates.values.sumOf { it.size }
            Log.i(TAG, "$consoleId: ${plan.updates.values.sumOf { it.size }} rows regrouped into ${plan.updates.size} games")
        }
        if (changed > 0) _version.update { it + 1 }
        missing
    }

    /** Canonical key → the key of each game [map] joins in the console: the title it shows ([TitleClones.gameKeys]). */
    private suspend fun gameKeys(consoleId: String, map: Map<String, String>, rows: List<TitleClones.Row>, prefs: VersionPrefs): Map<String, String> {
        val titles = TitleClones.titlesByGame(map, rows.mapTo(HashSet()) { it.titleKey })
        // Only games with several titles need their rows (and tags) read.
        val several = titles.values.filter { it.size > 1 }.flatten()
        val ranked = several.chunked(MAX_KEYS_PER_QUERY).flatMap { chunk ->
            dao.titledRows(consoleId, chunk).map { r ->
                TitleClones.TitledRow(r.titleKey, r.name, r.fileName, r.tags?.split('|')?.filter { it.isNotBlank() }.orEmpty())
            }
        }
        return TitleClones.gameKeys(titles, ranked, prefs)
    }

    /** The console's clone list ([TitleClones.parse]); empty when it has none, null when it cannot be had yet. */
    private fun listFor(consoleId: String): Map<String, String>? {
        val listName = TitleClones.listNameFor(consoleId) ?: return emptyMap()
        val file = File(File(context.noBackupFilesDir, "clonelists"), "$listName.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
        val failed = failedAt[listName]?.let { System.currentTimeMillis() - it < RETRY_AFTER_MS } == true
        if (!fresh && !failed) {
            try {
                download(TitleClones.url(listName), file)
                failedAt.remove(listName)
            } catch (e: IOException) {
                failedAt[listName] = System.currentTimeMillis()
                Log.w(TAG, "Clone list $listName: ${e.message}")
            }
        }
        if (!file.exists()) return null
        val modified = file.lastModified()
        parsed[file.name]?.takeIf { it.first == modified }?.let { return it.second }
        val map = if (file.length() == 0L) emptyMap() else TitleClones.parse(file.readText())
        parsed[file.name] = modified to map
        return map
    }

    /** Saves [url] to [target]; a 404 leaves an empty file (no list for that system). */
    private fun download(url: String, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", userAgent)
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
                HttpURLConnection.HTTP_NOT_FOUND -> temp.writeBytes(ByteArray(0))
                else -> throw IOException("HTTP $code for $url")
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("Could not save $target")
            }
        } finally {
            connection.disconnect()
            temp.delete()
        }
    }

    private companion object {
        const val TAG = "TitleClones"
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val RETRY_AFTER_MS = 10L * 60 * 1000
        /** SQLite before 3.32 takes 999 variables. */
        const val MAX_KEYS_PER_QUERY = 500
    }
}
