package com.cortinadev.dogmatix.util

/**
 * The app's trash: what is deleted from the library goes to `.dogmatix-trash/<batch>/…` at the
 * root of the SAF tree it lives in (same volume, so it is a rename and undoing it is another),
 * and stays there for [DEFAULT_RETENTION_DAYS] or until emptied by hand.
 */
object TrashRules {
    const val FOLDER = ".dogmatix-trash"

    /**
     * Written into the trash folder: media scanners skip a folder holding `.nomedia`, and ES-DE
     * skips one holding `noload.txt` even when it is set to show hidden folders.
     */
    val MARKERS = listOf(".nomedia", "noload.txt")

    /**
     * Added to the name of a file while it is in the trash, so a frontend that lists by
     * extension and walks into hidden folders still does not offer it as a game.
     */
    const val TRASHED_SUFFIX = ".dogmatix-trashed"

    fun trashedName(name: String): String = if (name.endsWith(TRASHED_SUFFIX)) name else name + TRASHED_SUFFIX

    val RETENTION_CHOICES = listOf(7, 30)
    const val DEFAULT_RETENTION_DAYS = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun retentionOrDefault(days: Int?): Int = days?.takeIf { it in RETENTION_CHOICES } ?: DEFAULT_RETENTION_DAYS

    fun isExpired(trashedAt: Long, now: Long, retentionDays: Int): Boolean = trashedAt <= cutoff(now, retentionDays)

    /** What was trashed at or before this moment has been there [retentionDays] and goes. */
    fun cutoff(now: Long, retentionDays: Int): Long = now - retentionDays * DAY_MS

    /**
     * Folder (relative to the tree root) that receives item number [index] of batch [batchId].
     * It mirrors the item's original folder when its [relativePath] is known and safe, so the
     * trash reads like the library; otherwise each item gets a numbered slot. Two items of one
     * batch never share a slot unless they came from the same folder (where names are unique).
     */
    fun slotPath(batchId: String, relativePath: String, index: Int): String {
        val parent = relativePath.substringBeforeLast('/', "")
        val safeParent = if (parent.isEmpty()) "" else ArchivePaths.safeRelativePath(parent)
        val known = relativePath.isNotBlank() && safeParent != null && safeParent.split('/').none { it == FOLDER }
        return when {
            !known -> "$FOLDER/$batchId/_$index"
            safeParent.isNullOrEmpty() -> "$FOLDER/$batchId"
            else -> "$FOLDER/$batchId/$safeParent"
        }
    }

    /**
     * Library walks skip names starting with a dot: the trash, half-copied `.dogmatix-part`
     * files of a move, and the hidden folders frontends and the system keep among the games.
     */
    fun isHidden(name: String): Boolean = name.startsWith('.')
}
