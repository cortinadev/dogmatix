// Port of MAME's CHD/CD-ROM code (src/lib/util: chd.cpp, chdcodec.cpp, cdrom.cpp, huffman.cpp, bitstream.h),
// BSD-3-Clause, copyright-holders Aaron Giles, R. Belmont. Binary redistribution must reproduce the BSD-3 notice.
package com.cortinadev.dogmatix.data.service.convert.chd

/*
 * Table of contents of a CD image (.cue, .gdi, .iso), parsed the way MAME's chdman 0.276 does it
 * (src/lib/util/cdrom.cpp: parse_cue, parse_gdi, parse_iso; BSD-3-Clause, Copyright Aaron Giles and
 * the MAME team). Track lengths, pregaps, padding and the CHT2/CHGD metadata strings have to match
 * chdman byte for byte, because emulators compute every LBA from them and the CHD's overall SHA-1
 * covers the metadata. The differences are deliberate leniencies: keywords are matched without
 * case, a UTF-8 BOM is skipped and WAVE files are refused with a clear error instead of parsed.
 */

/** Track types in the order of MAME's enum (CD_TRACK_MODE1 = 0): the default pregap type is MODE1. */
enum class CdTrackType(val metaName: String, val dataSize: Int) {
    MODE1("MODE1", 2048),
    MODE1_RAW("MODE1_RAW", 2352),
    MODE2("MODE2", 2336),
    MODE2_FORM1("MODE2_FORM1", 2048),
    MODE2_FORM2("MODE2_FORM2", 2324),
    MODE2_FORM_MIX("MODE2_FORM_MIX", 2336),
    MODE2_RAW("MODE2_RAW", 2352),
    AUDIO("AUDIO", 2352);

    companion object {
        /** A track type as written in a cue sheet (`MODE1/2352`) or in CHD metadata (`MODE1_RAW`). */
        fun parse(token: String): CdTrackType? = when (token) {
            "MODE1", "MODE1/2048" -> MODE1
            "MODE1_RAW", "MODE1/2352" -> MODE1_RAW
            "MODE2", "MODE2/2336" -> MODE2
            "MODE2_FORM1", "MODE2/2048" -> MODE2_FORM1
            "MODE2_FORM2", "MODE2/2324" -> MODE2_FORM2
            "MODE2_FORM_MIX" -> MODE2_FORM_MIX
            "MODE2_RAW", "MODE2/2352", "CDI/2352" -> MODE2_RAW
            "AUDIO" -> AUDIO
            else -> null
        }
    }
}

enum class CdSubType(val metaName: String, val size: Int) {
    NORMAL("RW", 96),
    RAW("RW_RAW", 96),
    NONE("NONE", 0),
}

/** One track as chdman lays it out. [frames] includes [padFrames] and [splitFrames]; not the 4-frame padding. */
class CdTrack(
    var type: CdTrackType,
    var file: String,
) {
    var dataSize = type.dataSize
    var subType = CdSubType.NONE
    var subSize = 0
    var frames = 0
    var padFrames = 0
    var splitFrames = 0
    var pregap = 0
    var pgType = CdTrackType.MODE1
    var pgDataSize = 0
    var pgSub = CdSubType.NONE
    var postgap = 0
    var physFrameOfs = 0
    var session = 0
    var highDensity = false

    /** Byte offset of the track's first frame in [file]. */
    var offset = 0L

    /** Audio is stored big-endian in a CHD: little-endian samples are swapped on the way in. */
    var swap = false

    internal val idx = IntArray(CdToc.MAX_INDEX + 1) { -1 }
    internal var leadIn = -1
    internal var leadOut = -1

    val bytesPerFrame: Int get() = dataSize + subSize

    /** chdman pads every track to a multiple of four frames in the CHD. */
    val extraFrames: Int get() = (frames + CdToc.TRACK_PADDING - 1) / CdToc.TRACK_PADDING * CdToc.TRACK_PADDING - frames
}

