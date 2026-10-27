package com.cortinadev.dogmatix.util

import java.io.IOException

/**
 * Why a storage operation could not be done, when that can be told: the volume holding the
 * folder is gone (SD card out, unmounted, failing), the app lost its grant to the folder (revoked,
 * or the folder was deleted or moved away), or the volume is full. Ordered worst first.
 */
enum class StorageProblem { VOLUME_GONE, PERMISSION_LOST, NO_SPACE }

/** Thrown by storage code that already knows the [problem]. */
open class StorageProblemException(val problem: StorageProblem, message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reads a [StorageProblem] out of what SAF, the providers and the kernel throw (their messages,
 * since the types say little across the binder), and out of the state of a tree. Pure JVM.
 */
object StorageProblems {

    private val noSpace = Regex("""ENOSPC|EDQUOT|No space left|Disk quota exceeded""", RegexOption.IGNORE_CASE)

    /**
     * The volume under a tree is gone or failing: ExternalStorageProvider's "No root for <volume>",
     * its "Failed to determine if <doc> is child of <tree>" when a tree cannot be resolved any more,
     * and the errnos of a card pulled out or remounted read-only.
     */
    private val volumeGone = Regex(
        """No root for|is child of|ENOTCONN|ENODEV|ENXIO|EROFS|\bEIO\b|I/O error|Transport endpoint is not connected|Read-only file system|not mounted|unmounted""",
        RegexOption.IGNORE_CASE
    )

    /** SAF's "Permission Denial … requires that you obtain access using ACTION_OPEN_DOCUMENT", and EACCES / EPERM. */
    private val denied = Regex("""EACCES|EPERM|Permission denied|Permission Denial|requires that you obtain access""", RegexOption.IGNORE_CASE)

    /** The problem behind [error] or one of its causes; null when nothing in it says. */
    fun classify(error: Throwable): StorageProblem? {
        var current: Throwable? = error
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            problemOf(current)?.let { return it }
            current = current.cause
        }
        return null
    }

    /** [error] itself, without its causes. */
    fun problemOf(error: Throwable): StorageProblem? {
        if (error is StorageProblemException) return error.problem
        val message = error.message.orEmpty()
        return when {
            noSpace.containsMatchIn(message) -> StorageProblem.NO_SPACE
            volumeGone.containsMatchIn(message) -> StorageProblem.VOLUME_GONE
            error is SecurityException || denied.containsMatchIn(message) -> StorageProblem.PERMISSION_LOST
            else -> null
        }
    }

    /**
     * The state of a folder the app writes to, from what can be asked before touching it.
     * [granted]: the app still holds the read/write grant of its tree. [volumeMounted]: the volume
     * is mounted for writing (null = cannot be told, a provider that is not external storage).
     * [reachable]: the provider answers for the folder. [writable]: it accepts new files there.
     */
    fun ofTree(granted: Boolean, volumeMounted: Boolean?, reachable: Boolean, writable: Boolean): StorageProblem? = when {
        volumeMounted == false -> StorageProblem.VOLUME_GONE
        !granted -> StorageProblem.PERMISSION_LOST
        // On a mounted volume an unreachable folder was deleted or moved: it has to be picked again.
        !reachable -> if (volumeMounted == true) StorageProblem.PERMISSION_LOST else StorageProblem.VOLUME_GONE
        !writable -> StorageProblem.PERMISSION_LOST
        else -> null
    }

    /** The worst of [problems] (what a batch of operations reports); null when none. */
    fun worst(problems: Iterable<StorageProblem?>): StorageProblem? = problems.filterNotNull().minByOrNull { it.ordinal }
}
