package com.cortinadev.dogmatix.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A finished download's file as the check sees it: [path] relative to the console folder. */
data class HashedFile(val path: String, val hashes: FileHashes) {
    val leaf: String get() = DownloadCheck.leafOf(path)
}

/** What a reference (RomM, a DAT) lists for one file; null hashes and size ≤ 0 = not given. */
data class ExpectedFile(val name: String, val size: Long, val crc: String?, val sha1: String?, val md5: String? = null)

enum class HashKind { SIZE, CRC32, MD5, SHA1 }

/** How one file compares with a reference. */
sealed interface FileVerdict {
    data object Match : FileVerdict
    /** Listed under this name, with other content: [what] differs ([expected] / [actual] as shown). */
    data class Mismatch(val what: HashKind, val expected: String, val actual: String) : FileVerdict
    data object NotListed : FileVerdict
}

enum class CheckState { CHECKING, VERIFIED, MISMATCH, UNKNOWN }

/** Why a check found nothing to say. */
enum class UnknownReason {
    /** No RomM hashes and no DAT for the console. */
    NO_REFERENCE,
    /** The DAT (or RomM) does not list the file: another dump, a hack, a format it does not describe. */
    NOT_LISTED,
    /** Formats DATs do not describe (CHD, RVZ, 7z…) or only side files (`.cue`, `.m3u`). */
    UNSUPPORTED,
    /** The file was no longer where the download left it. */
    NOT_FOUND,
    /** It could not be read. */
    ERROR
}

data class MismatchDetail(val file: String, val what: HashKind, val expected: String, val actual: String)

/**
 * The verdict on one download. [against] names the reference: [DownloadCheck.ROMM], [DownloadCheck.IA],
 * [DownloadCheck.NO_INTRO], [DownloadCheck.REDUMP] or `dat:<name>` for an imported DAT.
 * [files] are the paths checked (kept while CHECKING so a restart can pick the check up again).
 */
data class CheckResult(
    val state: CheckState,
    val against: String? = null,
    val files: List<String> = emptyList(),
    val verified: Int = 0,
    val total: Int = 0,
    val mismatch: MismatchDetail? = null,
    val reason: UnknownReason? = null
)

/** Comparing downloaded files with RomM's hashes or a DAT, and the stored form of the result. Pure, for the tests. */
object DownloadCheck {

    const val ROMM = "romm"
    /** The hashes Internet Archive lists for the files of an item. */
    const val IA = "ia"
    const val NO_INTRO = "no-intro"
    const val REDUMP = "redump"
    const val IMPORTED_PREFIX = "dat:"

    /** Side files no DAT lists the same way (cue sheets are regenerated, playlists are ours). */
    private val sideExtensions = setOf(
        "cue", "gdi", "ccd", "m3u", "txt", "nfo", "pdf", "jpg", "jpeg", "png", "sfv", "md5", "sha1",
        "xml", "dat", "ini", "cfg", "url", "htm", "html", "log"
    )
    /** Compressed or converted images: their bytes are not the dump a DAT describes. */
    private val convertedExtensions = setOf("chd", "rvz", "wia", "gcz", "cso", "zso", "pbp", "7z", "rar", "nsz", "xcz", "wbfs")
    /** RomM hashes what is inside these, not the archive itself. */
    private val archiveExtensions = setOf("zip", "7z", "gz", "tar", "rar")

    fun leafOf(path: String): String = path.replace('\\', '/').substringAfterLast('/')

    private fun extensionOf(name: String): String = leafOf(name).substringAfterLast('.', "").lowercase()

    /** Whether a DAT can say anything about this file. */
    fun datDescribes(path: String): Boolean = extensionOf(path).let { it !in sideExtensions && it !in convertedExtensions }

    fun isArchive(path: String): Boolean = extensionOf(path) in archiveExtensions

    /** Same bytes: SHA-1 when the reference has it, else MD5, else CRC-32 with the size. */
    private fun sameContent(expected: ExpectedFile, actual: FileHashes): Boolean = when {
        expected.sha1 != null -> expected.sha1 == actual.sha1
        expected.md5 != null && actual.md5 != null -> expected.md5 == actual.md5
        expected.crc != null -> expected.crc == actual.crc32 && (expected.size <= 0 || expected.size == actual.size)
        else -> false
    }