class CdToc(val tracks: List<CdTrack>, val gdrom: Boolean) {

    /** Frames in the CHD (each [FRAME_SIZE] bytes), padding included. */
    val totalFrames: Long get() = tracks.sumOf { (it.frames + it.extraFrames).toLong() }

    val logicalBytes: Long get() = totalFrames * FRAME_SIZE

    /** The metadata chdman writes: tag and text for each track (the CHD stores the text NUL-terminated). */
    fun metadata(): List<Pair<String, String>> = tracks.mapIndexed { i, t ->
        if (gdrom) {
            GDROM_TAG to "TRACK:${i + 1} TYPE:${t.type.metaName} SUBTYPE:${t.subType.metaName} " +
                "FRAMES:${t.frames} PAD:${t.padFrames} PREGAP:${t.pregap} PGTYPE:${t.pgType.metaName} " +
                "PGSUB:${t.pgSub.metaName} POSTGAP:${t.postgap}"
        } else {
            val pgType = if (t.pgDataSize > 0) "V" + t.pgType.metaName else t.pgType.metaName
            CDROM_TAG to "TRACK:${i + 1} TYPE:${t.type.metaName} SUBTYPE:${t.subType.metaName} " +
                "FRAMES:${t.frames} PREGAP:${t.pregap} PGTYPE:$pgType PGSUB:${t.pgSub.metaName} " +
                "POSTGAP:${t.postgap}"
        }
    }

    /** Names of the files the tracks read from, in order, without repeats. */
    val files: List<String> get() = tracks.map { it.file }.distinct()

    companion object {
        const val FRAME_SIZE = 2448
        const val MAX_SECTOR_DATA = 2352
        const val MAX_SUBCODE_DATA = 96
        const val FRAMES_PER_HUNK = 8
        const val TRACK_PADDING = 4
        const val MAX_TRACKS = 99
        const val MAX_INDEX = 99
        const val CDROM_TAG = "CHT2"
        const val GDROM_TAG = "CHGD"
    }
}

class CdSheetException(message: String) : Exception(message)

/**
 * Parsers. `fileSize` answers the size of a file named in the sheet (as written there, without the
 * sheet's folder), or null if it is not available.
 */
object CdSheets {

    fun parse(sheetName: String, text: String, fileSize: (String) -> Long?): CdToc =
        when (sheetName.substringAfterLast('.', "").lowercase()) {
            "cue" -> parseCue(text, fileSize)
            "gdi" -> parseGdi(text, fileSize)
            else -> throw CdSheetException("Not a cue or gdi sheet: $sheetName")
        }

    /** A plain image: 2048-byte sectors make a MODE1 track, 2336 MODE2 and 2352 MODE2_RAW. */
    fun parseIso(name: String, size: Long): CdToc {
        val type = when {
            size > 0 && size % 2048 == 0L -> CdTrackType.MODE1
            size > 0 && size % 2336 == 0L -> CdTrackType.MODE2
            size > 0 && size % 2352 == 0L -> CdTrackType.MODE2_RAW
            else -> throw CdSheetException("Unrecognised image size $size: not a multiple of 2048, 2336 or 2352")
        }
        val track = CdTrack(type, name).apply { frames = (size / type.dataSize).toInt() }
        return CdToc(listOf(track), gdrom = false)
    }

    // ---------------------------------------------------------------------------------------- cue

    private enum class Area { SINGLE, HIGH }

