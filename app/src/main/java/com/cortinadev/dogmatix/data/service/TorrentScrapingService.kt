package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.Console
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.ScanProgressMath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Torrent equivalent of [DatabaseScrapingService].
 *
 * 1. Calls [TorrentMetadataFetcher] to get (or reuse) the TorrentInfo.
 * 2. Calls [TorrentFileIndexer] to list all files, filtered by [UrlEntry.folders] if specified.
 * 3. Maps each file to a [DownloadableFileEntity] with torrentFileIndex +
 *    torrentMagnet filled in, then batch-inserts — same pattern as HTTP scraper.
 * 4. Runs [FileParsingUtils.extractNameAndTags] on each file name so region /
 *    language / version tags work the same as for HTTP sources.
 */
@Singleton
class TorrentScrapingService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val metadataFetcher: TorrentMetadataFetcher,
    private val fileIndexer: TorrentFileIndexer,
    private val rescanStateHolder: RescanStateHolder,
    private val registry: TorrentHandleRegistry
) {

    /**
     * Indexes the torrent's files and hands them to [sink], which writes them only if they
     * changed. The file list comes from a session handle, the metadata cache on disk or the
     * swarm (see [TorrentMetadataFetcher.withInfo]).
     */
    suspend fun scrapeAndInsert(urlEntry: UrlEntry, console: Console, sink: SourceRowsSink): SourceWrite =
        withContext(Dispatchers.IO) {
            val magnet = urlEntry.url
            val key = RescanStateHolder.sourceKey(console.id, magnet)

            rescanStateHolder.setTorrentFetchProgress(context.getString(R.string.scrape_fetching_torrent, console.name))

            val entries = try {
                metadataFetcher.withInfo(magnet) { info ->
                    rescanStateHolder.sourceProgress(key, ScanProgressMath.PHASE_TORRENT_METADATA)
                    rescanStateHolder.setTorrentFetchProgress(context.getString(R.string.scrape_indexing_torrent, console.name))
                    fileIndexer.index(info, magnet, urlEntry.folders)
                }
            } catch (e: Exception) {
                rescanStateHolder.setTorrentFetchProgress("")
                throw e
            }
            if (entries.isEmpty()) Log.w(TAG, "No files found in torrent")

            val allFiles = ArrayList<DownloadableFileEntity>(entries.size)
            val allTags = ArrayList<List<String>>(entries.size)
            val contentTypeTag = FileParsingUtils.normalizeTag(urlEntry.contentType.name)

            entries.forEach { entry ->
                val (cleanName, tagStrings) = FileParsingUtils.extractNameAndTags(entry.fileName)
                val entity = DownloadableFileEntity(
                    id = 0,
                    name = cleanName,
                    fileName = entry.fileName,
                    consoleId = console.id,
                    downloadUrl = magnet,
                    fileSize = entry.fileSize,
                    fileExtension = entry.fileName.substringAfterLast('.', ""),
                    torrentFileIndex = entry.fileIndex,
                    torrentMagnet = magnet
                )
                allFiles.add(entity)
                allTags.add(tagStrings + contentTypeTag)
            }
            rescanStateHolder.sourceProgress(key, ScanProgressMath.PHASE_PARSED)

            // By position, not by file name: two files with the same name in different folders
            // of the torrent used to share one id, so one got both sets of tags and the other none.
            val write = sink(allFiles, allTags)
            rescanStateHolder.setTorrentFetchProgress("")

            Log.i(TAG, "${allFiles.size} files, ${write.tags} tags for ${console.name}" + if (write.unchanged) " (unchanged)" else "")
            
            // NOTE: We no longer release the handle here.
            // Keeping it in the session allows immediate starting of downloads.

            write
        }

    companion object { private const val TAG = "TorrentScrapingService" }
}
