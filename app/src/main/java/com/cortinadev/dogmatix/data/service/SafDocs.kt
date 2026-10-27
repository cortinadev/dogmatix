package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import com.cortinadev.dogmatix.util.CopyCheck
import com.cortinadev.dogmatix.util.DocumentIds
import com.cortinadev.dogmatix.util.LibraryMoveRules
import com.cortinadev.dogmatix.util.StorageProblem
import com.cortinadev.dogmatix.util.StorageProblems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

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

    // ---- Reachability ------------------------------------------------------------------------

    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /**
     * What stops the app from writing into the folder [uriString] (a picked tree, or a folder in
     * one; for a file, the root of its tree) right now: its volume gone, its grant lost, the folder
     * unreachable or read-only. Null when it is fine, or when [uriString] is not a SAF tree at all.
     * Asked before long operations, so they stop with a reason instead of failing file by file.
     */
    fun problemOf(context: Context, uriString: String): StorageProblem? {
        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return null
        if (!runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)) return null
        val treeId = runCatching { treeId(uri) }.getOrNull() ?: return null
        val granted = runCatching {
            context.contentResolver.persistedUriPermissions.any { grant ->
                grant.isReadPermission && grant.isWritePermission && grant.uri.authority == uri.authority &&
                    runCatching { DocumentsContract.getTreeDocumentId(grant.uri) == treeId }.getOrDefault(false)
            }
        }.getOrDefault(false)
        val doc = docUri(uriString) ?: return null
        val flags = flagsOf(context, doc)?.let { (flags, isDirectory) ->
            if (isDirectory) flags else flagsOf(context, treeRootOf(doc))?.first
        }
        return StorageProblems.ofTree(
            granted = granted,
            volumeMounted = volumeMounted(context, uri.authority, treeId),
            reachable = flags != null,
            writable = flags != null && flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
        )
    }

    /**
     * True/false for the volumes of external storage (internal storage, SD cards, USB); null when
     * it cannot be told: other providers, and roots of external storage that are no volume
     * (`home:` for Documents, `downloads`…), which the system never lists.
     */
    private fun volumeMounted(context: Context, authority: String?, treeId: String): Boolean? {
        if (authority != EXTERNAL_STORAGE) return null
        val volume = DocumentIds.volumeOf(treeId) ?: return null
        if (volume.equals("primary", ignoreCase = true)) return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
        val manager = context.getSystemService(StorageManager::class.java) ?: return null
        val listed = runCatching { manager.storageVolumes }.getOrNull() ?: return null
        listed.firstOrNull { it.uuid.equals(volume, ignoreCase = true) }?.let { return it.state == Environment.MEDIA_MOUNTED }
        // A card that was pulled out is no longer listed at all; anything else unlisted is unknown.
        return if (DocumentIds.looksLikeVolumeUuid(volume)) false else null
    }

    /** The document's flags, and whether it is a folder; null when the provider does not answer for it. */
    private fun flagsOf(context: Context, doc: Uri): Pair<Int, Boolean>? = runCatching {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_MIME_TYPE)
        context.contentResolver.query(doc, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getInt(0) to (c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) else null
        }
    }.getOrNull()

    /** [problemOf] the first of [uris] that has one. */
    fun problemOfAny(context: Context, uris: Iterable<String>): StorageProblem? =
        uris.distinct().firstNotNullOfOrNull { problemOf(context, it) }

    // ---- Checked copies ----------------------------------------------------------------------

    /** The bytes of [uri] at the [CopyCheck] sample offsets of a file of [size]; null when it cannot be read. */
    fun samples(context: Context, uri: Uri, size: Long): List<ByteArray>? {
        val offsets = CopyCheck.offsets(size)
        if (offsets.isEmpty()) return emptyList()
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { readAt(it.channel, size, offsets) }
            }
        }.getOrNull() ?: runCatching {
            // Providers that hand out pipes cannot seek: read through, skipping between blocks.
            context.contentResolver.openInputStream(uri)?.use { readThrough(it, size, offsets) }
        }.getOrNull()
    }

    fun samples(file: File, size: Long): List<ByteArray>? {
        val offsets = CopyCheck.offsets(size)
        if (offsets.isEmpty()) return emptyList()
        return runCatching { FileInputStream(file).use { readAt(it.channel, size, offsets) } }.getOrNull()
    }

    private fun readAt(channel: FileChannel, size: Long, offsets: List<Long>): List<ByteArray> = offsets.map { offset ->
        val buffer = ByteBuffer.allocate(CopyCheck.lengthAt(size, offset))
        channel.position(offset)
        while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
        buffer.array().copyOf(buffer.position())
    }

    private fun readThrough(input: InputStream, size: Long, offsets: List<Long>): List<ByteArray> {
        var position = 0L
        return offsets.map { offset ->
            while (position < offset) {
                val skipped = input.skip(offset - position)
                if (skipped <= 0) {
                    if (input.read() < 0) break
                    position++
                } else position += skipped
            }
            val block = ByteArray(CopyCheck.lengthAt(size, offset))
            var filled = 0
            while (filled < block.size) {
                val n = input.read(block, filled, block.size - filled)
                if (n < 0) break
                filled += n
            }
            position += filled
            block.copyOf(filled)
        }
    }

    /** Whether [copy] ([copySize] bytes, null = unknown) stands for [source]: [CopyCheck.verdict] over both. */
    fun compare(context: Context, source: Uri, sourceSize: Long, copy: Uri, copySize: Long?): CopyCheck.Verdict {
        if (sourceSize < 0 || copySize != sourceSize) return CopyCheck.Verdict.SIZE
        return CopyCheck.verdict(sourceSize, copySize, samples(context, source, sourceSize), samples(context, copy, copySize))
    }

    /**
     * Copies the file [source] ([size] bytes) into the folder [targetDir] as [name], never over a
     * file already there: under `<name>.dogmatix-part` first, then checked against the original
     * ([compare]: size and sampled blocks) and only then renamed. Returns the copy, or null when it
     * did not come out the same (the part is deleted). Throws on read and write errors, leaving
     * nothing behind. [replaceStalePart] deletes a part of that name an earlier run left.
     */
    suspend fun copyFileVerified(
        context: Context,
        source: Uri,
        size: Long,
        targetDir: Uri,
        name: String,
        replaceStalePart: Boolean = false,
        onBytes: (Long) -> Unit = {}
    ): Uri? {
        val resolver = context.contentResolver
        val partName = LibraryMoveRules.partName(name)
        if (replaceStalePart) child(context, targetDir, partName)?.let { delete(context, it.uri) }
        val part = DocumentsContract.createDocument(resolver, targetDir, OCTET_STREAM, partName)
            ?: throw IOException("Cannot create $partName")
        // What is ours to delete if anything goes wrong: the part until it is renamed, then the copy.
        var partAlive = true
        var placed: Uri? = null
        try {
            copyInto(context, source, part, onBytes)
            if (compare(context, source, size, part, stat(context, part)?.size) != CopyCheck.Verdict.SAME) {
                Log.w(TAG, "Copy of $name did not match its original")
                delete(context, part)
                return null
            }
            if (child(context, targetDir, name) != null) throw IOException("$name appeared in the target meanwhile")
            val renamed = runCatching { DocumentsContract.renameDocument(resolver, part, name) }.getOrNull()
            if (renamed == null) {
                // A provider that cannot rename gets the file written again under its own name.
                val direct = DocumentsContract.createDocument(resolver, targetDir, OCTET_STREAM, name) ?: throw IOException("Cannot create $name")
                placed = direct
                if (delete(context, part)) partAlive = false
                copyInto(context, source, direct)
                if (compare(context, source, size, direct, stat(context, direct)?.size) != CopyCheck.Verdict.SAME) {
                    delete(context, direct)
                    return null
                }
                return direct
            }
            partAlive = false
            placed = renamed
            // The provider never overwrites: a name taken in the meantime comes back as "name (1)".
            if (stat(context, renamed)?.name != name) throw IOException("$name was taken in the target meanwhile")
            return renamed
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                if (partAlive) delete(context, part)
                placed?.let { delete(context, it) }
            }
            throw e
        }
    }

    /** How [moveFile] went. */
    enum class Placement {
        /** Moved by the provider, or copied, checked and its original deleted. */
        MOVED,
        /** The same file was already in the target (what an interrupted move leaves): the original was deleted. */
        ALREADY_THERE,
        /** Something else of that name is in the target: both stay as they are. */
        CONFLICT,
        /** The copy did not match its original: the copy went, the original stays. */
        UNVERIFIED,
        /** Could not be moved; [Placed.problem] says why when it can be told. */
        FAILED
    }

    data class Placed(val placement: Placement, val problem: StorageProblem? = null, val copied: Boolean = false)

    /**
     * Moves the file [source] (in the folder [sourceParent], null when unknown) into the folder
     * [target] under its own name: by the provider when it can (same volume, a rename), otherwise
     * copied with [copyFileVerified] and only then is the original deleted. A file of that name in
     * [target] is never replaced: the same file (size and samples) stands for this one and the
     * original goes; anything else is a [Placement.CONFLICT].
     */
    suspend fun moveFile(
        context: Context,
        source: Doc,
        sourceParent: Uri?,
        target: Uri,
        replaceStalePart: Boolean = false,
        onBytes: (Long) -> Unit = {}
    ): Placed {
        val existing = child(context, target, source.name)
        if (existing != null) {
            if (existing.isDirectory || compare(context, source.uri, source.size, existing.uri, existing.size) != CopyCheck.Verdict.SAME) {
                return Placed(Placement.CONFLICT)
            }
            return if (delete(context, source.uri)) Placed(Placement.ALREADY_THERE) else Placed(Placement.FAILED, problemOfAny(context, listOf(source.uri.toString())))
        }
        if (sourceParent != null && move(context, source.uri, sourceParent, target) != null) return Placed(Placement.MOVED)
        val copy = try {
            copyFileVerified(context, source.uri, source.size, target, source.name, replaceStalePart, onBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Copy of ${source.name} failed: ${e.message}")
            return Placed(Placement.FAILED, StorageProblems.classify(e) ?: problemOfAny(context, listOf(target.toString(), source.uri.toString())))
        } ?: return Placed(Placement.UNVERIFIED, copied = true)
        if (delete(context, source.uri)) return Placed(Placement.MOVED, copied = true)
        // The original cannot go: drop the copy so the file is not left twice.
        delete(context, copy)
        return Placed(Placement.FAILED, problemOfAny(context, listOf(source.uri.toString())), copied = true)
    }

    private const val OCTET_STREAM = "application/octet-stream"

    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE
    )
}