    fun parseCue(text: String, fileSize: (String) -> Long?): CdToc {
        val lines = text.removePrefix("\uFEFF").lines()
        val isGdrom = lines.any { remBody(it)?.startsWith("SINGLE-DENSITY AREA") == true } &&
            lines.any { remBody(it)?.startsWith("HIGH-DENSITY AREA") == true }

        val tracks = arrayOfNulls<CdTrack>(CdToc.MAX_TRACKS)
        var trk = -1
        var session = 0
        var sessionPregap = 0
        var multisession = false
        var multibin = false
        var lastFile: String? = null
        var pendingSwap = false
        var area = Area.SINGLE
        var leadIn = -1

        fun track(): CdTrack = (if (trk in tracks.indices) tracks[trk] else null)
            ?: throw CdSheetException("Command outside a TRACK")

        for (line in lines) {
            val tok = Tokens(line)
            when (tok.next().uppercase()) {
                "REM" -> {
                    val body = remBody(line) ?: ""
                    when {
                        body.startsWith("SESSION") -> {
                            tok.next()
                            session = (tok.next().toIntOrNull() ?: 1) - 1
                            if (session >= 1) multisession = true
                        }
                        multisession && body.startsWith("PREGAP") -> {
                            tok.next(); sessionPregap = msfToFrames(tok.next())
                        }
                        body.startsWith("LEAD-OUT") -> {
                            tok.next(); track().leadOut = msfToFrames(tok.next())
                        }
                        body.startsWith("LEAD-IN") -> {
                            tok.next(); leadIn = msfToFrames(tok.next())
                        }
                        isGdrom && body.startsWith("SINGLE-DENSITY AREA") -> area = Area.SINGLE
                        isGdrom && body.startsWith("HIGH-DENSITY AREA") -> area = Area.HIGH
                    }
                }
                "FILE" -> {
                    val name = tok.next()
                    if (!multibin) multibin = lastFile != null && lastFile != name
                    lastFile = name
                    when (tok.next().uppercase()) {
                        "BINARY" -> pendingSwap = false
                        "MOTOROLA" -> pendingSwap = true
                        "WAVE" -> throw CdSheetException("WAVE files in cue sheets are not supported")
                        else -> throw CdSheetException("Unsupported FILE type in: ${line.trim()}")
                    }
                }
                "TRACK" -> {
                    val number = tok.next().toIntOrNull() ?: throw CdSheetException("Bad TRACK line: ${line.trim()}")
                    if (number !in 1..CdToc.MAX_TRACKS) throw CdSheetException("Track number out of range: $number")
                    trk = number - 1
                    val typeToken = tok.next().uppercase()
                    val type = CdTrackType.parse(typeToken)
                        ?: throw CdSheetException("Unknown track type: $typeToken")
                    val file = lastFile ?: throw CdSheetException("TRACK $number before any FILE")
                    tracks[trk] = CdTrack(type, file).apply {
                        this.session = session
                        highDensity = isGdrom && area == Area.HIGH
                        swap = pendingSwap
                        this.leadIn = leadIn
                        if (sessionPregap != 0) {
                            this.leadIn = if (this.leadIn == -1) sessionPregap else this.leadIn + sessionPregap
                            sessionPregap = 0
                        }
                        when (tok.next().uppercase()) {
                            "RW" -> { subType = CdSubType.NORMAL; subSize = 96 }
                            "RW_RAW" -> { subType = CdSubType.RAW; subSize = 96 }
                        }
                    }
                    leadIn = -1
                }
                "INDEX" -> {
                    val idx = tok.next().toIntOrNull() ?: -1
                    val frames = msfToFrames(tok.next())
                    if (idx !in 0..CdToc.MAX_INDEX) throw CdSheetException("Invalid index $idx")
                    val t = track()
                    t.idx[idx] = frames
                    if (idx == 1) {
                        if (t.pregap == 0 && t.idx[0] != -1) {
                            t.pregap = frames - t.idx[0]
                            t.pgType = t.type
                            t.pgDataSize = t.dataSize
                        } else if (t.idx[0] == -1) {
                            t.idx[0] = frames
                        }
                    }
                }
                "PREGAP" -> track().pregap = msfToFrames(tok.next())
                "POSTGAP" -> track().postgap = msfToFrames(tok.next())
            }
        }

        val count = trk + 1
        if (count <= 0) throw CdSheetException("The cue sheet has no tracks")
        val list = (0 until count).map { tracks[it] ?: throw CdSheetException("Track ${it + 1} is missing") }

        for ((n, t) in list.withIndex()) {
            if (t.idx[1] == -1) throw CdSheetException("Track ${n + 1} has no INDEX 01")
            // True for cue/bin: audio is little-endian on disc images and big-endian in a CHD.
            if (t.type == CdTrackType.AUDIO) t.swap = true
            val prev = list.getOrNull(n - 1)
            val next = list.getOrNull(n + 1)
            if (next == null && prev != null && t.file == prev.file) {
                val size = sizeOf(t.file, fileSize)
                t.offset = prev.offset + prev.frames.toLong() * prev.bytesPerFrame
                t.frames = ((size - t.offset) / t.bytesPerFrame).toInt()
            } else if (next != null && t.file == next.file) {
                t.frames = next.idx[0] - t.idx[0]
                if (t.frames == 0) throw CdSheetException("Cannot work out the size of track ${n + 1}")
                if (prev != null) t.offset = prev.offset + prev.frames.toLong() * prev.bytesPerFrame
            } else if (t.frames == 0) {
                t.frames = (sizeOf(t.file, fileSize) / t.bytesPerFrame).toInt()
                t.offset = 0
            }

            if (multisession) {
                if (multibin) {
                    if (t.leadOut == -1 && next != null && t.session != next.session) {
                        t.leadOut = if (t.session == 0) 6750 else 2250
                    }
                    if (t.leadIn == -1 && prev != null && t.session != prev.session) t.leadIn = 4500
                } else {
                    if (t.leadOut != -1) {
                        val endFrames = t.leadOut - t.idx[0]
                        if (t.frames >= endFrames) {
                            t.frames = endFrames
                            if (next != null) t.leadOut = next.idx[0] - t.leadOut
                        }
                    }
                    if (prev != null && prev.leadOut != -1 && !prev.file.lowercase().endsWith(".img")) {
                        prev.padFrames += prev.leadOut
                        t.frames -= prev.leadOut
                        t.offset += prev.leadOut.toLong() * t.bytesPerFrame
                    }
                }
            }
        }

        if (isGdrom) {
            // Strip the Redump pregaps and move them to the end of the previous track (TOSEC layout).
            for (n in 1 until list.size) {
                val t = list[n]
                val p = list[n - 1]
                p.frames += t.pregap
                p.splitFrames += t.pregap
                t.offset += t.pregap.toLong() * t.bytesPerFrame
                t.frames -= t.pregap
                t.idx[1] -= t.pregap
                t.pregap = 0
                t.pgType = CdTrackType.MODE1
            }
            for (n in 1 until list.size) {
                val t = list[n]
                val p = list[n - 1]
                if (t.highDensity && !p.highDensity) {
                    t.physFrameOfs = 45000
                    val dif = t.physFrameOfs - (p.frames + p.physFrameOfs)
                    p.frames += dif
                    p.padFrames = dif
                } else {
                    t.physFrameOfs = p.physFrameOfs + p.frames
                }
            }
        }
        for ((n, t) in list.withIndex()) {
            if (t.frames <= 0) throw CdSheetException("Track ${n + 1} is empty or its file is too short")
        }
        return CdToc(list, isGdrom)
    }

