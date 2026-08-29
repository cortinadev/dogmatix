package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.FileTagEntity
import com.cortinadev.dogmatix.data.model.Console
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.RommSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RommScrapingService"

/**
 * Indexes a `romm://<slug>` source: every ROM RomM lists for that platform becomes a library
 * row whose download URL is the server's `/api/roms/{id}/content/…` endpoint.
 */
@Singleton
class RommScrapingService @Inject constructor(
    private val rommClient: RommClient,
    private val downloadableFileDao: DownloadableFileDao,
    private val rescanStateHolder: RescanStateHolder
) {
    suspend fun scrapeAndInsert(urlEntry: UrlEntry, console: Console): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val slug = RommSource.slugOf(urlEntry.url) ?: throw Exception("Invalid RomM source '${urlEntry.url}' (expected romm://<platform>)")
        val base = rommClient.configuredBaseUrl().ifEmpty { throw Exception("RomM server not configured (Settings → RomM)") }
        rescanStateHolder.setTorrentFetchProgress("Listing $slug on RomM…")
        try {
            val platform = rommClient.platforms().firstOrNull { it.slug.equals(slug, true) || it.fsSlug.equals(slug, true) || it.id.toString() == slug }
                ?: throw Exception("RomM has no platform '$slug'")
            val roms = rommClient.roms(platform.id)
            if (roms.isEmpty()) {
                Log.i(TAG, "No ROMs on RomM for $slug")
                return@withContext Pair(0, 0)
            }
            val files = ArrayList<DownloadableFileEntity>(roms.size)
            val tags = ArrayList<List<FileTagEntity>>(roms.size)
            val contentTypeTag = FileParsingUtils.normalizeTag(urlEntry.contentType.name)
            roms.forEach { rom ->
                val (cleanName, tagStrings) = FileParsingUtils.extractNameAndTags(rom.fsName.substringBeforeLast('.', rom.fsName))
                files += DownloadableFileEntity(
                    name = cleanName.ifBlank { rom.name.ifBlank { rom.fsName } },
                    fileName = rom.fsName,
                    consoleId = console.id,
                    downloadUrl = RommSource.downloadUrl(base, rom.id, rom.fsName),
                    fileSize = rom.fsSizeBytes,
                    fileExtension = rom.fsName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
                )
                tags += (tagStrings + contentTypeTag).distinct().map { FileTagEntity(fileId = 0L, tag = it) }
            }
            val ids = downloadableFileDao.insertAll(files)
            val tagRows = ids.zip(tags).flatMap { (id, list) -> list.map { it.copy(fileId = id) } }
            downloadableFileDao.insertTags(tagRows)
            Log.i(TAG, "Indexed ${files.size} ROM(s) from RomM platform $slug")
            Pair(files.size, tagRows.size)
        } finally {
            rescanStateHolder.setTorrentFetchProgress("")
        }
    }
}
