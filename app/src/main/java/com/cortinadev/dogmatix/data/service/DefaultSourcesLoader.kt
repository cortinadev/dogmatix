package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.data.local.dao.ConsoleDao
import com.cortinadev.dogmatix.data.local.dao.ManufacturerDao
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.local.entity.ManufacturerEntity
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.cortinadev.dogmatix.util.ConsoleAliasRegistry
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.SourceManufacturer
import com.cortinadev.dogmatix.util.SourcesJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DefaultSourcesLoader"
private const val ASSET = "consoles.json"
private const val FINGERPRINT_FILE = "default_sources.sha1"

/**
 * Seeds Room from `assets/consoles.json` and, on later app updates, merges whatever the bundled
 * file gained since the last sync (new manufacturers, consoles or URLs) without touching what the
 * user added, renamed or removed in between.
 */
@Singleton
class DefaultSourcesLoader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val manufacturerDao: ManufacturerDao,
    private val consoleDao: ConsoleDao
) {

    /** A console (already in Room) together with the URL entries the sync just added to it. */
    data class Added(val console: ConsoleEntity, val newUrls: List<UrlEntry>)

    /** First launch: wipes the sources tables and inserts the bundled document. */
    suspend fun loadDefaultSourcesToDatabase() = withContext(Dispatchers.IO) {
        try {
            consoleDao.clearAll()
            manufacturerDao.clearAll()
            val (manufacturers, consoles) = toEntities(readBundled())
            manufacturerDao.insertManufacturers(manufacturers)
            consoleDao.insertConsoles(consoles)
            rememberFingerprint()
            Log.d(TAG, "Loaded ${manufacturers.size} manufacturers and ${consoles.size} consoles")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading default sources: ${e.message}", e)
        }
    }

    /**
     * Adds anything present in the bundled document but missing from Room. Runs only when the
     * asset changed since the last sync (compared by content hash), so a user who deleted a
     * default console only sees it come back if a new app version ships a changed list.
     * Returns what was added so the caller can scrape just that.
     */
    suspend fun syncNewDefaults(): List<Added> = withContext(Dispatchers.IO) {
        val bundled = try { readBundled() } catch (e: Exception) {
            Log.e(TAG, "Cannot read bundled sources: ${e.message}", e); return@withContext emptyList()
        }
        val fingerprint = bundledFingerprint()
        if (fingerprint == storedFingerprint()) return@withContext emptyList()

        val added = mutableListOf<Added>()
        val (manufacturers, consoles) = toEntities(bundled)
        manufacturers.forEach { m ->
            if (manufacturerDao.getManufacturerById(m.id) == null) manufacturerDao.insertManufacturer(m)
        }
        consoles.forEach { bundledConsole ->
            val existing = consoleDao.getConsoleById(bundledConsole.id)
            val bundledUrls = SourcesJson.parseUrlEntries(bundledConsole.urls)
            if (existing == null) {
                consoleDao.insertConsole(bundledConsole)
                added += Added(bundledConsole, bundledUrls)
            } else {
                val current = SourcesJson.parseUrlEntries(existing.urls)
                val known = current.map { sourceKey(it.url) }.toSet()
                val missing = bundledUrls.filter { sourceKey(it.url) !in known }
                if (missing.isNotEmpty()) {
                    val updated = existing.copy(urls = SourcesJson.serializeUrlEntries(current + missing))
                    consoleDao.updateConsole(updated)
                    added += Added(updated, missing)
                }
            }
        }
        rememberFingerprint(fingerprint)
        Log.d(TAG, "Synced bundled sources: ${added.size} console(s) gained URLs")
        added
    }

    private fun readBundled(): List<SourceManufacturer> =
        SourcesJson.parseDocument(context.assets.open(ASSET).bufferedReader().use { it.readText() })

    private fun toEntities(doc: List<SourceManufacturer>): Pair<List<ManufacturerEntity>, List<ConsoleEntity>> {
        val manufacturers = doc.map { ManufacturerEntity(id = it.id, name = it.name) }
        val consoles = doc.flatMap { m ->
            m.consoles.map { c ->
                val urls = c.urls.map { u ->
                    if (u.url.startsWith("magnet:")) u.copy(url = FileParsingUtils.optimizeMagnetUri(u.url)) else u
                }
                ConsoleEntity(
                    id = c.id, name = c.name, manufacturerId = m.id, urls = SourcesJson.serializeUrlEntries(urls),
                    shortName = c.shortName.orEmpty(), folderAliases = ConsoleAliasRegistry.serializeAliases(c.folderAliases)
                )
            }
        }
        return manufacturers to consoles
    }

    /** Magnets are compared by info-hash so tracker-list changes do not count as a new source. */
    private fun sourceKey(url: String): String {
        if (!url.startsWith("magnet:")) return url.trim()
        val hash = Regex("xt=urn:btih:([A-Za-z0-9]+)").find(url)?.groupValues?.get(1)
        return hash?.lowercase() ?: url.trim()
    }

    private fun bundledFingerprint(): String {
        val bytes = context.assets.open(ASSET).use { it.readBytes() }
        return MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun fingerprintFile() = File(context.filesDir, FINGERPRINT_FILE)
    private fun storedFingerprint(): String? = fingerprintFile().takeIf { it.exists() }?.readText()?.trim()
    private fun rememberFingerprint(value: String = bundledFingerprint()) = fingerprintFile().writeText(value)
}