    private fun remBody(line: String): String? {
        val t = line.trimStart()
        if (!t.startsWith("REM", ignoreCase = true) || (t.length > 3 && !t[3].isWhitespace())) return null
        return t.substring(3).trimStart()
    }

    // ---------------------------------------------------------------------------------------- gdi

    fun parseGdi(text: String, fileSize: (String) -> Long?): CdToc {
        val lines = text.removePrefix("\uFEFF").lines()
        val count = Tokens(lines.firstOrNull() ?: "").next().toIntOrNull()
            ?: throw CdSheetException("The GDI has no track count")
        if (count <= 0) throw CdSheetException("The GDI has no tracks")
        if (count > CdToc.MAX_TRACKS) throw CdSheetException("Too many tracks in the GDI: $count")
        val tracks = arrayOfNulls<CdTrack>(count)
        for (line in lines.drop(1)) {
            val tok = Tokens(line)
            val first = tok.next()
            if (first.isEmpty()) continue
            val params = mutableListOf(first)
            while (true) {
                val t = tok.next()
                if (t.isEmpty()) break
                params += t
            }
            if (params.size != 6) throw CdSheetException("A GDI track line needs 6 fields: ${line.trim()}")
            val n = (params[0].toIntOrNull() ?: 0) - 1
            if (n !in 0 until count) throw CdSheetException("Track ${n + 1} is out of range 1..$count")
            val lba = params[1].toIntOrNull() ?: throw CdSheetException("Bad LBA in: ${line.trim()}")
            val kind = params[2].toIntOrNull()
            val sectorSize = params[3].toIntOrNull()
            val type = when {
                kind == 4 && sectorSize == 2352 -> CdTrackType.MODE1_RAW
                kind == 4 && sectorSize == 2048 -> CdTrackType.MODE1
                kind == 0 -> CdTrackType.AUDIO
                else -> throw CdSheetException("Unknown GDI track type $kind with sector size $sectorSize")
            }
            val track = CdTrack(type, params[4]).apply {
                physFrameOfs = lba
                dataSize = if (type == CdTrackType.AUDIO) 2352 else type.dataSize
                swap = type == CdTrackType.AUDIO
                // chdman divides by the size in the sheet (2352 for audio is the only sane value).
                frames = (sizeOf(file, fileSize) / (sectorSize ?: 2352)).toInt()
            }
            if (n > 0) {
                val prev = tracks[n - 1] ?: throw CdSheetException("GDI tracks must be in order")
                val dif = lba - (prev.frames + prev.physFrameOfs)
                prev.frames += dif
                prev.padFrames = dif
            }
            tracks[n] = track
        }
        val list = tracks.mapIndexed { i, t -> t ?: throw CdSheetException("The GDI is missing track ${i + 1}") }
        for ((n, t) in list.withIndex()) {
            if (t.frames <= 0) throw CdSheetException("Track ${n + 1} is empty or overlaps the next one")
        }
        return CdToc(list, gdrom = true)
    }

