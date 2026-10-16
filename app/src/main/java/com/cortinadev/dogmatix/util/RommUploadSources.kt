package com.cortinadev.dogmatix.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * One file of an upload to RomM, as recorded to tell whether a session cut short still matches
 * what is on disk. [path] is relative to the console's download folder; [crc32] and [dosTime] are
 * filled in for archives only (null until worked out).
 */
data class UploadSource(
    val path: String,
    val size: Long,
    val modified: Long,
    val crc32: Long? = null,
    val dosTime: Long? = null
)

/**
 * What goes up for a finished download. One file goes as it is. A game made of several files
 * (a `.cue` and its tracks, a `.gdi`, an extracted folder, a multi-disc `<game>.m3u/` folder)
 * goes as one `<game>.zip`: RomM's upload API only writes a single file into the platform folder
 * (5.0–5.2), and 5.3+ can add files only to a game the server already scanned, so an archive is
 * the one way every server version lists the game once with all its files.
 */
object RommUploadSources {

    fun needsArchive(paths: List<String>): Boolean = paths.size > 1

    /** `Game (USA).7z` → `Game (USA).zip`; a `.zip` keeps its name. */
    fun archiveName(downloadName: String): String {
        val base = downloadName.replace('\\', '/').substringAfterLast('/').trim()
        if (base.endsWith(".zip", ignoreCase = true)) return base
        return MultiDisc.stripExtension(base).ifEmpty { base } + ".zip"
    }

    /** The path of [path] inside the archive: below [folder] (`<game>.m3u`), or as it is. */
    fun entryName(folder: String, path: String): String {
        val clean = path.replace('\\', '/').trimStart('/')
        return if (folder.isNotEmpty() && clean.startsWith("$folder/")) clean.substring(folder.length + 1) else clean
    }

    /** Same files, same bytes: every field of every source, in order. */
    fun signature(sources: List<UploadSource>): String =
        sources.joinToString("\n") { "${it.path}|${it.size}|${it.modified}|${it.crc32 ?: ""}|${it.dosTime ?: ""}" }

    /** Checksums worth keeping from an earlier try: same path, size and modification time. */
    fun reuseChecksums(previous: List<UploadSource>, current: List<UploadSource>): List<UploadSource> {
        val known = previous.associateBy { it.path }
        return current.map { source ->
            val before = known[source.path]
            if (before != null && before.size == source.size && before.modified == source.modified && before.crc32 != null && before.dosTime != null)
                source.copy(crc32 = before.crc32, dosTime = before.dosTime)
            else source
        }
    }

    /** Written field by field (no reflection), so a minified build reads what an older one wrote. */
    fun encode(sources: List<UploadSource>): String = JsonArray().apply {
        sources.forEach { s ->
            add(JsonObject().apply {
                addProperty("path", s.path)
                addProperty("size", s.size)
                addProperty("modified", s.modified)
                s.crc32?.let { addProperty("crc32", it) }
                s.dosTime?.let { addProperty("dosTime", it) }
            })
        }
    }.toString()

    /** Never throws: something unreadable just means nothing to resume. */
    fun decode(text: String?): List<UploadSource> = runCatching {
        JsonParser.parseString(text.orEmpty()).asJsonArray.map { element ->
            val o = element.asJsonObject
            fun long(name: String) = o.get(name)?.takeUnless { it.isJsonNull }?.asLong
            UploadSource(o.get("path").asString, long("size")!!, long("modified") ?: 0L, long("crc32"), long("dosTime"))
        }.filter { it.path.isNotBlank() && it.size >= 0 }
    }.getOrDefault(emptyList())

    /**
     * The `x-upload-filename` header. Android's HTTP stack only takes printable ASCII in a header,
     * so anything else is percent-encoded there; RomM 5.3+ reads the real name from the body.
     */
    fun headerName(name: String): String {
        if (name.all { it.code in 0x20..0x7E }) return name
        val out = StringBuilder()
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            if (cp in 0x20..0x7E && cp != '%'.code) out.appendCodePoint(cp)
            else String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).forEach { out.append('%').append("%02X".format(it.toInt() and 0xFF)) }
            i += Character.charCount(cp)
        }
        return out.toString()
    }
}
