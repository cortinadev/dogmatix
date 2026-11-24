package com.cortinadev.dogmatix.util

import java.util.Locale

/** The formats a ROM can be converted to from the app. */
enum class ConversionFormat(val label: String, val extension: String) {
    /** An original Xbox Redump ISO rewritten as the game partition alone (what xemu reads). */
    XISO("XISO", "iso"),
    /** MAME's compressed hunks of disc images (CD from cue/bin, gdi or iso; DVD from a PS2/PSP iso). */
    CHD("CHD", "chd"),
    /** Dolphin's compressed GameCube / Wii image. */
    RVZ("RVZ", "rvz")
}

/** How chdman would take a disc: `createcd` (cue/bin, gdi, any iso but the PSP's) or `createdvd` (PSP iso). */
enum class ChdMode { CD, DVD }

/**
 * Which conversion a ROM file admits, from its console and its name only (no reading): the
 * converter then looks inside ([com.cortinadev.dogmatix.data.service.RomConverter.inspect]), e.g.
 * an Xbox ISO that already is an XISO is left alone. Pure, for the tests.
 *
 * A console is told by the names it answers to ([namesFor]): its id key, short name, the folder
 * names configured or known for it ([ConsoleFolderAliases]) and the folder the file sits in, all
 * normalized ("Sega CD" → `segacd`).
 */
object RomConversions {

    /** One kind of system: the names that select it, the extensions it converts and the output. */
    data class Rule(val format: ConversionFormat, val systems: Set<String>, val extensions: Set<String>, val mode: ChdMode? = null)

    /** The original Xbox only: never the 360 (its ISOs are XGD2/3 and xemu does not run them). */
    private val xbox = setOf("xbox", "microsoftxbox", "originalxbox", "xboxoriginal")
    private val notOriginalXbox = Regex("360|xboxone|seriesx|seriess")

    private val cdSystems = setOf(
        "psx", "ps1", "ps", "playstation", "sonyplaystation",
        "saturn", "segasaturn",
        "dreamcast", "dc", "segadreamcast",
        "segacd", "megacd", "cd", "scd", "segamegacd",
        "pcenginecd", "pcecd", "tgcd", "turbografxcd", "turbografx16cd"
    )
    private val ps2 = setOf("ps2", "playstation2", "sonyplaystation2")
    private val psp = setOf("psp", "playstationportable", "sonypsp")
    private val gamecubeWii = setOf("gc", "ngc", "gamecube", "nintendogamecube", "wii", "nintendowii")

    val rules: List<Rule> = listOf(
        Rule(ConversionFormat.XISO, xbox, setOf("iso")),
        Rule(ConversionFormat.CHD, cdSystems, setOf("cue", "gdi", "iso"), ChdMode.CD),
        // PS2: CD mode for its isos too. NetherSX2 / AetherSX2 do not open a CHD made with
        // createdvd, but do open one made with createcd from the iso (about the same size).
        Rule(ConversionFormat.CHD, ps2, setOf("iso", "cue"), ChdMode.CD),
        Rule(ConversionFormat.CHD, psp, setOf("iso"), ChdMode.DVD),
        Rule(ConversionFormat.RVZ, gamecubeWii, setOf("iso", "gcm", "wbfs"))
    )

    /** Archives a download may still be in (or was unpacked from): what is inside is decided once on disk. */
    private val archives = setOf("zip", "7z", "rar")

    /** The normalized names [consoleId] answers to, plus the [folder] the file is in (either may be absent). */
    fun namesFor(consoleId: String?, folder: String? = null): Set<String> = buildSet {
        if (!consoleId.isNullOrBlank()) addAll(ConsoleFolderAliases.candidatesFor(consoleId))
        if (!folder.isNullOrBlank()) add(ConsoleFolderAliases.normalize(folder))
    }

    /** The rule for a file called [fileName] of a system known by [names]; null when it converts to nothing. */
    fun ruleFor(names: Set<String>, fileName: String): Rule? {
        if (names.isEmpty()) return null
        val ext = extensionOf(fileName) ?: return null
        return rules.firstOrNull { rule -> ext in rule.extensions && matches(rule, names) }
    }

    fun formatFor(names: Set<String>, fileName: String): ConversionFormat? = ruleFor(names, fileName)?.format

    fun formatFor(consoleId: String?, folder: String?, fileName: String): ConversionFormat? = formatFor(namesFor(consoleId, folder), fileName)

    /**
     * For a row that may still name its archive (`Game (USA).zip`): the format its unpacked game
     * would take, when the system has a single one. Null for anything else; once the file on disk
     * is known, [formatFor] decides.
     */
    fun likelyFormat(names: Set<String>, fileName: String): ConversionFormat? {
        formatFor(names, fileName)?.let { return it }
        if (extensionOf(fileName) !in archives) return null
        return rules.filter { matches(it, names) }.map { it.format }.distinct().singleOrNull()
    }

    fun chdMode(names: Set<String>, fileName: String): ChdMode? = ruleFor(names, fileName)?.takeIf { it.format == ConversionFormat.CHD }?.mode

    private fun matches(rule: Rule, names: Set<String>): Boolean {
        if (names.none { it in rule.systems }) return false
        // An Xbox 360 folder or alias must never pass for the original one.
        return rule.format != ConversionFormat.XISO || names.none { notOriginalXbox.containsMatchIn(it) }
    }

