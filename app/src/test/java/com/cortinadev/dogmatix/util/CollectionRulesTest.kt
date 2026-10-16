package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.SourceFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionRulesTest {

    private val rules = CollectionRules(
        consoles = setOf("sony_playstation", "sega_saturn"),
        regions = setOf("Europe"),
        languages = setOf("ES"),
        favouritesOnly = true,
        onDisk = OnDiskRule.MISSING,
        source = SourceFilter.TORRENT,
        minBytes = 1000,
        maxBytes = 5000,
        nameContains = "Final Fantasy"
    )

    @Test
    fun `rules survive a round trip, unknown fields included`() {
        val withExtras = rules.copy(extras = mapOf("genres" to "[\"rpg\"]", "decade" to "1990"))
        assertEquals(withExtras, CollectionRuleCodec.decode(CollectionRuleCodec.encode(withExtras)))
        assertEquals(CollectionRules(), CollectionRuleCodec.decode("{}"))
        assertNull(CollectionRuleCodec.decode("not json"))
        assertNull(CollectionRuleCodec.decode("[1,2]"))
    }

    @Test
    fun `malformed fields fall back to no rule`() {
        val decoded = CollectionRuleCodec.decode("""{"consoles":"psx","onDisk":"SOMETIMES","minBytes":-5,"favourites":"yes"}""")!!
        assertTrue(decoded.consoles.isEmpty())
        assertEquals(OnDiskRule.ANY, decoded.onDisk)
        assertNull(decoded.minBytes)
        assertFalse(decoded.favouritesOnly)
    }

    @Test
    fun `emptiness`() {
        assertTrue(CollectionRules().isEmpty)
        assertFalse(CollectionRules(onDisk = OnDiskRule.OWNED).isEmpty)
        assertFalse(CollectionRules(extras = mapOf("decade" to "1990")).isEmpty)
    }

    @Test
    fun `sql ands the kinds and ors the values, with arguments in order`() {
        val sql = CollectionRuleSql.where(rules)
        assertEquals(
            "df.consoleId IN (?,?) AND " +
                "EXISTS (SELECT 1 FROM downloadable_file_tags t WHERE t.fileId = df.id AND t.tag IN (?)) AND " +
                "EXISTS (SELECT 1 FROM downloadable_file_tags t WHERE t.fileId = df.id AND t.tag IN (?)) AND " +
                "EXISTS (SELECT 1 FROM favourites f WHERE f.consoleId = df.consoleId AND f.fileName = df.fileName) AND " +
                "df.torrentFileIndex IS NOT NULL AND df.fileSize >= ? AND df.fileSize <= ? AND df.searchKey LIKE ?",
            sql.sql
        )
        assertEquals(listOf("sega_saturn", "sony_playstation", "Europe", "ES", 1000L, 5000L, "%fina%fantas%"), sql.args)
    }

    @Test
    fun `no rule is always true and extensions add their fields`() {
        assertEquals(SqlCondition("1"), CollectionRuleSql.where(CollectionRules()))
        val decade = object : CollectionRuleExtension {
            override val key = "decade"
            override fun condition(rawJson: String) = SqlCondition("df.year BETWEEN ? AND ?", listOf(rawJson.toInt(), rawJson.toInt() + 9))
        }
        val sql = CollectionRuleSql.where(CollectionRules(extras = mapOf("decade" to "1990", "other" to "1")), listOf(decade))
        assertEquals("(df.year BETWEEN ? AND ?)", sql.sql)
        assertEquals(listOf(1990, 1999), sql.args)
    }

    @Test
    fun `match pages are keyset paged`() {
        val page = CollectionRuleSql.matchPage(CollectionRules(consoles = setOf("nes")), emptyList(), afterId = 42, limit = 500)
        assertTrue(page.sql.contains("WHERE df.id > ? AND (df.consoleId IN (?)) ORDER BY df.id LIMIT ?"))
        assertEquals(listOf(42L, "nes", 500), page.args)
    }

    @Test
    fun `game keys`() {
        assertEquals("sony_playstation|final fantasy vii", CollectionKeys.gameKey("sony_playstation", "final fantasy vii"))
    }
}
