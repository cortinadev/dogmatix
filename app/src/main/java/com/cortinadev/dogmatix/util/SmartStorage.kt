package com.cortinadev.dogmatix.util

/**
 * Smart storage: the library root plus a second root (normally on the SD card), and for each
 * console which of the two holds its folder. It works per console, never per game: frontends
 * read one folder per system, so a console split over two volumes would hide half of it.
 * The decisions are here so they can be tested; free space is passed in (null = unknown).
 */
object SmartStorage {
    private const val MB = 1024L * 1024
    private const val GB = 1024L * MB

    /** Room left free on a volume besides what is moved or downloaded onto it. */
    const val RESERVE_BYTES = 512L * MB

    /** The internal volume counts as tight below this share of its size free… */
    const val TIGHT_FRACTION = 0.15
    /** …or below this much, whichever is bigger. */
    const val TIGHT_MIN_BYTES = 4L * GB

    /** Consoles smaller than this are never suggested: moving them frees next to nothing. */
    const val MIN_SUGGESTED_BYTES = 256L * MB

    enum class Place { MAIN, SECOND }

    /** One console with a folder on disk: where it is and how much it holds. */
    data class Console(val id: String, val place: Place, val bytes: Long)

    data class Suggestion(val consoleId: String, val to: Place, val bytes: Long)

    fun other(place: Place): Place = if (place == Place.MAIN) Place.SECOND else Place.MAIN

    /** True/false when the free space is known; unknown space refuses nothing. */
    fun fits(bytes: Long, free: Long?): Boolean = free == null || free - RESERVE_BYTES >= bytes

    /** Free space [from] should get back to: [TIGHT_FRACTION] of its size, at least [TIGHT_MIN_BYTES]. */
    fun targetFree(total: Long?): Long =
        maxOf(TIGHT_MIN_BYTES, total?.let { (it * TIGHT_FRACTION).toLong() } ?: 0L)

    /**
     * Consoles to move off [from] (the volume on internal storage) while it is tight: the biggest
     * first, each only if it fits on the other volume (counting the ones before it), until [from]
     * is back above [targetFree]. Nothing is suggested while there is room, or when the free
     * space of either side is unknown.
     */
    fun suggest(consoles: List<Console>, from: Place, fromFree: Long?, fromTotal: Long?, toFree: Long?): List<Suggestion> {
        if (fromFree == null || toFree == null) return emptyList()
        val target = targetFree(fromTotal)
        if (fromFree >= target) return emptyList()
        var freed = 0L
        var room = toFree
        val out = ArrayList<Suggestion>()
        for (c in consoles.filter { it.place == from && it.bytes >= MIN_SUGGESTED_BYTES }.sortedWith(compareByDescending<Console> { it.bytes }.thenBy { it.id })) {
            if (fromFree + freed >= target) break
            if (!fits(c.bytes, room)) continue
            out += Suggestion(c.id, other(from), c.bytes)
            freed += c.bytes
            room -= c.bytes
        }
        return out
    }

    /**
     * Where a download of [bytes] goes: the console's own volume, unless it has no room for the
     * file and the other one does (then the file lands in the same console folder over there).
     * Unknown sizes or free space never move a download.
     */
    fun downloadPlace(bytes: Long, own: Place, ownFree: Long?, otherFree: Long?): Place = when {
        bytes <= 0 || ownFree == null || otherFree == null -> own
        fits(bytes, ownFree) -> own
        fits(bytes, otherFree) -> other(own)
        else -> own
    }
}

/**
 * Moving library folders (the whole root, chosen consoles, or one console for smart storage):
 * each file is copied under a temporary name, checked by size and sampled bytes ([CopyCheck]),
 * renamed, and only then is the original deleted. A rerun after an interruption carries on. Pure JVM.
 */
object LibraryMoveRules {
    private const val MB = 1024L * 1024

    /** Suffix of a copy in progress; a leftover one is overwritten on the next run. */
    const val PART_SUFFIX = ".dogmatix-part"

    /** Room to leave free on the target besides the games themselves. */
    const val RESERVE_BYTES = 256L * MB

    fun partName(name: String): String = name + PART_SUFFIX

    fun isPart(name: String): Boolean = name.endsWith(PART_SUFFIX)

    /** The original goes only once its copy is complete (same size). */
    fun mayDeleteSource(sourceSize: Long, copySize: Long?): Boolean = sourceSize >= 0 && copySize == sourceSize

    /** What to do when the target already holds a file of that name (copied earlier, or there before). */
    enum class Existing { SAME, CONFLICT }

    /**
     * Same size counts as the same file: a rerun finds what an interrupted one had already
     * renamed into place, and a ROM of the same name and size in a merged folder is the same
     * game. Anything else is a conflict: both are kept where they are.
     */
    fun existing(sourceSize: Long, targetSize: Long?): Existing =
        if (sourceSize >= 0 && targetSize == sourceSize) Existing.SAME else Existing.CONFLICT

    /** What one file of a move does. */
    enum class FileStep {
        /** A `.dogmatix-part` an interrupted run left in the source: deleted, never moved. */
        DROP_PART,
        /** Nothing of its name in the target: moved (or copied, checked and renamed; a stale part of it there is replaced). */
        MOVE,
        /** The same file is in the target (an earlier run renamed it into place, then stopped): the original goes. */
        ALREADY_THERE,
        /** Something else of its name is in the target: both stay where they are. */
        CONFLICT
    }

    /**
     * The step for the file [sourceName] ([sourceSize] bytes) when the target holds [existingSize]
     * under its name (null: nothing; -1: a folder). [sameBytes]: the sampled blocks of both match
     * ([CopyCheck.verdict] is SAME); only asked when the sizes agree.
     */
    fun fileStep(sourceName: String, sourceSize: Long, existingSize: Long?, sameBytes: () -> Boolean): FileStep = when {
        isPart(sourceName) -> FileStep.DROP_PART
        existingSize == null -> FileStep.MOVE
        existing(sourceSize, existingSize) == Existing.SAME && sameBytes() -> FileStep.ALREADY_THERE
        else -> FileStep.CONFLICT
    }

    /** Parts in the target ([targetNames]) a rerun replaces before copying [name] again. */
    fun staleParts(targetNames: Collection<String>, name: String): List<String> = targetNames.filter { it == partName(name) }

    /** True/false when the free space is known; unknown space refuses nothing. */
    fun fits(totalBytes: Long, freeBytes: Long?): Boolean = freeBytes == null || freeBytes - RESERVE_BYTES >= totalBytes

    /** A folder cannot move into itself or one of its own subfolders (and the reverse). Document ids. */
    fun overlaps(sourceId: String, targetId: String): Boolean =
        DocumentIds.isWithin(sourceId, targetId) || DocumentIds.isWithin(targetId, sourceId)
}
