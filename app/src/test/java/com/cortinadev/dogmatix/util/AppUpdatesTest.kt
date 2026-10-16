package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {

    private fun releaseJson(
        tag: String = "v1.4",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = """[{"name":"dogmatix-1.4.apk","size":62594034,"browser_download_url":"https://github.com/cortinadev/dogmatix/releases/download/v1.4/dogmatix-1.4.apk"}]""",
        body: String = "Dogmatix 1.4 — faster scans\\n\\n### Library\\n- **Covers**"
    ) = """{"tag_name":"$tag","name":"Dogmatix 1.4","draft":$draft,"prerelease":$prerelease,
        "html_url":"https://github.com/cortinadev/dogmatix/releases/tag/$tag","body":"$body","assets":$assets}"""

    @Test
    fun `reads the release and its apk`() {
        val release = AppUpdates.parseRelease(releaseJson())
        assertNotNull(release)
        release!!
        assertEquals("1.4", release.version)
        assertEquals("Dogmatix 1.4", release.name)
        assertEquals("Dogmatix 1.4 — faster scans", release.summary)
        assertEquals("dogmatix-1.4.apk", release.apkName)
        assertEquals(62594034L, release.apkSize)
        assertTrue(release.apkUrl.endsWith("/v1.4/dogmatix-1.4.apk"))
        assertEquals("https://github.com/cortinadev/dogmatix/releases/tag/v1.4", release.pageUrl)
    }

    @Test
    fun `drafts, pre-releases and releases without an apk are ignored`() {
        assertNull(AppUpdates.parseRelease(releaseJson(draft = true)))
        assertNull(AppUpdates.parseRelease(releaseJson(prerelease = true)))
        assertNull(AppUpdates.parseRelease(releaseJson(assets = """[{"name":"notes.txt","size":3,"browser_download_url":"https://x/notes.txt"}]""")))
        assertNull(AppUpdates.parseRelease(releaseJson(assets = "[]")))
        assertNull(AppUpdates.parseRelease("not json"))
        assertNull(AppUpdates.parseRelease("""{"message":"Not Found"}"""))
    }

    @Test
    fun `the app apk wins over others and debug builds are never picked`() {
        val assets = listOf(
            AppUpdates.Asset("dogmatix-1.4-debug.apk", "d", 1),
            AppUpdates.Asset("other.apk", "o", 1),
            AppUpdates.Asset("Dogmatix-1.4.APK", "r", 1)
        )
        assertEquals("r", AppUpdates.pickApk(assets)?.url)
        assertEquals("o", AppUpdates.pickApk(assets.take(2))?.url)
        assertNull(AppUpdates.pickApk(assets.take(1)))
    }

    @Test
    fun `versions compare by number, whatever surrounds them`() {
        assertEquals("1.3", AppUpdates.versionOf("v1.3"))
        assertEquals("1.3", AppUpdates.versionOf("1.3-debug"))
        assertEquals("1.3.1", AppUpdates.versionOf("v1.3.1-rc1"))
        assertNull(AppUpdates.versionOf("latest"))
        assertTrue(AppUpdates.isNewer("v1.4", "1.3"))
        assertTrue(AppUpdates.isNewer("1.10", "1.9"))
        assertTrue(AppUpdates.isNewer("1.3.1", "1.3-debug"))
        assertFalse(AppUpdates.isNewer("v1.3", "1.3"))
        assertFalse(AppUpdates.isNewer("1.3", "1.3.0"))
        assertFalse(AppUpdates.isNewer("v1.2", "1.3"))
        assertFalse(AppUpdates.isNewer("nightly", "1.3"))
    }

    @Test
    fun `an automatic check runs once every six hours`() {
        val hour = 60 * 60 * 1000L
        assertTrue(AppUpdates.checkDue(now = 10 * hour, lastCheckedAt = 0L))
        assertFalse(AppUpdates.checkDue(now = 10 * hour, lastCheckedAt = 5 * hour))
        assertTrue(AppUpdates.checkDue(now = 11 * hour, lastCheckedAt = 5 * hour))
        // The clock went back: checked "in the future", so check again.
        assertTrue(AppUpdates.checkDue(now = 4 * hour, lastCheckedAt = 5 * hour))
    }

    @Test
    fun `the notice shows once per version until it is put off`() {
        val release = AppUpdates.parseRelease(releaseJson())!!
        assertTrue(AppUpdates.shouldPrompt(release, current = "1.3", dismissedVersion = ""))
        assertTrue(AppUpdates.shouldPrompt(release, current = "1.3", dismissedVersion = "1.3.5"))
        assertFalse(AppUpdates.shouldPrompt(release, current = "1.3", dismissedVersion = "1.4"))
        assertFalse(AppUpdates.shouldPrompt(release, current = "1.4", dismissedVersion = ""))
        assertFalse(AppUpdates.shouldPrompt(null, current = "1.3", dismissedVersion = ""))
    }

    @Test
    fun `the summary is the first line that says something, without markdown`() {
        assertEquals("Library", AppUpdates.summaryOf("\n\n### Library\n- item"))
        assertEquals("Covers: square art", AppUpdates.summaryOf("- **Covers**: square art"))
        assertEquals("", AppUpdates.summaryOf("  \n "))
        val long = AppUpdates.summaryOf("x".repeat(500))
        assertEquals(200, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `a kept release reads back the same`() {
        val release = AppUpdates.parseRelease(releaseJson())!!
        assertEquals(release, AppUpdates.decode(AppUpdates.encode(release)))
        assertNull(AppUpdates.decode(""))
        assertNull(AppUpdates.decode(null))
        assertNull(AppUpdates.decode("{\"name\":\"no version\"}"))
    }
}
