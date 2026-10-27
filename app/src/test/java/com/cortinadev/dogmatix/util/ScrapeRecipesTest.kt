package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.ScrapeRecipe
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Web pages read with the CSS selectors of a page recipe. */
class ScrapeRecipesTest {

    private val pageUrl = "https://site.example/roms/list?page=1"

    private fun doc(body: String, url: String = pageUrl): Document = Jsoup.parse("<html><body>$body</body></html>", url)

    private fun parse(body: String, recipe: ScrapeRecipe, url: String = pageUrl, extraTags: List<String> = emptyList()) =
        ScrapeRecipes.parsePage(doc(body, url), recipe, "nes", extraTags)

    // ---- Selectors and pages ------------------------------------------------------------------

    @Test
    fun `selectors split at the last at sign`() {
        assertEquals(ScrapeRecipes.Selector("a.rom", "href"), ScrapeRecipes.selector(" a.rom @ href "))
        assertEquals(ScrapeRecipes.Selector("td.title", null), ScrapeRecipes.selector("td.title"))
        assertEquals(ScrapeRecipes.Selector("", "data-url"), ScrapeRecipes.selector("@data-url"))
        assertEquals(ScrapeRecipes.Selector("a[title=x@y]", "title"), ScrapeRecipes.selector("a[title=x@y]@title"))
        assertEquals(ScrapeRecipes.Selector("a", null), ScrapeRecipes.selector("a@"))
    }

    @Test
    fun `numbered pages stop when empty, letters and literals never`() {
        assertEquals(listOf("1" to true, "2" to true, "3" to true), ScrapeRecipes.pageValues("1-3"))
        assertEquals(listOf("A" to false, "B" to false, "C" to false), ScrapeRecipes.pageValues("A-C"))
        assertEquals(listOf("num" to false, "0" to false, "a" to false, "b" to false), ScrapeRecipes.pageValues("num, 0 ,a-b"))
        assertEquals(listOf("3" to true, "2" to true, "1" to true), ScrapeRecipes.pageValues("3-1"))
        assertEquals(listOf("Z" to false, "Y" to false), ScrapeRecipes.pageValues("Z-Y"))
        assertEquals(listOf("1" to true, "2" to true), ScrapeRecipes.pageValues(" 1 - 2 , "))
        assertEquals(ScrapeRecipes.MAX_PAGES, ScrapeRecipes.pageValues("1-100000").size)
        assertEquals(ScrapeRecipes.MAX_PAGES, ScrapeRecipes.pageValues("1-400,401-900,A-Z").size)
        assertTrue(ScrapeRecipes.pageValues("").isEmpty())
    }

    @Test
    fun `plan replaces the placeholder or reads one page`() {
        assertEquals(listOf(PlannedPage("https://s.example/all", false)), ScrapeRecipes.plan("https://s.example/all", "1-5"))
        val implicit = ScrapeRecipes.plan("https://s.example/list?p={page}", " ")
        assertEquals(100, implicit.size)
        assertEquals(PlannedPage("https://s.example/list?p=1", true), implicit.first())
        assertEquals(PlannedPage("https://s.example/list?p=100", true), implicit.last())
        assertEquals(
            listOf(PlannedPage("https://s.example/vault/A", false), PlannedPage("https://s.example/vault/B", false)),
            ScrapeRecipes.plan("https://s.example/vault/%7Bpage%7D", "A-B")
        )
        assertEquals("https://s.example/x?p=2", ScrapeRecipes.plan("https://s.example/x?p=%7bpage%7d", "2").single().url)
        assertTrue(ScrapeRecipes.hasPlaceholder("https://s.example/{page}/"))
    }

    @Test
    fun `validate finds what is wrong with a recipe`() {
        assertEquals(RecipeProblem.NoItem, ScrapeRecipes.validate(ScrapeRecipe(" "), pageUrl))
        assertEquals(RecipeProblem.BadSelector("item", "li["), ScrapeRecipes.validate(ScrapeRecipe("li["), pageUrl))
        assertEquals(RecipeProblem.BadSelector("size", "span[@title"), ScrapeRecipes.validate(ScrapeRecipe("li", size = "span[@title"), pageUrl))
        assertEquals(RecipeProblem.BadSelector("name", "td::@x"), ScrapeRecipes.validate(ScrapeRecipe("li", name = "td::@x"), pageUrl))
        assertNull(ScrapeRecipes.validate(ScrapeRecipe("li", link = "@data-url", name = "img@alt", size = "td:nth-child(3)"), pageUrl))
        val paged = "https://s.example/list?p={page}"
        assertEquals(RecipeProblem.BadPages("1-"), ScrapeRecipes.validate(ScrapeRecipe("li", pages = "1-,A-Z"), paged))
        assertEquals(RecipeProblem.BadPages("A-5"), ScrapeRecipes.validate(ScrapeRecipe("li", pages = "A-5"), paged))
        assertEquals(RecipeProblem.BadPages("a-Z"), ScrapeRecipes.validate(ScrapeRecipe("li", pages = "a-Z"), paged))
        assertNull(ScrapeRecipes.validate(ScrapeRecipe("li", pages = "0,A-Z"), paged))
        // Pages only matter with the placeholder.
        assertNull(ScrapeRecipes.validate(ScrapeRecipe("li", pages = "1-"), pageUrl))
    }

