package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.GameVersion
import com.cortinadev.dogmatix.data.model.VersionPrefs
import java.util.Locale

/**
 * Groups the library's files into games and ranks each game's versions. A game is every file of
 * a console sharing [groupKey] (the indexed name without tags); a version is one release inside it
 * (same file name save for the disc tag, from the same source), holding one file per disc.
 */
object VersionGrouping {

    /**
     * Key shared by every version of one game in its console: the indexed name (already without
     * the `(…)` tags) minus extension, `[…]` dump flags and punctuation noise, lower-cased.
     */
    fun groupKey(name: String): String {
        val cleaned = MultiDisc.stripExtension(name.trim())
            .replace(Regex("""\[[^]]*]"""), " ")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim().trimEnd('-', ',', '.', ' ').trim()
            .lowercase(Locale.ROOT)
        return cleaned.ifEmpty { name.trim().lowercase(Locale.ROOT) }
    }

    /** The discs of one release share this: the file name without disc tag nor extension. */
    fun versionKey(fileName: String): String =
        MultiDisc.withoutDiscTag(MultiDisc.stripExtension(leaf(fileName))).lowercase(Locale.ROOT)

    /**
     * The versions among [rows] (all of one game), best first for [prefs]. The same release from
     * two sources stays two versions; its discs are sorted by number. [byOrigin] orders copies of
     * one release by how their sources have been doing (see [VersionRanking.sameReleaseOrder]).
     */
    fun versionsOf(
        rows: List<DownloadableFileWithTags>,
        prefs: VersionPrefs,
        byOrigin: ((List<GameVersion>) -> List<GameVersion>)? = null
    ): List<GameVersion> {
        val versions = rows
            .groupBy { versionKey(it.file.fileName) + "|" + sourceOf(it) }
            .values
            .map { discs -> GameVersion(discs.sortedWith(compareBy({ MultiDisc.discOf(leaf(it.file.fileName))?.number ?: 0 }, { it.file.fileName }))) }
        val ranked = VersionRanking.rank(versions, prefs, tagsOf = { it.primary.tags }, fileNameOf = { leaf(it.primary.file.fileName) })
        if (byOrigin == null) return ranked
        return VersionRanking.sameReleaseOrder(ranked, releaseOf = { versionKey(it.primary.file.fileName) + "#" + it.discs.size }, order = byOrigin)
    }

    /** Tags to show for a whole version: its first disc's, without the disc number. */
    fun displayTags(version: GameVersion): List<String> =
        version.primary.tags.filterNot { MultiDisc.isDiscTag(it) }

    /** Torrent (by info-hash) or web folder the file comes from. */
    private fun sourceOf(row: DownloadableFileWithTags): String =
        row.file.torrentMagnet?.let { DebridMatcher.infoHashFromMagnet(it) ?: it }
            ?: row.file.downloadUrl.substringBeforeLast('/')

    private fun leaf(fileName: String): String =
        FileParsingUtils.decodeUrlEncodedFileName(fileName).replace('\\', '/').substringAfterLast('/')
}

/** Orders the versions of one game for the user's region and languages; nothing is ever hidden. */
object VersionRanking {

    private val nonRelease = setOf("DEMO", "BETA", "PROTO", "PROTOTYPE", "SAMPLE", "KIOSK", "DEBUG", "PIRATE", "HACK", "PREVIEW", "PROMO")
    private val badDump = Regex("""\[b\d*]""", RegexOption.IGNORE_CASE)
    private val revisionTag = Regex("""^REV\s*([0-9]+|[A-Z])$""")
    private val versionTag = Regex("""^V\s*(\d+(?:\.\d+)?)$""")
    private val languageCode = Regex("^[A-Z]{2,3}$")

