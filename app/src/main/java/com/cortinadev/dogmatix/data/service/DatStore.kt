package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.DatHeader
import com.cortinadev.dogmatix.util.DatMatcher
import com.cortinadev.dogmatix.util.DatParser
import com.cortinadev.dogmatix.util.DatRom
import com.cortinadev.dogmatix.util.DownloadCheck
import com.cortinadev.dogmatix.util.FileVerdict
import com.cortinadev.dogmatix.util.HashedFile
import com.cortinadev.dogmatix.util.LibretroDats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.Writer
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DatStore"
/** An automatic DAT older than this is fetched again (No-Intro and Redump publish often). */
private const val AUTO_REFRESH_MS = 30L * 24 * 60 * 60 * 1000
private const val HEADER_MARK = "#DAT"
/** After a failed fetch (no network, a server error) the DAT is not asked for again for this long. */
private const val RETRY_AFTER_MS = 10L * 60 * 1000

/** One stored DAT: [origin] is [ORIGIN_IMPORTED] or a [LibretroDats.Collection] id. */
data class DatInfo(
    val consoleId: String,
    val origin: String,
    val name: String,
    val version: String,
    val entries: Int,
    val savedAt: Long
) {
    /** What a check against it is reported as (see [DownloadCheck]). */
    val against: String get() = if (origin == DatStore.ORIGIN_IMPORTED) DownloadCheck.IMPORTED_PREFIX + name else origin
}

/** An imported DAT read and waiting for its console. */
data class StagedDat(val header: DatHeader, val entries: Int, val guessedConsoleId: String?, internal val body: File)

/**
 * The DATs used to check downloads, per console: imported by the user (Settings → DAT files) or
 * fetched from libretro-database. Each is kept as a small text file in `files/dats` with only
 * what matching needs (name, size, CRC, SHA-1) and is streamed through a [DatMatcher] when a
 * download of its console is checked, so nothing large stays in memory.
 */
