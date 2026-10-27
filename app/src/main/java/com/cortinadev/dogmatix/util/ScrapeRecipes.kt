package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.ScrapeRecipe
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.QueryParser
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** One file found on a page: its row, its tags and its name as the page shows it (for "Test"). */
data class ScrapedItem(val file: DownloadableFileEntity, val tags: List<String>, val shown: String = file.name)

/** What one page gave: [matched] = elements the item selector found, with or without a file link. */
data class ScrapedPage(val matched: Int, val items: List<ScrapedItem>)

/** A page to read; [stopsWhenEmpty] = a numbered page, past the end once one brings nothing new. */
data class PlannedPage(val url: String, val stopsWhenEmpty: Boolean)

/** Why a recipe cannot be used as it is. */
sealed class RecipeProblem {
    data object NoItem : RecipeProblem()
    /** [field]: item, link, name or size. */
    data class BadSelector(val field: String, val css: String) : RecipeProblem()
    data class BadPages(val spec: String) : RecipeProblem()
}

/**
 * Reads the games of a web page that is not a folder listing, with the CSS selectors of a
 * [ScrapeRecipe] (one level: each element links straight to its file).
 *  - Selectors are `css@attr`, split at the last `@`: no `@` = the text, no css = the element itself.
 *  - Links resolve against the page that was downloaded (`<base href>` included), without fragment;
 *    elements without an http(s) link to a file (folders, `javascript:`, `mailto:`, `#`) are
 *    counted in [ScrapedPage.matched] but give no file.
 *  - `{page}` in the URL is replaced by each value of [ScrapeRecipe.pages] in turn.
 */
object ScrapeRecipes {
    const val PAGE_PLACEHOLDER = "{page}"
    private const val PAGE_PLACEHOLDER_ENCODED = "%7Bpage%7D"
    const val DEFAULT_NUMERIC_PAGES = "1-100"
    const val MAX_PAGES = 500

    /** Leaves that name a script, not a file: the recipe's extension applies to them. */
    private val SCRIPT_EXTENSIONS = setOf("php", "asp", "aspx", "html", "htm", "cgi", "jsp")
    private val REAL_EXTENSION = Regex("[A-Za-z0-9]{1,8}")
    private val NUMBER_RANGE = Regex("""(\d+)\s*-\s*(\d+)""")
    private val LETTER_RANGE = Regex("""([A-Za-z])\s*-\s*([A-Za-z])""")
    private val LITERAL = Regex("""[A-Za-z0-9_.~%]+""")

    data class Selector(val css: String, val attr: String?)

    fun selector(spec: String): Selector {
        val at = spec.lastIndexOf('@')
        if (at < 0) return Selector(spec.trim(), null)
        return Selector(spec.substring(0, at).trim(), spec.substring(at + 1).trim().ifEmpty { null })
    }

    fun hasPlaceholder(url: String): Boolean =
        url.contains(PAGE_PLACEHOLDER) || url.contains(PAGE_PLACEHOLDER_ENCODED, ignoreCase = true)

    /**
     * The values of a pages spec, in order, each with whether an empty page ends the run: number
     * ranges do ("1-50", also "50-1"), letter ranges ("A-Z") and literals ("num", "0") never.
     * Malformed tokens are left out (see [validate]); at most [MAX_PAGES] values.
     */
    fun pageValues(pages: String): List<Pair<String, Boolean>> =
        tokens(pages).flatMap { token ->
            NUMBER_RANGE.matchEntire(token)?.let { m ->
                val from = m.groupValues[1].toLongOrNull() ?: return@flatMap emptySequence()
                val to = m.groupValues[2].toLongOrNull() ?: return@flatMap emptySequence()
                val step = if (to >= from) 1L else -1L
                return@flatMap generateSequence(from) { it + step }.take((kotlin.math.abs(to - from) + 1).coerceAtMost(MAX_PAGES.toLong()).toInt())
                    .map { it.toString() to true }
            }
            letterRange(token)?.let { (from, to) ->
                val chars = if (to >= from) (from..to) else (from downTo to)
                return@flatMap chars.asSequence().map { it.toString() to false }
            }
            if (LITERAL.matches(token)) sequenceOf(token to false) else emptySequence()
        }.take(MAX_PAGES).toList()

    /** The pages of a source: one without `{page}` is a single page; a blank spec means [DEFAULT_NUMERIC_PAGES]. */
    fun plan(url: String, pages: String): List<PlannedPage> {
        if (!hasPlaceholder(url)) return listOf(PlannedPage(url, stopsWhenEmpty = false))
        return pageValues(pages.ifBlank { DEFAULT_NUMERIC_PAGES }).map { (value, stops) ->
            PlannedPage(url.replace(PAGE_PLACEHOLDER, value).replace(PAGE_PLACEHOLDER_ENCODED, value, ignoreCase = true), stops)
        }
    }

    /** The first thing wrong with [recipe] for a source at [url], or null when it can be used. */
    fun validate(recipe: ScrapeRecipe, url: String): RecipeProblem? {
        if (recipe.item.isBlank()) return RecipeProblem.NoItem
        if (!parses(recipe.item.trim())) return RecipeProblem.BadSelector("item", recipe.item.trim())
        listOf("link" to recipe.link, "name" to recipe.name, "size" to recipe.size).forEach { (field, spec) ->
            val css = selector(spec).css
            if (css.isNotEmpty() && !parses(css)) return RecipeProblem.BadSelector(field, spec.trim())
        }
        if (hasPlaceholder(url) && recipe.pages.isNotBlank()) {
            tokens(recipe.pages).firstOrNull { token ->
                !NUMBER_RANGE.matches(token) && letterRange(token) == null && !LITERAL.matches(token)
            }?.let { return RecipeProblem.BadPages(it) }
            if (pageValues(recipe.pages).isEmpty()) return RecipeProblem.BadPages(recipe.pages.trim())
        }
        return null
    }