    @Test
    fun `parsePage throws for a recipe validate rejects`() {
        try {
            parse("<ul><li>x</li></ul>", ScrapeRecipe("li", link = "a[href"))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("link"))
        }
    }

    // ---- Rows ---------------------------------------------------------------------------------

    @Test
    fun `a table with a header row, a root-absolute link, a size and tags`() {
        val page = parse(
            """<table class="list"><tr><th>Name</th><th>Size</th></tr>
               <tr><td><a href="/files/Game%20(USA).zip">Game (USA)</a></td><td class="s">12.5 MB</td></tr></table>""",
            ScrapeRecipe("table.list tr", size = "td.s")
        )
        assertEquals(2, page.matched)
        val item = page.items.single()
        assertEquals("https://site.example/files/Game%20(USA).zip", item.file.downloadUrl)
        assertEquals("Game", item.file.name)
        assertEquals(listOf("USA"), item.tags)
        assertEquals("Game%20(USA).zip", item.file.fileName)
        assertEquals(".zip", item.file.fileExtension)
        assertEquals(12_500_000L, item.file.fileSize)
        assertEquals("nes", item.file.consoleId)
        assertEquals("Game (USA)", item.shown)
    }

    @Test
    fun `the element can be the link itself`() {
        val page = parse("""<a class="rom" href="Tetris%20(World).nes">Tetris (World)</a><a href="other.zip">x</a>""", ScrapeRecipe("a.rom"))
        assertEquals("https://site.example/roms/Tetris%20(World).nes", page.items.single().file.downloadUrl)
        assertEquals(".nes", page.items.single().file.fileExtension)
    }

    @Test
    fun `attributes of elements that are not links`() {
        val page = parse(
            """<div class="g" data-url="/get/Zelda%20(Europe).sfc"><img alt="Zelda (Europe)" src="z.png"><span data-bytes="1048576">1 MB</span></div>""",
            ScrapeRecipe("div.g", link = "@data-url", name = "img@alt", size = "span@data-bytes")
        )
        val file = page.items.single().file
        assertEquals("https://site.example/get/Zelda%20(Europe).sfc", file.downloadUrl)
        assertEquals("Zelda", file.name)
        assertEquals(1_048_576L, file.fileSize)
    }

    @Test
    fun `relative links resolve against the page and lose their fragment`() {
        val file = parse("""<ul><li><a href="sub/Game.zip#top">Game</a></li></ul>""", ScrapeRecipe("li")).items.single().file
        assertEquals("https://site.example/roms/sub/Game.zip", file.downloadUrl)
        assertEquals("Game.zip", file.fileName)
    }

    @Test
    fun `base href is respected`() {
        val doc = Jsoup.parse("""<html><head><base href="https://cdn.example/set/"></head><body><ul><li><a href="Game.zip">Game</a></li></ul></body></html>""", pageUrl)
        assertEquals("https://cdn.example/set/Game.zip", ScrapeRecipes.parsePage(doc, ScrapeRecipe("li"), "nes").items.single().file.downloadUrl)
    }

    @Test
    fun `elements without a file link count as matched but give nothing`() {
        val page = parse(
            """<ul><li><a href="folder/">Folder</a></li><li><a href="javascript:void(0)">JS</a></li>
               <li><a href="mailto:a@b.example">Mail</a></li><li><a href="#">Top</a></li><li><a href="?page=1">Self</a></li>
               <li>No link</li><li><a href="ok.zip">OK</a></li></ul>""",
            ScrapeRecipe("li")
        )
        assertEquals(7, page.matched)
        assertEquals(listOf("https://site.example/roms/ok.zip"), page.items.map { it.file.downloadUrl })
    }

    @Test
    fun `links without a file extension take the recipe's one`() {
        val withExt = parse("""<ul><li><a href="/download.php?id=5">Game (USA) (Rev 1)</a></li></ul>""", ScrapeRecipe("li", ext = ".zip")).items.single()
        assertEquals("https://site.example/download.php?id=5", withExt.file.downloadUrl)
        assertEquals(".zip", withExt.file.fileExtension)
        assertEquals("Game%20%28USA%29%20%28Rev%201%29.zip", withExt.file.fileName)
        assertEquals("Game", withExt.file.name)

        val noExt = parse("""<ul><li><a href="/dl/42">Other Game</a></li></ul>""", ScrapeRecipe("li")).items.single()
        assertEquals("", noExt.file.fileExtension)
        assertEquals("Other%20Game", noExt.file.fileName)
    }

    @Test
    fun `two regions without a file extension keep different file names`() {
        val items = parse(
            """<ul><li><a href="/dl/1">Game (USA)</a></li><li><a href="/dl/2">Game (Europe)</a></li></ul>""",
            ScrapeRecipe("li", ext = "zip")
        ).items
        assertEquals(listOf("Game%20%28USA%29.zip", "Game%20%28Europe%29.zip"), items.map { it.file.fileName })
        assertEquals(listOf("Game", "Game"), items.map { it.file.name })
    }

    @Test
    fun `a shown name with the extension loses it`() {
        val item = parse("""<ul><li><a href="/files/Game%20(Japan).zip">Game (Japan).ZIP</a></li></ul>""", ScrapeRecipe("li")).items.single()
        assertEquals("Game (Japan)", item.shown)
        assertEquals(listOf("Japan"), item.tags)
    }

    @Test
    fun `an empty name falls back to the link title, then its leaf`() {
        val titled = parse("""<ul><li><a href="/dl/7" title="Titled (USA)"><img src="x.png"></a></li></ul>""", ScrapeRecipe("li", ext = "zip")).items.single()
        assertEquals("Titled", titled.file.name)
        val leaf = parse("""<ul><li><a href="/files/Leaf%20Game%20(USA).zip"><img src="x.png"></a></li></ul>""", ScrapeRecipe("li")).items.single()
        assertEquals("Leaf Game (USA)", leaf.shown)
        assertEquals("Leaf Game", leaf.file.name)
        // A name selector that finds nothing takes the same way.
        val missing = parse("""<ul><li><a href="/files/Other.zip">x</a></li></ul>""", ScrapeRecipe("li", name = "span.title")).items.single()
        assertEquals("Other", missing.file.name)
    }

    @Test
    fun `sizes in every form`() {
        fun sizeOf(text: String) =
            parse("""<ul><li><a href="a.zip">A</a><span class="s">$text</span></li></ul>""", ScrapeRecipe("li", size = "span.s")).items.single().file.fileSize
        assertEquals((1.5 * 1024 * 1024).toLong(), sizeOf("1.5 MiB"))
        assertEquals(66L * 1024 * 1024, sizeOf("66M"))
        assertEquals(512L, sizeOf("512"))
        assertEquals(12_000_000L, sizeOf("Size: 12 MB"))
        assertEquals(0L, sizeOf("-"))
        assertEquals(0L, parse("""<ul><li><a href="a.zip">A</a></li></ul>""", ScrapeRecipe("li", size = "span.s")).items.single().file.fileSize)
        assertEquals(0L, parse("""<ul><li><a href="a.zip">A</a><span class="s">9 MB</span></li></ul>""", ScrapeRecipe("li")).items.single().file.fileSize)
    }

    @Test
    fun `no element matched`() {
        val page = parse("""<ul><li><a href="a.zip">A</a></li></ul>""", ScrapeRecipe("ul.none li"))
        assertEquals(0, page.matched)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `the extra tag is added once`() {
        val item = parse("""<ul><li><a href="a.zip">Game (USA) (Game)</a></li></ul>""", ScrapeRecipe("li"), extraTags = listOf("Game")).items.single()
        assertEquals(listOf("USA", "Game"), item.tags)
    }

    @Test
    fun `duplicates stay on a page and go across pages`() {
        val recipe = ScrapeRecipe("li")
        val first = parse("""<ul><li><a href="/f/a.zip">A</a></li><li><a href="/f/a.zip">A again</a></li><li><a href="/f/b.zip">B</a></li></ul>""", recipe)
        assertEquals(3, first.items.size)
        val second = parse("""<ul><li><a href="https://site.example/f/b.zip">B</a></li><li><a href="/f/c.zip">C</a></li></ul>""", recipe, url = "https://site.example/roms/list?page=2")
        val all = ScrapeRecipes.dedupe(first.items + second.items)
        assertEquals(listOf("A", "B", "C"), all.map { it.file.name })
    }
}
