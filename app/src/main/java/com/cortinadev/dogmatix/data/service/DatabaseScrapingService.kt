package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.Console
import com.cortinadev.dogmatix.data.model.Manufacturer
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.util.EmptyListingException
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.HttpHeadersUtils
import com.cortinadev.dogmatix.util.ListingHttpException
import com.cortinadev.dogmatix.util.MirrorUrls
import com.cortinadev.dogmatix.util.NoListingException
import com.cortinadev.dogmatix.util.ScanFailure
import com.cortinadev.dogmatix.util.ScanHostGate
import com.cortinadev.dogmatix.util.ScanProgressMath
import com.cortinadev.dogmatix.util.ScanRetry
import com.cortinadev.dogmatix.util.ScrapingConstants
import com.cortinadev.dogmatix.util.SourceKind
import com.cortinadev.dogmatix.util.SourceScanCheck
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** What scanning one source gave: its rows ([write]) and, for a web listing, what to ask with next time. */
data class SourceOutcome(
    val write: SourceWrite,
    val etag: String? = null,
    val lastModified: String? = null,
    /** The mirror that answered, when it was not the source's own address. */
    val servedBy: String? = null
)

/** Signature of the row writer the scrapers hand their files to (see [SourceRowWriter]). */
typealias SourceRowsSink = suspend (files: List<DownloadableFileEntity>, tags: List<List<String>>) -> SourceWrite