    /** One file against the entry listed under its name; null when there is nothing to compare. */
    fun compare(actual: FileHashes, expected: ExpectedFile): FileVerdict? {
        if (expected.size > 0 && expected.size != actual.size) {
            return FileVerdict.Mismatch(HashKind.SIZE, expected.size.toString(), actual.size.toString())
        }
        expected.sha1?.let { return if (it == actual.sha1) FileVerdict.Match else FileVerdict.Mismatch(HashKind.SHA1, it, actual.sha1) }
        if (expected.md5 != null && actual.md5 != null) {
            return if (expected.md5 == actual.md5) FileVerdict.Match else FileVerdict.Mismatch(HashKind.MD5, expected.md5, actual.md5)
        }
        expected.crc?.let { return if (it == actual.crc32) FileVerdict.Match else FileVerdict.Mismatch(HashKind.CRC32, it, actual.crc32) }
        return null
    }

    /**
     * [file] against a short list (what RomM gives for one ROM): the same bytes under any name
     * match; otherwise the entry with its name decides; otherwise it is not listed.
     */
    fun check(file: HashedFile, expected: List<ExpectedFile>): FileVerdict {
        if (expected.any { sameContent(it, file.hashes) }) return FileVerdict.Match
        return expected.asSequence()
            .filter { leafOf(it.name).equals(file.leaf, ignoreCase = true) }
            .firstNotNullOfOrNull { compare(file.hashes, it) }
            ?: FileVerdict.NotListed
    }

    /**
     * The verdict of one reference over every file of a download, or null when it has nothing to
     * say (no file listed), so the next reference can be tried. A single mismatch makes the whole
     * download a mismatch; nothing is ever deleted for it.
     */
    fun combine(against: String, files: List<HashedFile>, verdicts: List<FileVerdict>): CheckResult? {
        val paths = files.map { it.path }
        val firstMismatch = files.indices.firstNotNullOfOrNull { i ->
            (verdicts[i] as? FileVerdict.Mismatch)?.let { MismatchDetail(files[i].path, it.what, it.expected, it.actual) }
        }
        val matches = verdicts.count { it == FileVerdict.Match }
        return when {
            firstMismatch != null -> CheckResult(CheckState.MISMATCH, against, paths, matches, files.size, mismatch = firstMismatch)
            matches > 0 -> CheckResult(CheckState.VERIFIED, against, paths, matches, files.size)
            else -> null
        }
    }

    /** Of several DATs' verdicts, a match anywhere beats a mismatch in another (an older DAT, a redump). */
    fun best(results: List<CheckResult?>): CheckResult? {
        val decided = results.filterNotNull()
        return decided.filter { it.state == CheckState.VERIFIED }.maxByOrNull { it.verified }
            ?: decided.firstOrNull { it.state == CheckState.MISMATCH }
    }

    // ---- RomM ------------------------------------------------------------------------------------

    private val rommRomId = Regex("""/api/roms/(\d+)/content/""")

