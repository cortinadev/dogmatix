package com.cortinadev.dogmatix.util

import java.io.Reader

/** One file of a DAT with only what matching needs; hashes are normalised hex, null when the DAT has none. */
data class DatRom(val name: String, val size: Long, val crc: String?, val sha1: String?)

/** The DAT's own name and version, from its header. */
data class DatHeader(val name: String, val version: String)

/**
 * Reads the DATs of No-Intro, Redump and the like in both layouts: Logiqx XML
 * (`<game name=…><rom name=… size=… crc=… sha1=…/></game>`, also `<machine>`) and ClrMamePro text
 * (`game ( name "…" rom ( name "…" size … crc … sha1 … ) )`, what libretro-database ships).
 * Streams the input and keeps nothing but the roms it hands to `onRom`: a full Redump set runs to
 * tens of thousands of entries.
 */
object DatParser {

    fun parse(reader: Reader, onRom: (DatRom) -> Unit): DatHeader {
        val input = CharSource(reader)
        input.skipBomAndSpace()
        return if (input.peek() == '<'.code) parseXml(input, onRom) else parseClrMamePro(input, onRom)
    }

    fun parse(text: String): Pair<DatHeader, List<DatRom>> {
        val roms = mutableListOf<DatRom>()
        val header = parse(text.reader()) { roms += it }
        return header to roms
    }

    // ---- Logiqx XML -----------------------------------------------------------------------------

