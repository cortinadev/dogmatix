package com.cortinadev.dogmatix.util

/**
 * Path arithmetic on SAF document ids of path-based providers (external storage:
 * `primary:ROMs/snes/Game.sfc`, `6A3B-BB88:ROMS`). The part before `:` is the volume, the rest a
 * path from its root. Ids of other providers are opaque: every function answers "unknown"
 * (null / false) for them instead of guessing.
 */
object DocumentIds {

    /** `primary` / `6A3B-BB88`: the volume of [docId], null for an opaque id. */
    fun volumeOf(docId: String): String? =
        docId.substringBefore(':', "").takeIf { it.isNotEmpty() }

    private fun pathOf(docId: String): String? =
        if (volumeOf(docId) == null) null else docId.substringAfter(':').trim('/')

    /** True when [docId] is [rootId] itself or lies below it (same volume, path prefix by segment). */
    fun isWithin(rootId: String, docId: String): Boolean {
        val volume = volumeOf(rootId) ?: return false
        if (volumeOf(docId) != volume) return false
        val root = pathOf(rootId)!!
        val path = pathOf(docId)!!
        return root.isEmpty() || path == root || path.startsWith("$root/")
    }

    /** `snes/Game.sfc` for `primary:ROMs/snes/Game.sfc` below `primary:ROMs`; "" for the root itself; null when outside. */
    fun relativePath(rootId: String, docId: String): String? {
        if (!isWithin(rootId, docId)) return null
        val root = pathOf(rootId)!!
        val path = pathOf(docId)!!
        return if (root.isEmpty()) path else path.removePrefix(root).trimStart('/')
    }

    /** The folder [docId] sits in; null at the volume root or for an opaque id. */
    fun parentOf(docId: String): String? {
        val path = pathOf(docId) ?: return null
        if (path.isEmpty()) return null
        return "${volumeOf(docId)}:${path.substringBeforeLast('/', "")}"
    }

    /** [relative] (`snes/Game.sfc`) below [rootId]; null for an opaque root. */
    fun child(rootId: String, relative: String): String? {
        val root = pathOf(rootId) ?: return null
        val rel = relative.trim('/')
        val path = listOf(root, rel).filter { it.isNotEmpty() }.joinToString("/")
        return "${volumeOf(rootId)}:$path"
    }

    /** [docId] carried from below [fromRoot] to the same place below [toRoot]; null when it was not below [fromRoot]. */
    fun rebase(docId: String, fromRoot: String, toRoot: String): String? =
        relativePath(fromRoot, docId)?.let { child(toRoot, it) }

    /** Last segment of [docId] (`Game.sfc`), or the id itself when it is opaque. */
    fun nameOf(docId: String): String = pathOf(docId)?.substringAfterLast('/')?.ifEmpty { null } ?: docId
}