    /**
     * The converted file's name. An XISO keeps the ISO's name (xemu and ES-DE read `.iso`, and the
     * library's ✓ and the download stay matched); CHD and RVZ swap the extension.
     */
    fun outputName(format: ConversionFormat, inputName: String): String = when (format) {
        ConversionFormat.XISO -> inputName
        else -> MultiDisc.stripExtension(inputName) + "." + format.extension
    }

    /**
     * What a conversion saves: the input files ([inputBytes]) against the output ([outputBytes]);
     * null when the output size is not known before converting.
     */
    fun savedBytes(inputBytes: Long, outputBytes: Long?): Long? = outputBytes?.let { (inputBytes - it).coerceAtLeast(0) }

    /** The least a conversion has to save, in percent of the input, to replace the original. */
    const val MIN_SAVING_PERCENT = 5

    /**
     * Whether a converted copy of [outputBytes] is worth keeping against the input files
     * ([inputBytes]: the sheet and its tracks, or the image): only if it saves at least
     * [MIN_SAVING_PERCENT] of them. Otherwise it is discarded and the original stays. An unknown
     * size (negative) is not judged.
     */
    fun savesSpace(inputBytes: Long, outputBytes: Long): Boolean =
        outputBytes < 0 || (inputBytes > 0 && (inputBytes - outputBytes) * 100 >= inputBytes * MIN_SAVING_PERCENT)

    /**
     * A playlist's text with every line that names [oldName] (by its last path segment, without
     * case) pointing at [newName] instead, keeping the line's folder part; null when no line does.
     */
    fun rewritePlaylist(text: String, oldName: String, newName: String): String? {
        var changed = false
        val lines = text.split('\n').map { raw ->
            val line = raw.trimEnd('\r')
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@map raw
            val cut = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
            val leaf = trimmed.substring(cut + 1)
            if (!leaf.equals(oldName, ignoreCase = true)) return@map raw
            changed = true
            trimmed.substring(0, cut + 1) + newName + if (raw.endsWith("\r")) "\r" else ""
        }
        return if (changed) lines.joinToString("\n") else null
    }

    /**
     * Compression threads for a conversion on the device (RVZ and CHD): 2 on small or low-memory
     * devices (4 cores or fewer, or `ActivityManager.isLowRamDevice`), otherwise one per core up to 4.
     */
    fun threadsFor(cores: Int, lowRam: Boolean): Int = if (lowRam || cores <= 4) 2 else cores.coerceAtMost(4)

    /**
     * The files a `.cue` / `.gdi` ([sheetExtension]) names, by their last path segment and lower
     * case (`.\\Game (Track 1).bin` → `game (track 1).bin`): the tracks to look for next to it.
     */
    fun trackNames(sheetExtension: String, text: String): Set<String> {
        val refs = when (sheetExtension.lowercase(Locale.ROOT)) {
            "cue" -> MultiDisc.cueFiles(text)
            "gdi" -> MultiDisc.gdiFiles(text)
            else -> emptyList()
        }
        return refs.mapTo(LinkedHashSet()) { ref -> ref.substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.ROOT) }
    }

    /**
     * Where a download's file [path] (relative to its console folder) may be on disk, in order:
     * where the download left it, then the `<game>.m3u/` folder MultiDiscService moves a disc of a
     * multi-disc game into (`Game (Disc 1).cue` → `Game.m3u/Game (Disc 1).cue`).
     */
    fun placesOf(path: String): List<String> {
        val clean = path.replace('\\', '/').trim('/')
        if (clean.isEmpty()) return emptyList()
        val leaf = clean.substringAfterLast('/')
        return listOfNotNull(clean, discSetFolder(leaf)?.let { "$it/$leaf" }).distinct()
    }

    /** The `<game>.m3u` folder a disc called [fileName] is gathered into; null when the name tells no disc. */
    fun discSetFolder(fileName: String): String? = MultiDisc.discOf(fileName)?.let { "${it.setStem}.m3u" }

    /**
     * Whether a download that finished at [finishedAt] has been converted since: its last
     * conversion not undone happened at [convertedAt] (null = none). A download done again after
     * a conversion ("download again") is a fresh, unconverted file.
     */
    fun convertedSince(convertedAt: Long?, finishedAt: Long?): Boolean =
        convertedAt != null && (finishedAt == null || convertedAt >= finishedAt)

    /** The labels of [formats] for one title or line ("XISO", "XISO / RVZ"), in the enum's order. */
    fun labels(formats: Collection<ConversionFormat>): String =
        formats.distinct().sortedBy { it.ordinal }.joinToString(" / ") { it.label }

    /**
     * Whether a file on disk called [diskName] is the same title as a catalogue row whose title
     * key is [titleKey] (the row's own title, [VersionGrouping.groupKey] of its name; not the
     * canonical game key a clone list may give it, which would also take a translated release).
     */
    fun sameTitle(diskName: String, titleKey: String): Boolean =
        titleKey.isNotEmpty() && VersionGrouping.groupKey(diskName.substringAfterLast('/')) == titleKey

    /**
     * A WBFS drops the disc's unused data, so its RVZ decodes to an image no Redump DAT lists
     * (it still plays). True for such inputs.
     */
    fun losesDatMatch(fileName: String): Boolean = extensionOf(fileName) == "wbfs"

    private fun extensionOf(fileName: String): String? =
        fileName.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it.isNotEmpty() && it.length <= 5 }
}