    private fun parseXml(input: CharSource, onRom: (DatRom) -> Unit): DatHeader {
        var name = ""
        var version = ""
        var inHeader = false
        var capture: String? = null
        val text = StringBuilder()
        val tag = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) break
            if (c != '<'.code) {
                if (capture != null) text.append(c.toChar())
                continue
            }
            tag.setLength(0)
            readTag(input, tag)
            if (tag.isEmpty() || tag[0] == '!' || tag[0] == '?') continue
            val closing = tag[0] == '/'
            val tagName = tagName(tag, if (closing) 1 else 0)
            if (closing) {
                when (tagName) {
                    "header" -> inHeader = false
                    "name", "version" -> if (capture == tagName) {
                        val value = unescape(text.toString()).trim()
                        if (tagName == "name") name = value else version = value
                        capture = null
                    }
                }
                continue
            }
            val selfClosing = tag[tag.length - 1] == '/'
            when (tagName) {
                "header" -> inHeader = !selfClosing
                "name", "version" -> if (inHeader && !selfClosing && capture == null) {
                    capture = tagName
                    text.setLength(0)
                }
                "rom" -> romOf(attributes(tag, tagName.length))?.let(onRom)
            }
        }
        return DatHeader(name, version)
    }

    /** Everything up to the `>` that closes the tag (outside quoted values), comments and CDATA whole. */
    private fun readTag(input: CharSource, out: StringBuilder) {
        var quote = -1
        while (true) {
            val c = input.read()
            if (c < 0) return
            val ch = c.toChar()
            if (quote < 0 && ch == '>') {
                if (out.startsWith("!--") && !out.endsWith("--")) { out.append(ch); continue }
                if (out.startsWith("![CDATA[") && !out.endsWith("]]")) { out.append(ch); continue }
                return
            }
            // Quotes group attribute values; in a comment or a declaration they are just text.
            if (out.isEmpty() || out[0] != '!') {
                if (quote < 0 && (ch == '"' || ch == '\'')) quote = c
                else if (c == quote) quote = -1
            }
            out.append(ch)
        }
    }

    private fun tagName(tag: CharSequence, from: Int): String {
        var end = from
        while (end < tag.length && !tag[end].isWhitespace() && tag[end] != '/') end++
        return tag.substring(from, end).lowercase()
    }

    /** The `key="value"` pairs of a tag after its name (keys lower-cased, values unescaped). */
    private fun attributes(tag: CharSequence, from: Int): Map<String, String> {
        val out = HashMap<String, String>(8)
        val n = tag.length
        var i = from
        while (i < n) {
            while (i < n && (tag[i].isWhitespace() || tag[i] == '/')) i++
            val keyStart = i
            while (i < n && tag[i] != '=' && !tag[i].isWhitespace() && tag[i] != '/') i++
            if (i == keyStart) { i++; continue }
            val key = tag.substring(keyStart, i).lowercase()
            while (i < n && tag[i].isWhitespace()) i++
            if (i >= n || tag[i] != '=') continue
            i++
            while (i < n && tag[i].isWhitespace()) i++
            if (i >= n) break
            val q = tag[i]
            if (q == '"' || q == '\'') {
                val close = tag.indexOf(q, i + 1).let { if (it < 0) n else it }
                out[key] = unescape(tag.substring(i + 1, close))
                i = close + 1
            } else {
                val start = i
                while (i < n && !tag[i].isWhitespace() && tag[i] != '/') i++
                out[key] = unescape(tag.substring(start, i))
            }
        }
        return out
    }

    private fun unescape(s: String): String {
        if (s.indexOf('&') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val semi = if (c == '&') s.indexOf(';', i) else -1
            if (semi < 0 || semi - i > 10) { out.append(c); i++; continue }
            val entity = s.substring(i + 1, semi)
            val decoded = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x", ignoreCase = true) -> entity.substring(2).toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() }
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() }
                else -> null
            }
            if (decoded == null) { out.append(c); i++ } else { out.append(decoded); i = semi + 1 }
        }
        return out.toString()
    }

    // ---- ClrMamePro text ------------------------------------------------------------------------

    private const val EOF = 0
    private const val OPEN = 1
    private const val CLOSE = 2
    private const val WORD = 3
    private val gameBlocks = setOf("game", "machine", "resource", "set")

    private fun parseClrMamePro(input: CharSource, onRom: (DatRom) -> Unit): DatHeader {
        var name = ""
        var version = ""
        var depth = 0
        var block = ""
        // A key waiting for its value, or the name of the block that opens next.
        var pending: String? = null
        var inRom = false
        val fields = HashMap<String, String>()
        val token = StringBuilder()
        while (true) {
            when (nextToken(input, token)) {
                EOF -> break
                OPEN -> {
                    if (depth == 0) block = pending.orEmpty()
                    else if (depth == 1 && pending == "rom" && block in gameBlocks) { inRom = true; fields.clear() }
                    depth++
                    pending = null
                }
                CLOSE -> {
                    if (inRom && depth == 2) { romOf(fields)?.let(onRom); inRom = false }
                    depth = (depth - 1).coerceAtLeast(0)
                    pending = null
                }
                else -> {
                    val word = token.toString()
                    val key = pending
                    if (depth == 0 || key == null) {
                        pending = word.lowercase()
                    } else {
                        if (inRom && depth == 2) fields[key] = word
                        else if (depth == 1 && block == "clrmamepro") {
                            if (key == "name") name = word else if (key == "version") version = word
                        }
                        pending = null
                    }
                }
            }
        }
        return DatHeader(name, version)
    }

    private fun nextToken(input: CharSource, out: StringBuilder): Int {
        out.setLength(0)
        while (true) {
            val c = input.peek()
            if (c < 0) return EOF
            if (!c.toChar().isWhitespace()) break
            input.read()
        }
        return when (val c = input.read().toChar()) {
            '(' -> OPEN
            ')' -> CLOSE
            '"' -> {
                while (true) {
                    val n = input.read()
                    if (n < 0 || n == '"'.code) break
                    out.append(n.toChar())
                }
                WORD
            }
            else -> {
                out.append(c)
                while (true) {
                    val n = input.peek()
                    if (n < 0 || n.toChar().isWhitespace() || n == '('.code || n == ')'.code) break
                    out.append(input.read().toChar())
                }
                WORD
            }
        }
    }

    // ---- shared ---------------------------------------------------------------------------------

    private fun romOf(fields: Map<String, String>): DatRom? {
        val name = fields["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return DatRom(
            name = name,
            size = fields["size"]?.trim()?.toLongOrNull() ?: -1L,
            crc = Checksums.normalize(fields["crc"], 8),
            sha1 = Checksums.normalize(fields["sha1"], 40)
        )
    }

    private class CharSource(private val reader: Reader) {
        private val buffer = CharArray(64 * 1024)
        private var length = 0
        private var position = 0

        fun peek(): Int {
            if (position >= length) {
                length = reader.read(buffer).coerceAtLeast(0)
                position = 0
            }
            return if (position < length) buffer[position].code else -1
        }

        fun read(): Int = peek().also { if (it >= 0) position++ }

        fun skipBomAndSpace() {
            while (true) {
                val c = peek()
                if (c == 0xFEFF || (c >= 0 && c.toChar().isWhitespace())) read() else return
            }
        }
    }
}