    /** [problem] in words, for logs and the scan report. */
    fun describe(problem: RecipeProblem): String = when (problem) {
        RecipeProblem.NoItem -> "no item selector"
        is RecipeProblem.BadSelector -> "invalid ${problem.field} selector '${problem.css}'"
        is RecipeProblem.BadPages -> "invalid pages '${problem.spec}'"
    }

    /**
     * The files of one page. Throws [IllegalArgumentException] for a recipe [validate] rejects.
     * Duplicates are kept (the scan drops them across pages with [dedupe]); [extraTags] (the
     * content type) are added once to every file.
     */
    fun parsePage(doc: Document, recipe: ScrapeRecipe, consoleId: String, extraTags: List<String> = emptyList()): ScrapedPage {
        validate(recipe, "")?.let { throw IllegalArgumentException("Recipe: ${describe(it)}") }
        val page = doc.location().substringBefore('#')
        val elements = doc.select(recipe.item.trim())
        val items = elements.mapNotNull { element -> itemOf(element, recipe, consoleId, page, extraTags) }
        return ScrapedPage(elements.size, items)
    }

    /** The first file of each address, in page order. */
    fun dedupe(items: List<ScrapedItem>): List<ScrapedItem> = items.distinctBy { it.file.downloadUrl }

    private fun itemOf(element: Element, recipe: ScrapeRecipe, consoleId: String, page: String, extraTags: List<String>): ScrapedItem? {
        val (linkElement, rawUrl) = linkOf(element, recipe.link) ?: return null
        // A raw space would go out as it is in the request line.
        val url = rawUrl.substringBefore('#').replace(" ", "%20")
        val path = url.substringBefore('?')
        if (!MirrorUrls.isWeb(url) || path.endsWith("/") || url == page) return null

        val leaf = path.substringAfterLast('/')
        val leafExt = leaf.substringAfterLast('.', "")
        val realExt = leafExt.takeIf { REAL_EXTENSION.matches(it) && it.lowercase() !in SCRIPT_EXTENSIONS }
        val extension = realExt?.let { ".$it" } ?: recipe.ext.trim().removePrefix(".").let { if (it.isEmpty()) "" else ".$it" }
        val leafName = decode(leaf).let { if (REAL_EXTENSION.matches(leafExt)) it.substringBeforeLast('.') else it }.trim()

        val given = if (recipe.name.isBlank()) linkElement.text().trim() else valueOf(element, recipe.name)
        val shown = given.ifEmpty { linkElement.attr("title").trim() }.ifEmpty { leafName }
            .let { if (extension.isNotEmpty() && it.endsWith(extension, ignoreCase = true)) it.dropLast(extension.length).trim() else it }
            .ifEmpty { return null }

        val (cleanName, tags) = FileParsingUtils.extractNameAndTags(shown)
        // The name on disk and the key of the row: the shown name with its tags, so that
        // "Game (USA)" and "Game (Europe)" do not both become Game.zip.
        val fileName = if (realExt != null) leaf
                       else URLEncoder.encode(FileParsingUtils.sanitizeFolderName(shown) + extension, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val size = if (recipe.size.isBlank()) 0L else FileSizeUtils.parseFileSize(valueOf(element, recipe.size))
        val file = DownloadableFileEntity(
            name = cleanName.ifEmpty { shown },
            fileName = fileName,
            consoleId = consoleId,
            downloadUrl = url,
            fileSize = size,
            fileExtension = extension
        )
        return ScrapedItem(file, (tags + extraTags).distinct(), shown)
    }

    /** The link element of [element] and its resolved address (empty when it does not resolve). */
    private fun linkOf(element: Element, spec: String): Pair<Element, String>? {
        val sel = selector(spec)
        val attr = sel.attr ?: "href"
        val target = when {
            spec.isBlank() -> if (element.normalName() == "a" && element.hasAttr("href")) element else element.selectFirst("a[href]")
            sel.css.isNotEmpty() -> element.selectFirst(sel.css)
            else -> element
        } ?: return null
        val raw = target.attr(attr).trim()
        if (raw.isEmpty() || raw.startsWith("#")) return null
        return target to target.absUrl(attr)
    }

    private fun valueOf(element: Element, spec: String): String {
        val sel = selector(spec)
        val target = if (sel.css.isEmpty()) element else element.selectFirst(sel.css) ?: return ""
        return (if (sel.attr == null) target.text() else target.attr(sel.attr)).trim()
    }

    private fun parses(css: String): Boolean = try {
        QueryParser.parse(css)
        true
    } catch (_: IllegalStateException) {
        // Selector.SelectorParseException
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun tokens(pages: String): Sequence<String> =
        pages.split(',').asSequence().map { it.trim() }.filter { it.isNotEmpty() }

    private fun letterRange(token: String): Pair<Char, Char>? {
        val m = LETTER_RANGE.matchEntire(token) ?: return null
        val from = m.groupValues[1][0]
        val to = m.groupValues[2][0]
        return if (from.isUpperCase() == to.isUpperCase()) from to to else null
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}