@Singleton
class DatabaseScrapingService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadableFileDao: DownloadableFileDao,
    private val torrentScrapingService: TorrentScrapingService,
    private val rommScrapingService: RommScrapingService,
    private val rowWriter: SourceRowWriter,
    private val rescanStateHolder: RescanStateHolder
) {

    /** Per server, for the scan run under way (see [beginRun]). */
    @Volatile private var hostGate = ScanHostGate(SCAN_PER_HOST)

    /** A new scan run: every server starts at full speed again. */
    fun beginRun() { hostGate = ScanHostGate(SCAN_PER_HOST) }

    /**
     * Scans one URL entry of [console] and writes its rows (only if they changed), routing it to
     * the right scraper:
     *   TORRENT → TorrentScrapingService
     *   romm:// → RommScrapingService (a platform of the RomM server set in Settings)
     *   HTTP    → [scanWebSource] (with its mirrors and conditional requests)
     * [previous] is what the last scan of the source left (null = read it in full). Throws when
     * the source could not be read; its rows are then left as they were.
     */
    suspend fun scanSource(console: Console, entry: UrlEntry, previous: SourceScanCheck.Previous?, force: Boolean): SourceOutcome =
        withContext(Dispatchers.IO) {
            val sink: SourceRowsSink = { files, tags -> rowWriter.write(console.id, entry, previous, force, files, tags) }
            when (SourceKind.of(entry.url)) {
                SourceKind.TORRENT -> SourceOutcome(scrapeTorrent(entry, console, sink))
                // Every romm:// source is the one RomM server of Settings: they share its slots.
                SourceKind.ROMM -> SourceOutcome(hostGate.withSlot(ROMM_HOST) { rommScrapingService.scrapeAndInsert(entry, console, sink) })
                SourceKind.WEB -> scanWebSource(entry, console, previous, force, sink)
            }
        }

    /** A torrent whose file list did not arrive in time gets one more go: the swarm is often found by then. */
    private suspend fun scrapeTorrent(entry: UrlEntry, console: Console, sink: SourceRowsSink): SourceWrite =
        try {
            torrentScrapingService.scrapeAndInsert(entry, console, sink)
        } catch (e: TorrentMetadataTimeoutException) {
            Log.w(TAG, "Metadata timed out for ${console.id}; one more try")
            torrentScrapingService.scrapeAndInsert(entry, console, sink)
        }

    /**
     * A web listing: the source's own address first, then its mirrors in order when it fails.
     * The rows always keep the main address, whichever one answered, so the download mirrors
     * ([SourceMirrors]) keep working and nothing changes its key.
     */
    private suspend fun scanWebSource(
        entry: UrlEntry, console: Console, previous: SourceScanCheck.Previous?, force: Boolean, sink: SourceRowsSink
    ): SourceOutcome {
        val targets = (listOf(entry.url) + entry.mirrors.filter { MirrorUrls.isWeb(it) }).distinct()
        var lastError: Exception? = null
        for (target in targets) {
            try {
                return scanListing(entry, console, target, previous, force, sink)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (target != targets.last()) Log.w(TAG, "$target failed (${e.message}); trying the next address")
            }
        }
        throw lastError ?: IllegalStateException("No address for ${entry.url}")
    }

    private suspend fun scanListing(
        entry: UrlEntry, console: Console, target: String, previous: SourceScanCheck.Previous?, force: Boolean, sink: SourceRowsSink
    ): SourceOutcome {
        val key = RescanStateHolder.sourceKey(console.id, entry.url)
        val servedBy = target.takeIf { it != entry.url }
        val listing = fetchListing(target, SourceScanCheck.conditionalHeaders(entry, previous, force, target))
        rescanStateHolder.sourceProgress(key, ScanProgressMath.PHASE_FETCHED)
        if (listing.body == null) {
            // 304: conditional headers only go out when the previous scan's rows can be reused.
            val kept = checkNotNull(previous)
            Log.i(TAG, "$target unchanged (304)")
            return SourceOutcome(
                SourceWrite(kept.fileCount, 0, unchanged = true, contentHash = kept.contentHash),
                etag = listing.etag ?: kept.etag, lastModified = listing.lastModified ?: kept.lastModified, servedBy = servedBy
            )
        }

        val doc = Jsoup.parse(ByteArrayInputStream(listing.body), listing.charset, target)
        val table = doc.select(ScrapingConstants.TABLE_SELECTOR).first() ?: throw NoListingException(target)
        val contentTypeTag = FileParsingUtils.normalizeTag(entry.contentType.name)
        val files = ArrayList<DownloadableFileEntity>()
        val tags = ArrayList<List<String>>()
        for (row in table.select("tr")) {
            val linkCell = row.select("td.link a").first() ?: row.select("a").first() ?: continue
            val href = linkCell.attr("href")
            val linkText = linkCell.text().trim()

            if (href == ScrapingConstants.PARENT_DIRECTORY ||
                href == ScrapingConstants.CURRENT_DIRECTORY ||
                linkText == "Parent directory/" ||
                linkText == "./" || linkText == "../") continue

            // Subfolders are not followed: a source is one listing.
            if (href.endsWith("/")) continue

            // Relative links resolve against the main address; absolute ones into a mirror are moved back to it.
            val (fileEntity, tagEntities) = FileParsingUtils.parseFileFromRow(row, entry.url, console.id, linkCell)
            if (fileEntity != null) {
                files += if (servedBy == null) fileEntity
                         else fileEntity.copy(downloadUrl = SourceScanCheck.primaryUrl(fileEntity.downloadUrl, servedBy, entry.url))
                tags += tagEntities.map { it.tag } + contentTypeTag
            }
        }
        // A table without files is far more likely an error page than an emptied folder: only a
        // source whose last good scan was empty too may come back empty (its rows stay otherwise).
        if (files.isEmpty() && previous?.fileCount != 0) throw EmptyListingException(target)
        rescanStateHolder.sourceProgress(key, ScanProgressMath.PHASE_PARSED)

        val write = sink(files, tags)
        Log.i(TAG, "${files.size} files from $target (${if (write.unchanged) "unchanged" else "${write.newFiles} new"})")
        return SourceOutcome(write, listing.etag, listing.lastModified, servedBy)
    }

    /** A downloaded listing; [body] null = 304 Not Modified. */
    private class Listing(val body: ByteArray?, val charset: String?, val etag: String?, val lastModified: String?)

    /**
     * Downloads a listing (parsing happens after, outside the server's slot). 429, 5xx and
     * timeouts are tried again ([ScanRetry]); a server that asks to slow down makes every
     * request to it wait (see [ScanHostGate]).
     */
    private suspend fun fetchListing(url: String, conditional: Map<String, String>): Listing {
        val host = MirrorUrls.hostOf(url).orEmpty()
        val gate = hostGate
        var attempts = 0
        while (true) {
            try {
                return gate.withSlot(host) { request(url, conditional) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempts++
                val failure = ScanRetry.classify(e)
                val wait = ScanRetry.nextDelayMs(attempts, failure, (e as? ListingHttpException)?.retryAfterMs, Random.nextDouble())
                    ?: throw e
                Log.w(TAG, "$url: ${e.message} ($failure), try ${attempts + 1} in ${wait / 1000} s")
                if (failure == ScanFailure.RATE_LIMITED || failure == ScanFailure.SERVER) gate.pushBack(host, wait) else delay(wait)
            }
        }
    }

    private fun request(url: String, conditional: Map<String, String>): Listing {
        val connection = Jsoup.connect(url).ignoreHttpErrors(true)
        HttpHeadersUtils.configureBrowserHeaders(connection)
        conditional.forEach { (name, value) -> connection.header(name, value) }
        val response = connection.execute()
        val code = response.statusCode()
        val etag = response.header("ETag")
        val lastModified = response.header("Last-Modified")
        return when {
            code == 304 && conditional.isNotEmpty() -> Listing(null, null, etag, lastModified)
            code in 200..299 -> Listing(response.bodyAsBytes(), response.charset(), etag, lastModified)
            else -> throw ListingHttpException(code, ScanRetry.parseRetryAfter(response.header("Retry-After"), System.currentTimeMillis()), url)
        }
    }

    /**
     * Reads every enabled source of [manufacturer] in full, one after the other, without the
     * incremental checks or the scan report (those live in [SourceScanService]).
     * [onScrapeError] is invoked on [Dispatchers.IO]; it must be thread-safe.
     */
    suspend fun scrapeManufacturer(manufacturer: Manufacturer, onScrapeError: (String) -> Unit = {}): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            var totalFiles = 0
            var totalTags = 0
            manufacturer.consoles.forEach { console ->
                console.urls.filter { it.enabled }.forEach { urlEntry ->
                    try {
                        val outcome = scanSource(console, urlEntry, previous = null, force = true)
                        totalFiles += outcome.write.files
                        totalTags += outcome.write.tags
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onScrapeError(context.getString(R.string.scrape_failed, console.name, e.message ?: ""))
                    }
                }
            }
            Pair(totalFiles, totalTags)
        }

    suspend fun clearAllData() = downloadableFileDao.clearAll()

    suspend fun clearConsoleData(consoleId: String) = downloadableFileDao.deleteFilesByConsoleId(consoleId)

    companion object {
        private const val TAG = "DatabaseScrapingService"
        /** Listings read from one server at the same time during a scan. */
        const val SCAN_PER_HOST = 2
        private const val ROMM_HOST = "romm://"
    }
}