    // ------------------------------------------------------------------------------------ helpers

    private fun sizeOf(name: String, fileSize: (String) -> Long?): Long {
        val size = fileSize(name) ?: throw CdSheetException("Missing file: $name")
        if (size <= 0) throw CdSheetException("Empty file: $name")
        return size
    }

    /** `mm:ss:ff` → frames; a bare number is taken as frames (like sscanf in chdman). */
    fun msfToFrames(token: String): Int {
        val parts = token.split(':')
        if (parts.size == 1) return leadingInt(parts[0])
        val m = leadingInt(parts[0])
        val s = leadingInt(parts.getOrElse(1) { "0" })
        val f = leadingInt(parts.getOrElse(2) { "0" })
        return (m * 60 + s) * 75 + f
    }

    private fun leadingInt(s: String): Int = Regex("^\\s*[-+]?\\d+").find(s)?.value?.trim()?.toInt() ?: 0

    /** chdman's tokenizer: whitespace separated, with "double" and 'single' quotes grouping. */
    private class Tokens(private val line: String) {
        private var i = 0
        fun next(): String {
            while (i < line.length && line[i].isWhitespace()) i++
            val sb = StringBuilder()
            var single = false
            var double = false
            while (i < line.length) {
                val c = line[i]
                when {
                    !single && c == '"' -> double = !double
                    !double && c == '\'' -> single = !single
                    !single && !double && c.isWhitespace() -> break
                    else -> sb.append(c)
                }
                i++
            }
            return sb.toString()
        }
    }
}