    /** The RomM ROM id in a `/api/roms/{id}/content/…` download address. */
    fun rommRomIdOf(downloadUrl: String): Int? = rommRomId.find(downloadUrl)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * What `GET /api/roms/{id}` says about the ROM's files: the ROM itself when it is a single
     * plain file, each of `files[]` and each member of an archive (`archive_members`). Archives
     * are left out: RomM hashes their contents, so their hash never matches the archive's bytes.
     */
    fun rommExpectedFiles(json: String): List<ExpectedFile> {
        val rom = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<ExpectedFile>()
        fun add(name: String, size: Long, o: JsonObject) {
            if (name.isBlank() || isArchive(name)) return
            val entry = ExpectedFile(
                name = name,
                size = size,
                crc = Checksums.normalize(o.str("crc_hash"), 8),
                sha1 = Checksums.normalize(o.str("sha1_hash"), 40),
                md5 = Checksums.normalize(o.str("md5_hash"), 32)
            )
            if (entry.crc != null || entry.sha1 != null || entry.md5 != null) out += entry
        }
        if (rom.get("has_multiple_files")?.takeIf { it.isJsonPrimitive }?.asBoolean != true) {
            add(rom.str("fs_name").orEmpty(), rom.long("fs_size_bytes"), rom)
        }
        rom.array("files")?.forEach { element ->
            val f = element.asObjectOrNull() ?: return@forEach
            add(f.str("file_name") ?: f.str("filename").orEmpty(), f.long("file_size_bytes").takeIf { it > 0 } ?: f.long("size"), f)
            f.array("archive_members")?.forEach { member ->
                val m = member.asObjectOrNull() ?: return@forEach
                add(m.str("name").orEmpty(), m.long("size"), m)
            }
        }
        return out.distinct()
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.long(name: String): Long = get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() } ?: -1L
    private fun JsonObject.array(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    // ---- stored form -----------------------------------------------------------------------------

    fun encode(result: CheckResult): String = JsonObject().apply {
        addProperty("state", result.state.name)
        result.against?.let { addProperty("against", it) }
        if (result.files.isNotEmpty()) add("files", JsonArray().apply { result.files.forEach { add(it) } })
        addProperty("verified", result.verified)
        addProperty("total", result.total)
        result.reason?.let { addProperty("reason", it.name) }
        result.mismatch?.let { m ->
            add("mismatch", JsonObject().apply {
                addProperty("file", m.file)
                addProperty("what", m.what.name)
                addProperty("expected", m.expected)
                addProperty("actual", m.actual)
            })
        }
    }.toString()

    fun decode(text: String?): CheckResult? {
        val o = runCatching { JsonParser.parseString(text ?: return null).asJsonObject }.getOrNull() ?: return null
        val state = o.str("state")?.let { s -> CheckState.entries.firstOrNull { it.name == s } } ?: return null
        val mismatch = o.get("mismatch")?.asObjectOrNull()?.let { m ->
            val what = m.str("what")?.let { w -> HashKind.entries.firstOrNull { it.name == w } } ?: return@let null
            MismatchDetail(m.str("file").orEmpty(), what, m.str("expected").orEmpty(), m.str("actual").orEmpty())
        }
        return CheckResult(
            state = state,
            against = o.str("against"),
            files = o.array("files")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty(),
            verified = o.long("verified").coerceAtLeast(0).toInt(),
            total = o.long("total").coerceAtLeast(0).toInt(),
            mismatch = mismatch,
            reason = o.str("reason")?.let { r -> UnknownReason.entries.firstOrNull { it.name == r } }
        )
    }
}

/**
 * Matches a few downloaded files against a DAT read as a stream (see [DatParser]), so even a
 * Redump set of tens of thousands of entries is checked in one pass without loading it.
 */
class DatMatcher(private val files: List<HashedFile>) {
    private val matched = BooleanArray(files.size)
    private val mismatches = arrayOfNulls<FileVerdict.Mismatch>(files.size)
    private val bySha1 = files.indices.groupBy { files[it].hashes.sha1 }
    private val byCrc = files.indices.groupBy { files[it].hashes.crc32 }
    private val byName = files.indices.groupBy { files[it].leaf.lowercase() }

    /** Entries seen so far. */
    var entries = 0
        private set

    fun offer(rom: DatRom) {
        entries++
        if (rom.sha1 != null) bySha1[rom.sha1]?.forEach { matched[it] = true }
        else if (rom.crc != null) byCrc[rom.crc]?.forEach { if (rom.size <= 0 || rom.size == files[it].hashes.size) matched[it] = true }
        byName[DownloadCheck.leafOf(rom.name).lowercase()]?.forEach { i ->
            if (matched[i] || mismatches[i] != null) return@forEach
            when (val v = DownloadCheck.compare(files[i].hashes, ExpectedFile(rom.name, rom.size, rom.crc, rom.sha1))) {
                FileVerdict.Match -> matched[i] = true
                is FileVerdict.Mismatch -> mismatches[i] = v
                else -> Unit
            }
        }
    }

    fun verdicts(): List<FileVerdict> = files.indices.map { i ->
        when {
            matched[i] -> FileVerdict.Match
            else -> mismatches[i] ?: FileVerdict.NotListed
        }
    }
}