    /** Language a region tag implies when a release lists none: `(USA)` is English, `(Japan)` Japanese… */
    private val impliedLanguage = mapOf(
        "USA" to "EN", "UK" to "EN", "UNITED KINGDOM" to "EN", "AUSTRALIA" to "EN", "CANADA" to "EN",
        "EUROPE" to "EN", "WORLD" to "EN", "JAPAN" to "JA", "SPAIN" to "ES", "MEXICO" to "ES",
        "FRANCE" to "FR", "GERMANY" to "DE", "ITALY" to "IT", "BRAZIL" to "PT", "PORTUGAL" to "PT",
        "KOREA" to "KO", "CHINA" to "ZH", "NETHERLANDS" to "NL", "SWEDEN" to "SV", "RUSSIA" to "RU", "POLAND" to "PL"
    )

    fun <T> rank(items: List<T>, prefs: VersionPrefs, tagsOf: (T) -> List<String>, fileNameOf: (T) -> String): List<T> =
        items.sortedWith(
            compareBy<T> { isNonRelease(tagsOf(it), fileNameOf(it)) }
                .thenByDescending { preferencePoints(tagsOf(it), prefs) }
                .thenByDescending { revision(tagsOf(it)) }
                .thenBy { !fileNameOf(it).contains("[!]") }
                .thenBy { tags -> tagsOf(tags).any { it.uppercase(Locale.ROOT).startsWith("ALT") } }
        )

    /**
     * Region match (own region highest, then the fallbacks in order) plus a fixed bonus when the
     * release is in one of the favourite languages, stated or implied by its region.
     */
    fun preferencePoints(tags: List<String>, prefs: VersionPrefs): Int {
        val upper = tags.map { it.uppercase(Locale.ROOT) }
        val order = prefs.region.order.map { it.uppercase(Locale.ROOT) }
        val regionPoints = upper.mapNotNull { tag -> order.indexOf(tag).takeIf { it >= 0 }?.let { (order.size - it) * 10 } }.maxOrNull() ?: 0
        val favourites = prefs.languages.map { it.uppercase(Locale.ROOT) }.toSet()
        return regionPoints + if (languagesOf(tags).any { it in favourites }) 25 else 0
    }

    /** The release's languages, upper-case: the ones its tags state, else the one its region implies. */
    fun languagesOf(tags: List<String>): List<String> {
        val upper = tags.map { it.uppercase(Locale.ROOT) }
        return upper.filter(::isLanguage).ifEmpty { upper.mapNotNull { impliedLanguage[it] } }
    }

    /**
     * The last tie-break: copies of one release ([releaseOf]: same file name and disc count, so
     * same region, languages and status) from different sources are reordered by [order], each
     * group within the positions it already holds. Nothing else moves, so the source never
     * outranks region, language or released / unreleased.
     */
    fun <T> sameReleaseOrder(ranked: List<T>, releaseOf: (T) -> String, order: (List<T>) -> List<T>): List<T> {
        val groups = ranked.withIndex().groupBy { releaseOf(it.value) }.values.filter { it.size > 1 }
        if (groups.isEmpty()) return ranked
        val out = ranked.toMutableList()
        groups.forEach { group ->
            order(group.map { it.value }).forEachIndexed { i, item -> out[group[i].index] = item }
        }
        return out
    }

    fun isNonRelease(tags: List<String>, fileName: String): Boolean =
        tags.any { tag -> tag.uppercase(Locale.ROOT).let { u -> u in nonRelease || nonRelease.any { u.startsWith("$it ") } } } ||
            badDump.containsMatchIn(fileName)

    /** "Rev 2" → 2, "Rev B" → 2, "v1.1" → 1.1; 0 for the original release. */
    fun revision(tags: List<String>): Double = tags.maxOfOrNull { tag ->
        val upper = tag.uppercase(Locale.ROOT).trim()
        revisionTag.find(upper)?.groupValues?.get(1)?.let { it.toDoubleOrNull() ?: (it[0] - 'A' + 1).toDouble() }
            ?: versionTag.find(upper)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: 0.0
    } ?: 0.0

    private fun isLanguage(tag: String): Boolean =
        languageCode.matches(tag) && tag !in Constants.Tags.ALL_REGIONS &&
            tag !in Constants.Tags.VIDEO_STANDARDS && tag !in Constants.Tags.CONTENT_TYPES
}
