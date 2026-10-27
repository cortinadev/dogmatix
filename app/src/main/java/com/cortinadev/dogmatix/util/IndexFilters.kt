package com.cortinadev.dogmatix.util

/** What the indexers (torrents, Internet Archive items) leave out of the library. Pure. */
object IndexFilters {

    /** Extensions (with the dot, lowercase) of files that are never games: metadata, checksums, images. */
    val BLOCKED_EXTENSIONS = setOf(
        // Metadata / database
        ".xml", ".sqlite", ".nfo", ".txt", ".pdf", ".log", ".html", ".htm",
        // Checksums / verification
        ".sfv", ".md5", ".sha1", ".sha256",
        // Images
        ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp",
        // System / hidden
        ".ds_store"
    )

    fun isBlocked(fileName: String): Boolean =
        ".${fileName.substringAfterLast('.', "")}".lowercase() in BLOCKED_EXTENSIONS

    /** [path] is under one of [folders] (as a whole path segment, any case); no folders = everything. */
    fun inFolders(path: String, folders: List<String>): Boolean {
        val wanted = folders.map { it.trim().replace('\\', '/').lowercase().trim('/') }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return true
        val normalized = path.replace('\\', '/').lowercase()
        return wanted.any { folder -> normalized.startsWith("$folder/") || normalized.contains("/$folder/") }
    }
}
