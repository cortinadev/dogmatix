package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import com.cortinadev.dogmatix.util.StorageHelper
import java.io.IOException

/**
 * Reads the frontends' folders (ES-DE, iiSU, the console folders) through SAF with one provider
 * query per level ([StorageHelper.listChildren]). `DocumentFile.findFile` asks for the name of
 * every child one by one, which is slow in ES-DE's `gamelists/` and `downloaded_media/`, with a
 * folder per system.
 */
internal object FrontendFolders {

    /** Document URI of the stored SAF folder [uriString], or null when it is not set or not a folder. */
    fun root(context: Context, uriString: String): Uri? {
        if (uriString.isBlank()) return null
        val dir = StorageHelper.getDocumentFile(context, uriString) ?: return null
        return dir.uri.takeIf { runCatching { dir.isDirectory }.getOrDefault(false) }
    }

    /**
     * Whether the stored SAF folder still works: the permission granted when it was picked is
     * still held, and the folder is there and writable (a removed SD card, a reinstalled
     * frontend or "clear data" all break it).
     */
    fun isAccessible(context: Context, uriString: String): Boolean {
        if (uriString.isBlank()) return false
        val held = context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == uriString && it.isReadPermission && it.isWritePermission
        }
        if (!held) return false
        val dir = StorageHelper.getDocumentFile(context, uriString) ?: return false
        return runCatching { dir.isDirectory && dir.canWrite() }.getOrDefault(false)
    }

    /** Children of [dir] by lower-case name. */
    fun children(context: Context, dir: Uri): Map<String, StorageHelper.Child> =
        StorageHelper.listChildren(context, dir).associateBy { it.name.lowercase() }

    /** The document at [path] ("a/b/c") under [dir], names compared ignoring case; null when missing. */
    fun find(context: Context, dir: Uri, path: String): StorageHelper.Child? {
        var current: StorageHelper.Child? = null
        var uri = dir
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            current = children(context, uri)[part.lowercase()] ?: return null
            uri = current.uri
        }
        return current
    }

    /** UTF-8 text of the document [uri]; throws when it cannot be read. */
    fun readText(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Could not open $uri")

    /** Text of the file at [path] under [dir]; null when there is none, throws when it is there but unreadable. */
    fun readIfPresent(context: Context, dir: Uri, path: String): String? =
        find(context, dir, path)?.takeIf { !it.isDirectory }?.let { readText(context, it.uri) }
}
