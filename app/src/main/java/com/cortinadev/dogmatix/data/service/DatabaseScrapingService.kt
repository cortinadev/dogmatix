package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.Console
import com.cortinadev.dogmatix.data.model.Manufacturer
import com.cortinadev.dogmatix.data.model.ScrapeRecipe
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.util.AuthRedirects
import com.cortinadev.dogmatix.util.EmptyListingException
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.HttpHeadersUtils
import com.cortinadev.dogmatix.util.IaFiles
import com.cortinadev.dogmatix.util.IaSource
import com.cortinadev.dogmatix.util.ListingHttpException
import com.cortinadev.dogmatix.util.ListingParser
import com.cortinadev.dogmatix.util.MirrorUrls
import com.cortinadev.dogmatix.util.NoListingException
import com.cortinadev.dogmatix.util.ScanFailure
import com.cortinadev.dogmatix.util.ScanHostGate
import com.cortinadev.dogmatix.util.ScanProgressMath
import com.cortinadev.dogmatix.util.ScanRetry
import com.cortinadev.dogmatix.util.ScrapeRecipes
import com.cortinadev.dogmatix.util.ScrapedItem
import com.cortinadev.dogmatix.util.SourceKind
import com.cortinadev.dogmatix.util.SourceScanCheck
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.Charset
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

/** One file of a page recipe's test: the name as the page shows it. */
data class ScrapePreviewRow(val name: String, val fileSize: Long, val downloadUrl: String)

/** "Test" in the source dialog: the first page of a recipe, [matched] elements, [total] with a file link. */
data class ScrapePreview(val pageUrl: String, val matched: Int, val total: Int, val rows: List<ScrapePreviewRow>)

/** Signature of the row writer the scrapers hand their files to (see [SourceRowWriter]). */
typealias SourceRowsSink = suspend (files: List<DownloadableFileEntity>, tags: List<List<String>>) -> SourceWrite