@Singleton
class DatStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val dir = File(context.filesDir, "dats")
    private val lock = Mutex()
    /** Addresses that answered 404 in this run: not asked again. */
    private val unavailable = ConcurrentHashMap.newKeySet<String>()
    /** When each address that failed may be tried again. */
    private val retryAt = ConcurrentHashMap<String, Long>()

    /** The file behind each stored DAT, by console and origin (replaced whole by [refresh]). */
    @Volatile private var stored: Map<Pair<String, String>, File> = emptyMap()

    private val _dats = MutableStateFlow<List<DatInfo>>(emptyList())
    /** Every stored DAT; refreshed by [refresh] and after each change. */
    val dats: StateFlow<List<DatInfo>> = _dats.asStateFlow()

    suspend fun refresh(): List<DatInfo> = withContext(Dispatchers.IO) {
        val found = (dir.listFiles { f -> f.isFile && f.name.endsWith(".tsv") } ?: emptyArray())
            .mapNotNull { file -> readInfo(file)?.let { it to file } }
        stored = found.associate { (info, file) -> (info.consoleId to info.origin) to file }
        found.map { it.first }.sortedWith(compareBy({ it.consoleId }, { it.origin })).also { list -> _dats.update { list } }
    }

    private fun readInfo(file: File): DatInfo? = runCatching {
        val parts = file.bufferedReader().use { it.readLine() }?.split('\t') ?: return null
        if (parts.size < 6 || parts[0] != HEADER_MARK) return null
        DatInfo(parts[1], parts[2], parts[3], parts[4], parts[5].toIntOrNull() ?: 0, file.lastModified())
    }.getOrNull()

    private fun fileFor(consoleId: String, origin: String): File =
        File(dir, consoleId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + "-" + origin + ".tsv")

    /**
     * Matches [files] against every DAT of [consoleId], one streamed pass each; returns what each
     * DAT says, by [DatInfo.against]. Empty when the console has no DAT.
     */
    suspend fun check(consoleId: String, files: List<HashedFile>): List<Pair<String, List<FileVerdict>>> = withContext(Dispatchers.IO) {
        if (_dats.value.isEmpty()) refresh()
        // The user's own DAT first; the automatic ones after it.
        _dats.value.filter { it.consoleId == consoleId }
            .sortedBy { if (it.origin == ORIGIN_IMPORTED) 0 else 1 }
            .mapNotNull { info ->
                val file = stored[info.consoleId to info.origin] ?: return@mapNotNull null
                runCatching {
                    val matcher = DatMatcher(files)
                    file.bufferedReader().useLines { lines -> lines.drop(1).forEach { line -> romOf(line)?.let(matcher::offer) } }
                    info.against to matcher.verdicts()
                }.onFailure { Log.w(TAG, "Could not read ${file.name}: ${it.message}") }.getOrNull()
            }
    }

    /**
     * Fetches the libretro-database DATs of [consoleId] that are missing or old, when "Download
     * DATs" is on. Failures leave whatever was there; a 404 is not asked again in this run.
     */
    suspend fun ensureAuto(consoleId: String) = withContext(Dispatchers.IO) {
        if (!settingsRepository.datAutoDownload.first()) return@withContext
        lock.withLock {
            if (_dats.value.isEmpty()) refresh()
            var changed = false
            LibretroDats.sourcesFor(consoleId).forEach { (collection, url) ->
                val target = fileFor(consoleId, collection.id)
                val fresh = target.exists() && System.currentTimeMillis() - target.lastModified() < AUTO_REFRESH_MS
                if (fresh || url in unavailable || System.currentTimeMillis() < (retryAt[url] ?: 0L)) return@forEach
                runCatching { download(url, consoleId, collection.id, target) }
                    .onSuccess { changed = changed || it }
                    .onFailure {
                        Log.w(TAG, "Could not fetch $url: ${it.message}")
                        retryAt[url] = System.currentTimeMillis() + RETRY_AFTER_MS
                    }
            }
            if (changed) refresh()
        }
    }

    private fun download(url: String, consoleId: String, origin: String, target: File): Boolean {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", JsonHttp.userAgent)
            val code = connection.responseCode
            if (code == 404) { unavailable += url; return false }
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            val (header, count, body) = connection.inputStream.use { parseToBody(it) }
            if (count == 0) { body.delete(); return false }
            finish(target, consoleId, origin, header, count, body)
            Log.i(TAG, "Saved $origin DAT '${header.name}' for $consoleId ($count entries)")
            return true
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Reads a DAT the user picked: a `.dat` / `.xml` (Logiqx or ClrMamePro), or a ZIP holding one.
     * Null when it holds no game entry.
     */
    suspend fun stage(uri: Uri, consoleIds: List<String>): StagedDat? = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
        val (header, count, body) = BufferedInputStream(input).use { buffered ->
            buffered.mark(4)
            val magic = ByteArray(4).also { buffered.read(it) }
            buffered.reset()
            if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() && magic[2] == 3.toByte() && magic[3] == 4.toByte()) {
                ZipInputStream(buffered).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null && (entry.isDirectory || !entry.name.lowercase().let { it.endsWith(".dat") || it.endsWith(".xml") })) {
                        entry = zip.nextEntry
                    }
                    if (entry == null) return@withContext null
                    parseToBody(zip)
                }
            } else parseToBody(buffered)
        }
        if (count == 0) { body.delete(); return@withContext null }
        StagedDat(header, count, LibretroDats.guessConsole(header.name, consoleIds), body)
    }

    /** Stores a staged DAT for [consoleId], replacing the one imported before. */
    suspend fun commit(staged: StagedDat, consoleId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            finish(fileFor(consoleId, ORIGIN_IMPORTED), consoleId, ORIGIN_IMPORTED, staged.header, staged.entries, staged.body)
            refresh()
        }
    }

    fun discard(staged: StagedDat) {
        staged.body.delete()
    }

    suspend fun remove(info: DatInfo) = withContext(Dispatchers.IO) {
        lock.withLock {
            fileFor(info.consoleId, info.origin).delete()
            refresh()
        }
    }

    /** Parses [input] into a temporary file of `name \t size \t crc \t sha1` lines. */
    private fun parseToBody(input: InputStream): Triple<DatHeader, Int, File> {
        val body = File.createTempFile("dat-", ".tsv", context.cacheDir)
        var count = 0
        val header = body.bufferedWriter().use { out ->
            DatParser.parse(input.reader(Charsets.UTF_8).buffered()) { rom ->
                writeRom(out, rom)
                count++
            }
        }
        return Triple(header, count, body)
    }

    private fun finish(target: File, consoleId: String, origin: String, header: DatHeader, count: Int, body: File) {
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        tmp.bufferedWriter().use { out ->
            out.write(listOf(HEADER_MARK, consoleId, origin, clean(header.name), clean(header.version), count.toString()).joinToString("\t"))
            out.write("\n")
            body.bufferedReader().use { it.copyTo(out) }
        }
        body.delete()
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("Could not save ${target.name}")
        }
    }

    private fun writeRom(out: Writer, rom: DatRom) {
        out.write(clean(rom.name)); out.write("\t")
        out.write(rom.size.toString()); out.write("\t")
        out.write(rom.crc.orEmpty()); out.write("\t")
        out.write(rom.sha1.orEmpty()); out.write("\n")
    }

    private fun romOf(line: String): DatRom? {
        val parts = line.split('\t')
        if (parts.size < 4 || parts[0].isEmpty()) return null
        return DatRom(parts[0], parts[1].toLongOrNull() ?: -1L, parts[2].ifEmpty { null }, parts[3].ifEmpty { null })
    }

    private fun clean(text: String): String = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    companion object {
        const val ORIGIN_IMPORTED = "imported"
    }
}
