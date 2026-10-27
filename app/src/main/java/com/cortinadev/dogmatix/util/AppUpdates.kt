package com.cortinadev.dogmatix.util

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** The latest published Dogmatix release, with the APK that installs it. */
data class AppRelease(
    /** The tag without its `v`: "1.4". */
    val version: String,
    /** The release title: "Dogmatix 1.4". */
    val name: String,
    /** First line of the release notes, plain text. */
    val summary: String,
    val apkUrl: String,
    val apkName: String,
    /** Bytes; 0 when GitHub does not say. */
    val apkSize: Long,
    /** The release page on GitHub. */
    val pageUrl: String
)

/**
 * Update check against the GitHub releases of the fork. The answer is read by hand (no Gson
 * reflection, so R8 can rename freely) and the release found is kept in DataStore in the same
 * form, so Settings knows about it between checks. Pure, so it is unit-tested.
 */
object AppUpdates {
    const val LATEST_URL = "https://api.github.com/repos/cortinadev/dogmatix/releases/latest"

    /** Automatic checks at most this often: GitHub allows 60 unauthenticated calls an hour per address. */
    const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private const val SUMMARY_MAX = 200

    /** The release in a `releases/latest` answer; null for a draft, a pre-release or one without an APK. */
    fun parseRelease(json: String): AppRelease? = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        if (root.bool("draft") || root.bool("prerelease")) return null
        val tag = root.string("tag_name") ?: return null
        val version = versionOf(tag) ?: return null
        val assets = root.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            ?.mapNotNull { o ->
                val name = o.string("name") ?: return@mapNotNull null
                val url = o.string("browser_download_url") ?: return@mapNotNull null
                Asset(name, url, o.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L)
            }.orEmpty()
        val apk = pickApk(assets) ?: return null
        AppRelease(
            version = version,
            name = root.string("name")?.takeIf { it.isNotBlank() } ?: "Dogmatix $version",
            summary = summaryOf(root.string("body").orEmpty()),
            apkUrl = apk.url,
            apkName = apk.name,
            apkSize = apk.size,
            pageUrl = root.string("html_url").orEmpty()
        )
    }.getOrNull()

    data class Asset(val name: String, val url: String, val size: Long)

    /** The APK to install: never a debug build; one named after the app first. */
    fun pickApk(assets: List<Asset>): Asset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && !it.name.contains("debug", ignoreCase = true) }
        return apks.firstOrNull { it.name.contains("dogmatix", ignoreCase = true) } ?: apks.firstOrNull()
    }

    /** `v1.4` → `1.4`, `1.3-debug` → `1.3`; null when there is no version number in it. */
    fun versionOf(text: String): String? = Regex("""\d+(\.\d+)*""").find(text)?.value

    fun isNewer(version: String, current: String): Boolean {
        val latest = versionOf(version) ?: return false
        val installed = versionOf(current) ?: return true
        return VersionUtils.compareVersions(latest, installed) > 0
    }

    /** What the system read from a downloaded APK; null / 0 where it could not tell. */
    data class ApkFacts(
        val fileSize: Long,
        /** Null when the file is not an APK the system can parse. */
        val packageName: String?,
        val versionName: String?,
        val versionCode: Long,
        /** Signing certificates (any stable encoding); null when unknown. */
        val signers: Set<String>?
    )

    /** The app that is checking for updates. */
    data class InstalledApp(val packageName: String, val versionName: String, val versionCode: Long, val signers: Set<String>?)

    enum class ApkProblem {
        /** Not the size the release announced (truncated or replaced). */
        SIZE,
        /** The system cannot read it as an APK. */
        NOT_AN_APK,
        /** Another app's APK. */
        OTHER_APP,
        /** Not newer than what is installed: there is nothing to update. */
        NOT_NEWER,
        /** Same package, other signing key: Android would refuse it. */
        SIGNATURE
    }

    /**
     * Whether the APK downloaded for an update can install over [installed]; null when it can.
     * It must be the announced size ([expectedSize] 0 = unknown), a Dogmatix APK and newer by
     * name. A debug build downloads the release, which is another package (the same id without
     * `.debug`): there only the name and the version name count. Over the same package, a lower
     * version code (Android refuses a downgrade) or another signing key are refused too.
     */
    fun checkApk(expectedSize: Long, apk: ApkFacts, installed: InstalledApp): ApkProblem? {
        if (expectedSize > 0 && apk.fileSize != expectedSize) return ApkProblem.SIZE
        val pkg = apk.packageName ?: return ApkProblem.NOT_AN_APK
        if (pkg != installed.packageName.removeSuffix(DEBUG_SUFFIX)) return ApkProblem.OTHER_APP
        if (apk.versionName != null && !isNewer(apk.versionName, installed.versionName)) return ApkProblem.NOT_NEWER
        val samePackage = pkg == installed.packageName
        if (samePackage && apk.versionCode > 0 && installed.versionCode > 0 && apk.versionCode < installed.versionCode) return ApkProblem.NOT_NEWER
        if (samePackage && apk.signers != null && installed.signers != null && apk.signers != installed.signers) return ApkProblem.SIGNATURE
        return null
    }

    private const val DEBUG_SUFFIX = ".debug"

    /** An automatic check is due: never checked, the interval has passed, or the clock went back. */
    fun checkDue(now: Long, lastCheckedAt: Long): Boolean =
        lastCheckedAt <= 0L || now < lastCheckedAt || now - lastCheckedAt >= CHECK_INTERVAL_MS

    /** The startup notice: once per version, until the user says "Later" to it. */
    fun shouldPrompt(release: AppRelease?, current: String, dismissedVersion: String): Boolean =
        release != null && isNewer(release.version, current) && release.version != dismissedVersion

    /** The first line of the notes that says something, without Markdown marks. */
    fun summaryOf(body: String): String {
        val line = body.lineSequence()
            .map { it.trim().trimStart('#', '>', '-', '*', ' ').replace("**", "").replace("`", "").trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        return if (line.length <= SUMMARY_MAX) line else line.take(SUMMARY_MAX - 1).trimEnd() + "…"
    }

    fun encode(release: AppRelease): String = JsonObject().apply {
        addProperty("version", release.version)
        addProperty("name", release.name)
        addProperty("summary", release.summary)
        addProperty("apkUrl", release.apkUrl)
        addProperty("apkName", release.apkName)
        addProperty("apkSize", release.apkSize)
        addProperty("pageUrl", release.pageUrl)
    }.toString()

    fun decode(text: String?): AppRelease? = runCatching {
        if (text.isNullOrBlank()) return null
        val o = JsonParser.parseString(text).asJsonObject
        AppRelease(
            version = o.string("version") ?: return null,
            name = o.string("name").orEmpty(),
            summary = o.string("summary").orEmpty(),
            apkUrl = o.string("apkUrl") ?: return null,
            apkName = o.string("apkName").orEmpty(),
            apkSize = o.get("apkSize")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            pageUrl = o.string("pageUrl").orEmpty()
        )
    }.getOrNull()

    private fun JsonObject.string(key: String): String? = get(key)?.stringOrNull()

    private fun JsonObject.bool(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false

    private fun JsonElement.stringOrNull(): String? = takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