@Singleton
class DatabaseScrapingService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadableFileDao: DownloadableFileDao,
    private val torrentScrapingService: TorrentScrapingService,
    private val rommScrapingService: RommScrapingService,
    private val rowWriter: SourceRowWriter,
    private val rescanStateHolder: RescanStateHolder,
    private val iaAuth: IaAuth
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
     *   HTTP with a page recipe → [scanScrapeSource] (CSS selectors, one page per `{page}` value)
     *   ia://   → [scanIaSource] (an Internet Archive item, from its metadata API)
     *   HTTP    → [scanWebSource] (with its mirrors and conditional requests; an archive.org ZIP
     *             viewer is one of these)
     * [previous] is what the last scan of the source left (null = read it in full). Throws when
     * the source could not be read; its rows are then left as they were. Without [swarm], a
     * torrent is only read when its file list is at hand (see [SourceScanCheck.torrentReread]).
     */
    suspend fun scanSource(
        console: Console, entry: UrlEntry, previous: SourceScanCheck.Previous?, force: Boolean, swarm: Boolean = true
    ): SourceOutcome =
        withContext(Dispatchers.IO) {
            val sink: SourceRowsSink = { files, tags -> rowWriter.write(console.id, entry, previous, force, files, tags) }
            when (SourceKind.of(entry)) {
                SourceKind.TORRENT -> SourceOutcome(scrapeTorrent(entry, console, previous, swarm, sink))
                // Every romm:// source is the one RomM server of Settings: they share its slots.
                SourceKind.ROMM -> SourceOutcome(hostGate.withSlot(ROMM_HOST) { rommScrapingService.scrapeAndInsert(entry, console, sink) })
                SourceKind.WEB -> scanWebSource(entry, console, previous, force, sink)
                SourceKind.SCRAPE -> scanScrapeSource(entry, console, previous, sink)
                SourceKind.IA -> scanIaSource(entry, console, previous, force, sink)
            }
        }

    /**
     * A torrent whose file list did not arrive in time gets one more go: the swarm is often found
     * by then. Without [swarm], a file list that is not at hand (any more) leaves the rows of
     * [previous] as they are.
     */
    private suspend fun scrapeTorrent(
        entry: UrlEntry, console: Console, previous: SourceScanCheck.Previous?, swarm: Boolean, sink: SourceRowsSink
    ): SourceWrite {
        if (!swarm) {
            return torrentScrapingService.scrapeAndInsert(entry, console, sink, swarm = false)
                ?: checkNotNull(previous) { "No previous scan to keep for ${entry.url}" }
                    .let { SourceWrite(it.fileCount.coerceAtLeast(0), 0, unchanged = true, contentHash = it.contentHash) }
        }
        return try {
            checkNotNull(torrentScrapingService.scrapeAndInsert(entry, console, sink))
        } catch (e: TorrentMetadataTimeoutException) {
            Log.w(TAG, "Metadata timed out for ${console.id}; one more try")
            checkNotNull(torrentScrapingService.scrapeAndInsert(entry, console, sink))
        }
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
            // Without them [request] already refuses a 304; this only guards against that changing.
            val kept = previous ?: throw ListingHttpException(304, null, target)
            Log.i(TAG, "$target unchanged (304)")
            return SourceOutcome(
                SourceWrite(kept.fileCount, 0, unchanged = true, contentHash = kept.contentHash),
                etag = listing.etag ?: kept.etag, lastModified = listing.lastModified ?: kept.lastModified, servedBy = servedBy
            )
        }

        val doc = Jsoup.parse(ByteArrayInputStream(listing.body), listing.charset, target)
        val contentTypeTag = FileParsingUtils.normalizeTag(entry.contentType.name)
        // Relative links resolve against the main address; links into a mirror are moved back to it.
        val parsed = ListingParser.parse(doc, baseUrl = entry.url, pageUrl = target, consoleId = console.id, contentTypeTag = contentTypeTag)
        val files = if (servedBy == null) parsed.files
                    else parsed.files.map { it.copy(downloadUrl = SourceScanCheck.primaryUrl(it.downloadUrl, servedBy, entry.url)) }
        // A listing without files is far more likely an error page than an emptied folder: only a
        // source whose last good scan was empty too may come back empty (its rows stay otherwise).
        if (files.isEmpty() && previous?.fileCount != 0) throw EmptyListingException(target)
        rescanStateHolder.sourceProgress(key, ScanProgressMath.PHASE_PARSED)

        val write = sink(files, parsed.tags)
        Log.i(TAG, "${files.size} files from $target (${if (write.unchanged) "unchanged" else "${write.newFiles} new"})")
        return SourceOutcome(write, listing.etag, listing.lastModified, servedBy)
    }

    /**
     * A web page read with the selectors of its recipe, page after page: a numbered page that
     * brings nothing new (or a 404 past the first) ends the run. No validators are kept (a source
     * of several pages has no ETag that describes it); the content hash still saves the rewrite.
     * Mirrors do not apply.
     */
    private suspend fun scanScrapeSource(
        entry: UrlEntry, console: Console, previous: SourceScanCheck.Previous?, sink: SourceRowsSink
    ): SourceOutcome {
        val recipe = checkNotNull(entry.scrape)
        ScrapeRecipes.validate(recipe, entry.url)?.let { throw IllegalArgumentException("Recipe: ${ScrapeRecipes.describe(it)}") }
        val key = RescanStateHolder.sourceKey(console.id, entry.url)
        val pages = ScrapeRecipes.plan(entry.url, recipe.pages)
        val contentTypeTag = FileParsingUtils.normalizeTag(entry.contentType.name)
        val seen = HashSet<String>()
        val items = ArrayList<ScrapedItem>()
        var matched = 0
        for ((i, page) in pages.withIndex()) {
            val doc = try {
                fetchPage(page.url)
            } catch (e: ListingHttpException) {
                if (i > 0 && page.stopsWhenEmpty && (e.code == 404 || e.code == 410)) break
                throw e
            }
            val parsed = ScrapeRecipes.parsePage(doc, recipe, console.id, listOf(contentTypeTag))
            matched += parsed.matched
            items += parsed.items
            rescanStateHolder.sourceProgress(key, ScanProgressMath.PHASE_PARSED * (i + 1) / pages.size)
            // Past the last page: nothing, or (sites that clamp the number) the same files again.
            if (page.stopsWhenEmpty && parsed.items.count { seen.add(it.file.downloadUrl) } == 0) break
        }
        // Nothing matched at all: a wrong selector, or a challenge / error page instead of the list.
        if (matched == 0) throw NoListingException(entry.url)
        val found = ScrapeRecipes.dedupe(items)
        if (found.isEmpty() && previous?.fileCount != 0) throw EmptyListingException(entry.url)
        rescanStateHolder.sourceProgress(key, ScanProgressMath.PHASE_PARSED)

        val write = sink(found.map { it.file }, found.map { it.tags })
        Log.i(TAG, "${found.size} files from ${entry.url} (${if (write.unchanged) "unchanged" else "${write.newFiles} new"})")
        return SourceOutcome(write)
    }

    /**
     * An Internet Archive item, from the metadata API (`/metadata/<id>`): one request with every
     * file and its hashes. Its `item_last_updated` is kept where a listing keeps its ETag, so a
     * rescan first asks for that field alone and, when it did not move, leaves the rows as they are.
     * `{}` (no such item) reads as not found; the folders of the entry narrow it like a torrent's.
     */
    private suspend fun scanIaSource(
        entry: UrlEntry, console: Console, previous: SourceScanCheck.Previous?, force: Boolean, sink: SourceRowsSink
    ): SourceOutcome {
        val id = IaSource.itemOf(entry.url)
            ?: throw IllegalArgumentException(context.getString(R.string.scrape_ia_invalid_source, entry.url))
        val key = RescanStateHolder.sourceKey(console.id, entry.url)
        rescanStateHolder.setTorrentFetchProgress(context.getString(R.string.scrape_listing_ia, id))
        try {
            val kept = previous?.takeIf { SourceScanCheck.canReuse(entry, it, force) && it.etag != null }
            if (kept != null) {
                val stamp = IaFiles.lastUpdated(text(fetchListing(IaSource.lastUpdatedUrl(id), emptyMap(), anyContentType = true)))
                if (stamp != null && stamp.toString() == kept.etag) {
                    Log.i(TAG, "${entry.url} unchanged (item_last_updated)")
                    return SourceOutcome(SourceWrite(kept.fileCount, 0, unchanged = true, contentHash = kept.contentHash), etag = kept.etag)
                }
            }
            val metadataUrl = IaSource.metadataUrl(id)
            val json = text(fetchListing(metadataUrl, emptyMap(), anyContentType = true))
            val all = IaFiles.parse(json)
            if (all.isEmpty()) throw ListingHttpException(404, null, metadataUrl)
            rescanStateHolder.sourceProgress(key, ScanProgressMath.PHASE_FETCHED)

            val contentTypeTag = FileParsingUtils.normalizeTag(entry.contentType.name)
            val rows = IaFiles.rows(IaFiles.select(all, id, entry.folders), id, console.id, contentTypeTag)
            if (rows.isEmpty() && previous?.fileCount != 0) throw EmptyListingException(entry.url)
            rescanStateHolder.sourceProgress(key, ScanProgressMath.PHASE_PARSED)

            val write = sink(rows.map { it.first }, rows.map { it.second })
            Log.i(TAG, "${rows.size} files from ${entry.url} (${if (write.unchanged) "unchanged" else "${write.newFiles} new"})")
            return SourceOutcome(write, etag = IaFiles.lastUpdated(json)?.toString())
        } finally {
            rescanStateHolder.setTorrentFetchProgress("")
        }
    }

    private fun text(listing: Listing): String =
        String(checkNotNull(listing.body), runCatching { Charset.forName(listing.charset ?: "UTF-8") }.getOrDefault(Charsets.UTF_8))

    /**
     * "Test" in the source dialog: the recipe on the first page only, with a single request (a
     * button cannot wait for the scan's retries) that still takes its turn at the server.
     */
    suspend fun preview(url: String, recipe: ScrapeRecipe): ScrapePreview = withContext(Dispatchers.IO) {
        ScrapeRecipes.validate(recipe, url)?.let { throw IllegalArgumentException("Recipe: ${ScrapeRecipes.describe(it)}") }
        val page = ScrapeRecipes.plan(url, recipe.pages).first()
        val headers = iaAuth.headers(page.url)
        val listing = hostGate.withSlot(MirrorUrls.hostOf(page.url).orEmpty()) { request(page.url, emptyMap(), headers = headers) }
        val parsed = ScrapeRecipes.parsePage(parse(listing, page.url), recipe, consoleId = "")
        ScrapePreview(
            page.url, parsed.matched, parsed.items.size,
            parsed.items.take(PREVIEW_ROWS).map { ScrapePreviewRow(it.shown, it.file.fileSize, it.file.downloadUrl) }
        )
    }

    /** A page for a recipe, with the scan's retries and server slots; links resolve against where it was served from. */
    private suspend fun fetchPage(url: String): Document = parse(fetchListing(url, emptyMap()), url)

    private fun parse(listing: Listing, url: String): Document =
        Jsoup.parse(ByteArrayInputStream(checkNotNull(listing.body)), listing.charset, listing.location ?: url)

    /** A downloaded listing; [body] null = 304 Not Modified; [location] = the address after redirects. */
    private class Listing(val body: ByteArray?, val charset: String?, val etag: String?, val lastModified: String?, val location: String? = null)

    /**
     * Downloads a listing (parsing happens after, outside the server's slot). 429, 5xx and
     * timeouts are tried again ([ScanRetry]); a server that asks to slow down makes every
     * request to it wait (see [ScanHostGate]). Requests to archive.org carry its headers
     * ([IaAuth]); [anyContentType] reads JSON as well as HTML.
     */
    private suspend fun fetchListing(url: String, conditional: Map<String, String>, anyContentType: Boolean = false): Listing {
        val host = MirrorUrls.hostOf(url).orEmpty()
        val gate = hostGate
        val headers = iaAuth.headers(url)
        return try {
            ScanRetry.retrying(
                random = { Random.nextDouble() },
                onRetry = { e, failure, wait, attemptsDone ->
                    Log.w(TAG, "$url: ${e.message} ($failure), try ${attemptsDone + 1} in ${wait / 1000} s")
                    if (failure == ScanFailure.RATE_LIMITED || failure == ScanFailure.SERVER) gate.pushBack(host, wait) else delay(wait)
                }
            ) { gate.withSlot(host) { request(url, conditional, anyContentType, headers) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw sessionRefused(e, headers)
        }
    }

    /**
     * Archive.org refused a request that carried the saved session: the report adds that the
     * session has probably expired (still a 401/403, see [ScanRetry.classify]).
     */
    private fun sessionRefused(e: Exception, headers: Map<String, String>): Exception {
        val code = (e as? ListingHttpException)?.code ?: return e
        return if ((code == 401 || code == 403) && AuthRedirects.carriesCredentials(headers)) IOException(IaSource.SESSION_EXPIRED_DETAIL, e) else e
    }

    /**
     * One request. [headers] go after the browser ones (archive.org's `User-Agent` replaces the
     * browser's); with credentials among them, redirects are followed by hand so the session only
     * goes on to archive.org hosts ([AuthRedirects]).
     */
    private fun request(
        url: String, conditional: Map<String, String>, anyContentType: Boolean = false, headers: Map<String, String> = emptyMap()
    ): Listing {
        var current = url
        var sent = headers
        var manual = AuthRedirects.carriesCredentials(headers)
        repeat(AuthRedirects.MAX_REDIRECTS + 1) {
            val connection = Jsoup.connect(current).ignoreHttpErrors(true)
            HttpHeadersUtils.configureBrowserHeaders(connection)
            if (anyContentType) connection.ignoreContentType(true)
            if (manual) connection.followRedirects(false)
            sent.forEach { (name, value) -> connection.header(name, value) }
            conditional.forEach { (name, value) -> connection.header(name, value) }
            val response = connection.execute()
            val code = response.statusCode()
            if (manual && code in AuthRedirects.REDIRECT_CODES) {
                val next = response.header("Location")?.let { AuthRedirects.resolve(current, it) }
                    ?: throw ListingHttpException(code, null, url)
                if (!AuthRedirects.keepAuth(current, next)) {
                    sent = AuthRedirects.withoutCredentials(sent)
                    manual = false
                }
                current = next
                return@repeat
            }
            val etag = response.header("ETag")
            val lastModified = response.header("Last-Modified")
            return when {
                code == 304 && conditional.isNotEmpty() -> Listing(null, null, etag, lastModified)
                code in 200..299 -> Listing(response.bodyAsBytes(), response.charset(), etag, lastModified, response.url().toString())
                else -> throw ListingHttpException(code, ScanRetry.parseRetryAfter(response.header("Retry-After"), System.currentTimeMillis()), url)
            }
        }
        throw IOException("Too many redirects for $url")
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
        private const val PREVIEW_ROWS = 5
    }
}
