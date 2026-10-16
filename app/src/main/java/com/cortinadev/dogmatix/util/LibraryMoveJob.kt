package com.cortinadev.dogmatix.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

/**
 * One thing to move: the file or folder [source] goes to [targetParent]/[targetName] (into
 * [targetParent] itself when the name is empty), merging with a folder already there.
 * [sourceParent] is null when [source] is the root of its own SAF grant: then only its contents
 * move and the folder itself stays. [consoleId] marks a console's folder. All URIs are strings.
 */
data class MoveItem(
    val source: String,
    val sourceParent: String?,
    val sourceName: String,
    val targetParent: String,
    val targetName: String,
    val consoleId: String? = null,
    /** The console lands in its folder of the library root: it no longer needs one of its own. */
    val clearConsoleDir: Boolean = false
)

/** Settings a finished move switches to. Null / absent = unchanged; "" for a console = no folder of its own. */
data class MoveSettings(val downloadDirectory: String? = null, val consoleDirs: Map<String, String> = emptyMap())

/** A whole move, saved before it starts so an interrupted one can carry on. */
data class MoveJob(
    val id: String = UUID.randomUUID().toString(),
    /** [ActionTypes.LIBRARY_MOVE] or [ActionTypes.SMART_STORAGE]. */
    val type: String,
    val subject: String,
    val items: List<MoveItem>,
    val after: MoveSettings,
    val before: MoveSettings,
    /** The history entry this move reverts (a move back), if any. */
    val undoOf: Long? = null,
    /** Downloads paused for the move, resumed when it ends. */
    val pausedDownloads: List<String> = emptyList(),
    /** The library root before a whole-library move (downloads redirected into it follow). */
    val oldRoot: String? = null
)

/** What the history keeps to undo a move: the move back, valid while [expect] is still what Settings say. */
data class MoveUndo(val job: MoveJob, val expect: MoveSettings)

/**
 * JSON for the saved job and the history's undo data. Written by hand (no reflection): the
 * release build is minified, and both outlive an app update.
 */
object MoveJobCodec {

    fun encodeJob(job: MoveJob): String = jobJson(job).toString()

    fun decodeJob(text: String?): MoveJob? = runCatching { job(JsonParser.parseString(text ?: return null).asJsonObject) }.getOrNull()

    fun encodeUndo(undo: MoveUndo): String = JsonObject().apply {
        add("job", jobJson(undo.job))
        add("expect", settingsJson(undo.expect))
    }.toString()

    fun decodeUndo(text: String?): MoveUndo? = runCatching {
        val root = JsonParser.parseString(text ?: return null).asJsonObject
        MoveUndo(job(root.getAsJsonObject("job"))!!, settings(root.getAsJsonObject("expect")))
    }.getOrNull()

    private fun jobJson(job: MoveJob) = JsonObject().apply {
        addProperty("id", job.id)
        addProperty("type", job.type)
        addProperty("subject", job.subject)
        add("items", JsonArray().apply { job.items.forEach { add(itemJson(it)) } })
        add("after", settingsJson(job.after))
        add("before", settingsJson(job.before))
        job.undoOf?.let { addProperty("undoOf", it) }
        add("paused", JsonArray().apply { job.pausedDownloads.forEach(::add) })
        job.oldRoot?.let { addProperty("oldRoot", it) }
    }

    private fun job(o: JsonObject): MoveJob? {
        val items = o.getAsJsonArray("items")?.mapNotNull { (it as? JsonObject)?.let(::item) }.orEmpty()
        if (items.isEmpty()) return null
        return MoveJob(
            id = o.string("id") ?: return null,
            type = o.string("type") ?: return null,
            subject = o.string("subject").orEmpty(),
            items = items,
            after = settings(o.getAsJsonObject("after")),
            before = settings(o.getAsJsonObject("before")),
            undoOf = o.get("undoOf")?.takeIf { it.isJsonPrimitive }?.asLong,
            pausedDownloads = o.getAsJsonArray("paused")?.mapNotNull { it.stringOrNull() }.orEmpty(),
            oldRoot = o.string("oldRoot")
        )
    }

    private fun itemJson(item: MoveItem) = JsonObject().apply {
        addProperty("source", item.source)
        item.sourceParent?.let { addProperty("sourceParent", it) }
        addProperty("sourceName", item.sourceName)
        addProperty("targetParent", item.targetParent)
        addProperty("targetName", item.targetName)
        item.consoleId?.let { addProperty("consoleId", it) }
        if (item.clearConsoleDir) addProperty("clearConsoleDir", true)
    }

    private fun item(o: JsonObject): MoveItem? = MoveItem(
        source = o.string("source") ?: return null,
        sourceParent = o.string("sourceParent"),
        sourceName = o.string("sourceName").orEmpty(),
        targetParent = o.string("targetParent") ?: return null,
        targetName = o.string("targetName").orEmpty(),
        consoleId = o.string("consoleId"),
        clearConsoleDir = o.get("clearConsoleDir")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    )

    private fun settingsJson(s: MoveSettings) = JsonObject().apply {
        s.downloadDirectory?.let { addProperty("downloadDirectory", it) }
        add("consoleDirs", JsonObject().apply { s.consoleDirs.forEach { (k, v) -> addProperty(k, v) } })
    }

    private fun settings(o: JsonObject?): MoveSettings = MoveSettings(
        downloadDirectory = o?.string("downloadDirectory"),
        consoleDirs = o?.getAsJsonObject("consoleDirs")?.entrySet()?.mapNotNull { (k, v) -> v.stringOrNull()?.let { k to it } }?.toMap().orEmpty()
    )

    private fun JsonObject.string(key: String): String? = get(key)?.stringOrNull()

    private fun JsonElement.stringOrNull(): String? = takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}

object MoveJobs {

    /**
     * True when an item would land on itself or inside itself (document ids of path-based
     * providers): moving a folder there would delete what it just "copied".
     */
    fun landsInside(sourceId: String, targetParentId: String, targetName: String): Boolean {
        val targetId = if (targetName.isEmpty()) targetParentId else DocumentIds.child(targetParentId, targetName) ?: return false
        return DocumentIds.isWithin(sourceId, targetId)
    }

    /**
     * The move back of a finished [job]: each item from where it went ([destinationOf], null when
     * it is not there any more) to where it came from, and the settings as they were before.
     * [applied] is what the move switched the settings to; consoles it gave a folder of their own
     * that had none before get theirs cleared again.
     */
    fun reverse(job: MoveJob, applied: MoveSettings, destinationOf: (MoveItem) -> String?): MoveJob {
        val items = job.items.mapNotNull { item ->
            val now = destinationOf(item) ?: return@mapNotNull null
            MoveItem(
                source = now,
                sourceParent = if (item.targetName.isEmpty()) null else item.targetParent,
                sourceName = item.targetName.ifEmpty { item.sourceName },
                // A grant's root folder only gave its contents: they go back into it.
                targetParent = item.sourceParent ?: item.source,
                targetName = if (item.sourceParent == null) "" else item.sourceName,
                consoleId = item.consoleId
            )
        }
        val restore = MoveSettings(
            downloadDirectory = job.before.downloadDirectory,
            consoleDirs = job.before.consoleDirs + applied.consoleDirs.keys.filter { it !in job.before.consoleDirs }.associateWith { "" }
        )
        return MoveJob(
            type = job.type, subject = job.subject, items = items,
            after = restore, before = applied,
            oldRoot = applied.downloadDirectory
        )
    }
}
