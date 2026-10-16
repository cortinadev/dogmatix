package com.cortinadev.dogmatix.util

import java.util.Locale

/**
 * Where each archive entry lands when extracted: its own relative path, so multi-disc sets
 * (`Disc 1/track01.bin`, `Disc 2/track01.bin`) and games laid out in folders keep their
 * structure instead of being flattened onto each other.
 */
object ArchivePaths {
    /** Characters SAF / FAT storage rejects in a name; replaced, not dropped, so names stay recognisable. */
    private val INVALID_CHARS = Regex("[<>:\"|?*\\u0000-\\u001F\\u007F]")
    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")

    /**
     * [raw] (the entry's path in the archive) as a safe relative path with `/` separators, or null
     * for one that would escape the destination: absolute paths, drive letters and `..` segments.
     * Empty and `.` segments are dropped; trailing dots and spaces are trimmed (FAT drops them).
     */
    fun safeRelativePath(raw: String): String? {
        val path = raw.replace('\\', '/')
        if (path.startsWith('/') || DRIVE_PREFIX.containsMatchIn(path)) return null
        val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." }) return null
        val clean = segments.map { it.replace(INVALID_CHARS, "_").trimEnd('.', ' ') }
        if (clean.any { it.isEmpty() }) return null
        return clean.joinToString("/")
    }

    /**
     * Hands out destination paths for the entries of one archive, keeping two entries from landing
     * on the same file. Storage may compare names without case (FAT, exFAT on SD cards), so
     * `Game.bin` and `game.BIN` collide: the later one becomes `game (2).BIN`.
     */
    class Allocator {
        private val taken = HashSet<String>()

        fun allocate(path: String): String {
            if (taken.add(key(path))) return path
            val dir = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            val stem = name.substringBeforeLast('.', name)
            val ext = name.removePrefix(stem)
            var n = 2
            while (true) {
                val candidate = (if (dir.isEmpty()) "" else "$dir/") + "$stem ($n)$ext"
                if (taken.add(key(candidate))) return candidate
                n++
            }
        }

        private fun key(path: String) = path.lowercase(Locale.ROOT)
    }
}
