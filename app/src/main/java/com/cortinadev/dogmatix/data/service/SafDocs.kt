package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/**
 * Document-level SAF operations shared by the trash and the library mover: everything works on
 * document URIs inside a granted tree (`content://…/tree/<root>/document/<doc>`), so a folder
 * found by walking can be moved, renamed or deleted without a `DocumentFile` per step. Folders
 * are listed with one provider query each, sizes included.
 */
internal object SafDocs {
    private const val TAG = "SafDocs"
    private const val BUFFER = 256 * 1024

    data class Doc(val uri: Uri, val name: String, val isDirectory: Boolean, val size: Long)

    /** Document URI for [uriString]: a picked tree becomes the document of its root folder. */
    fun docUri(uriString: String): Uri? = runCatching {
        val uri = uriString.toUri()
        if (!DocumentsContract.isTreeUri(uri)) return@runCatching null
        if (isDocumentInTree(uri)) uri else DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    }.getOrNull()

    private fun isDocumentInTree(uri: Uri): Boolean = uri.pathSegments.let { it.size >= 4 && it[0] == "tree" && it[2] == "document" }

    fun docId(uri: Uri): String =
        if (isDocumentInTree(uri)) DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri)

    fun treeId(uri: Uri): String = DocumentsContract.getTreeDocumentId(uri)

    /** The tree [uri] was granted through (`content://…/tree/<root>`). */
    fun treeUriOf(uri: Uri): Uri = DocumentsContract.buildTreeDocumentUri(uri.authority, treeId(uri))

    /** The document of the root folder of [uri]'s tree. */
    fun treeRootOf(uri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(uri, treeId(uri))

    /** Another document of the same tree as [inTree]. */
    fun sibling(inTree: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(inTree, docId)

    fun stat(context: Context, uri: Uri): Doc? = runCatching {
        context.contentResolver.query(uri, PROJECTION, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            Doc(uri, c.getString(1) ?: return@use null, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (c.isNull(3)) -1 else c.getLong(3))
        }
    }.getOrNull()

    /** Children of the folder [dir]; null when it cannot be listed (gone, or no permission). */
    fun list(context: Context, dir: Uri): List<Doc>? = try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(dir, docId(dir))
        context.contentResolver.query(children, PROJECTION, null, null, null)?.use { c ->
            val out = ArrayList<Doc>(c.count)
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out += Doc(sibling(dir, id), name, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (c.isNull(3)) -1 else c.getLong(3))
            }
            out
        }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot list $dir: ${e.message}")
        null
    }

    fun child(context: Context, dir: Uri, name: String): Doc? = list(context, dir)?.firstOrNull { it.name == name }

    /** [dir]/[relative] ("snes/Disc 1"), creating what is missing; null when a folder cannot be made. */
    fun ensureDirs(context: Context, dir: Uri, relative: String): Uri? {
        var current = dir
        for (part in relative.split('/').filter { it.isNotEmpty() }) {
            val existing = child(context, current, part)
            current = when {
                existing?.isDirectory == true -> existing.uri
                existing != null -> return null
                else -> createDir(context, current, part) ?: return null
            }
        }
        return current
    }

    fun createDir(context: Context, parent: Uri, name: String): Uri? = runCatching {
        DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
    }.getOrNull()

    /** An empty file; octet-stream keeps the display name as given (text types gain an extension). */
    fun createFile(context: Context, parent: Uri, name: String): Uri? = runCatching {
        DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", name)
    }.getOrNull()

    /** Provider-side move (a rename on the same volume); null when the provider cannot do it. */
    fun move(context: Context, doc: Uri, fromParent: Uri, toParent: Uri): Uri? = try {
        DocumentsContract.moveDocument(context.contentResolver, doc, fromParent, toParent)
    } catch (e: Exception) {
        Log.d(TAG, "Provider move of $doc failed: ${e.message}")
        null
    }

    fun rename(context: Context, doc: Uri, name: String): Uri? = runCatching {
        DocumentsContract.renameDocument(context.contentResolver, doc, name)
    }.getOrNull()

    /** Deletes a file, or a folder with everything in it. */
    fun delete(context: Context, doc: Uri): Boolean = runCatching {
        DocumentsContract.deleteDocument(context.contentResolver, doc)
    }.getOrDefault(false)

    /** Bytes below [dir] (every file, any depth); -1 entries count as 0. */
    fun folderSize(context: Context, dir: Uri): Long =
        (list(context, dir) ?: return 0).sumOf { if (it.isDirectory) folderSize(context, it.uri) else it.size.coerceAtLeast(0) }

    /**
     * Streams [source] into the new file [target] (which must exist), reporting each block to
     * [onBytes]; cancellable between blocks. Returns the bytes written.
     */
    suspend fun copyInto(context: Context, source: Uri, target: Uri, onBytes: (Long) -> Unit = {}): Long {
        var written = 0L
        val input = context.contentResolver.openInputStream(source) ?: throw IOException("Cannot read $source")
        input.use { ins ->
            val output = context.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Cannot write $target")
            output.use { out ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = ins.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    written += read
                    onBytes(read.toLong())
                }
            }
        }
        return written
    }

    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE
    )
}
