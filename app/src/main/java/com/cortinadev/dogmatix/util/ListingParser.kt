package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * The files of a downloaded web directory listing, in page order:
 *  - an HTML table, one file per row (Myrient, Apache with `HTMLTable`, h5ai, lighttpd, Caddy…);
 *  - else a `<pre>` block of links, one file per line (nginx `autoindex`, Apache `FancyIndexing`
 *    without `HTMLTable`, IIS), the size read from the line's text.
 * Folders, the parent folder, sort links and links that are not files of the listing are left
 * out ([FileParsingUtils.listingFileName]). Pure: the page is already downloaded.
 */
object ListingParser {

    /** [tags] parallel to [files], each with the content type of the source at the end. */
    class Parsed(val files: List<DownloadableFileEntity>, val tags: List<List<String>>)

    /** Link texts of a parent folder whose link is not a plain `../` (Myrient). */
    private val PARENT_TEXTS = setOf("Parent directory/", "./", "../")

    private val WHITESPACE = Regex("\\s+")

    /** A unit written apart from its number (`1.2 MB`): joined back before reading the size. */
    private val UNIT_TOKEN = Regex("(?:[KMGTPE](?:i?B)?|B)", RegexOption.IGNORE_CASE)

    /**
     * [baseUrl]: the source's own address (relative links resolve against it); [pageUrl]: the
     * address that served the page (a mirror, or the same). [contentTypeTag] ends every file's tags.
     * A page with a table or a `<pre>` of links but no files gives an empty result (the caller decides
     * whether to trust it).
     * @throws NoListingException when the page has neither: an error page, a login, JSON or plain text.
     */
    fun parse(doc: Document, baseUrl: String, pageUrl: String, consoleId: String, contentTypeTag: String): Parsed {
        val tables = doc.select("table")
        for (table in tables) {
            val parsed = parseTable(table, baseUrl, pageUrl, consoleId, contentTypeTag)
            if (parsed.files.isNotEmpty()) return parsed
        }
        val pres = doc.select("pre").filter { it.selectFirst("a[href]") != null }
        for (pre in pres) {
            val parsed = parsePre(pre, baseUrl, pageUrl, consoleId, contentTypeTag)
            if (parsed.files.isNotEmpty()) return parsed
        }
        if (tables.isNotEmpty() || pres.isNotEmpty()) return Parsed(emptyList(), emptyList())
        throw NoListingException(pageUrl)
    }

    private fun parseTable(table: Element, baseUrl: String, pageUrl: String, consoleId: String, contentTypeTag: String): Parsed {
        val files = ArrayList<DownloadableFileEntity>()
        val tags = ArrayList<List<String>>()
        for (row in table.select("tr")) {
            val link = row.select("td.link a").first() ?: row.select("a").first() ?: continue
            if (link.text().trim() in PARENT_TEXTS) continue
            val (file, fileTags) = FileParsingUtils.fileFromLink(link, FileParsingUtils.sizeCellOf(row)?.text(), baseUrl, pageUrl, consoleId) ?: continue
            files += file
            tags += fileTags + contentTypeTag
        }
        return Parsed(files, tags)
    }

    /** One line of a `<pre>` listing: its links and the text around them (date, size…). */
    private class Line {
        val links = ArrayList<Element>()
        val text = StringBuilder()
    }

    private fun parsePre(pre: Element, baseUrl: String, pageUrl: String, consoleId: String, contentTypeTag: String): Parsed {
        val files = ArrayList<DownloadableFileEntity>()
        val tags = ArrayList<List<String>>()
        for (line in linesOf(pre)) {
            val candidates = line.links.filter { FileParsingUtils.listingFileName(it.attr("href")) != null && it.text().trim() !in PARENT_TEXTS }
            // An icon linked to the file comes first on some servers: the named link is the one.
            val link = candidates.firstOrNull { it.text().isNotBlank() } ?: candidates.firstOrNull() ?: continue
            val (file, fileTags) = FileParsingUtils.fileFromLink(link, sizeIn(line.text.toString()), baseUrl, pageUrl, consoleId) ?: continue
            files += file
            tags += fileTags + contentTypeTag
        }
        return Parsed(files, tags)
    }

    /** The lines of a `<pre>`: split at line breaks in its text and at `<br>` / `<hr>` (IIS). */
    private fun linesOf(pre: Element): List<Line> {
        val lines = ArrayList<Line>()
        var line = Line()
        fun endLine() {
            if (line.links.isNotEmpty()) lines += line
            line = Line()
        }
        fun walk(node: Node) {
            when (node) {
                is TextNode -> node.wholeText.split('\n').forEachIndexed { i, part ->
                    if (i > 0) endLine()
                    line.text.append(part).append(' ')
                }
                is Element -> when (node.normalName()) {
                    // A link's text is the name, not part of the line's columns.
                    "a" -> line.links.add(node)
                    "br", "hr" -> endLine()
                    else -> node.childNodes().forEach(::walk)
                }
            }
        }
        pre.childNodes().forEach(::walk)
        endLine()
        return lines
    }

    /** The last thing on the line that reads as a size (`1234567`, `66M`, `1.2 GiB`); dates and times do not. */
    private fun sizeIn(text: String): String? {
        val tokens = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        var size: String? = null
        var i = 0
        while (i < tokens.size) {
            val joined = if (i + 1 < tokens.size && UNIT_TOKEN.matches(tokens[i + 1])) tokens[i] + " " + tokens[i + 1] else null
            when {
                joined != null && FileParsingUtils.SIZE_CELL_TEXT.matches(joined) -> { size = joined; i += 2 }
                FileParsingUtils.SIZE_CELL_TEXT.matches(tokens[i]) -> { size = tokens[i]; i++ }
                else -> i++
            }
        }
        return size
    }
}
