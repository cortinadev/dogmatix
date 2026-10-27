package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.cortinadev.dogmatix.util.ArchiveExtraction
import com.cortinadev.dogmatix.util.ArchiveExtractionException
import com.cortinadev.dogmatix.util.ArchiveExtractionUtils
import com.cortinadev.dogmatix.util.ArchivePaths
import com.cortinadev.dogmatix.util.Constants
import com.cortinadev.dogmatix.util.CopyCheck
import com.cortinadev.dogmatix.util.DownloadFailure
import com.cortinadev.dogmatix.util.LibraryMoveRules
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.StorageProblem
import com.cortinadev.dogmatix.util.StorageProblems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.ISeekableStream
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ArchiveExtractorService"
private const val TEMP_DIR = "extraction_temp"

/**
 * Unpacks a finished download into its folder: every entry to `cacheDir/extraction_temp/<run>/`
 * first, then copied into the folder (under `<name>.dogmatix-part`, checked by size and renamed).
 * Either the whole archive lands or nothing of this run stays: on any failure (or cancellation)
 * what it created is removed again, the archive is left as it was and the caller gets an
 * [ArchiveExtractionException] saying why. Files already in the folder are never overwritten: the
 * same file is taken as it is, anything else of that name stays and the entry gets `name (2).ext`.
 */
@Singleton
class ArchiveExtractorService @Inject constructor() {

    /**
     * Extracts an archive from a SAF URI (used for HTTP downloads where the file lands in SAF
     * storage directly). Returns the paths written, relative to [destinationUri]/[subPath].
     */
    suspend fun extractArchive(
        context: Context,
        archiveUri: Uri,
        destinationUri: Uri,
        subPath: String = "",
        onProgress: (Float) -> Unit = {}
    ): List<String> = withContext(Dispatchers.IO) {
        val label = archiveUri.lastPathSegment?.substringAfterLast('/') ?: archiveUri.toString()
        unpackInto(context, label, destinationUri, subPath) { extractDir, isActive ->
            // Opened as a seekable FileChannel: no copy of the archive to a temp file first.
            val pfd = try {
                context.contentResolver.openFileDescriptor(archiveUri, "r")
            } catch (e: Exception) {
                throw ArchiveExtractionException(storageFailure(context, destinationUri, e), "Cannot open $label: ${e.message}", e)
            } ?: throw ArchiveExtractionException(DownloadFailure.STORAGE, "Cannot open $label")
            pfd.use { FileInputStream(it.fileDescriptor).use { fis -> extractToCache(context, fis, extractDir, destinationUri, isActive, onProgress) } }
        }
    }

    /**
     * Extracts an archive that is already on the local filesystem (used for torrent downloads
     * where the file lives in cacheDir), without writing the compressed archive to SAF at all.
     */
    suspend fun extractArchiveFile(
        context: Context,
        archiveFile: File,
        destinationUri: Uri,
        subPath: String = "",
        onProgress: (Float) -> Unit = {}
    ): List<String> = withContext(Dispatchers.IO) {
        unpackInto(context, archiveFile.name, destinationUri, subPath) { extractDir, isActive ->
            FileInputStream(archiveFile).use { fis -> extractToCache(context, fis, extractDir, destinationUri, isActive, onProgress) }
        }
    }

    private suspend fun unpackInto(
        context: Context,
        label: String,
        destinationUri: Uri,
        subPath: String,
        unpack: (File, () -> Boolean) -> List<Pair<String, File>>
    ): List<String> {
        val job = currentCoroutineContext()[Job]
        val extractDir = newExtractDir(context)
        try {
            val cached = unpack(extractDir) { job?.isActive != false }
            currentCoroutineContext().ensureActive()
            return copyToSaf(context, cached, destinationUri, subPath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ArchiveExtractionException) {
            Log.e(TAG, "Extraction of $label failed (${e.failure}): ${e.message}")
            throw e
        } catch (e: Throwable) {
            // Anything else 7-Zip or the native library throws (a missing ABI included).
            Log.e(TAG, "Extraction of $label failed: ${e.message}", e)
            val failure = if (StorageProblems.classify(e) == StorageProblem.NO_SPACE) DownloadFailure.EXTRACTION_NO_SPACE else DownloadFailure.EXTRACTION
            throw ArchiveExtractionException(failure, "Extraction of $label failed: ${e.message}", e)
        } finally {
            withContext(NonCancellable) { extractDir.deleteRecursively() }
        }
    }

    /**
     * A fresh folder for one run. The first run of the process clears what a run cut short by a
     * killed process left behind (nothing else unpacks before it, every run waits for this).
     */
    private suspend fun newExtractDir(context: Context): File {
        val base = File(context.cacheDir, TEMP_DIR)
        if (!tempCleared) tempLock.withLock {
            if (!tempCleared) {
                base.listFiles()?.forEach { it.deleteRecursively() }
                tempCleared = true
            }
        }
        return File(base, "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
    }

    /**
     * Extracts every entry under [extractDir], keeping the archive's folders; returns (relative
     * path, file). Room is checked first (the cache holds everything before it is copied, the
     * folder must take it all); an entry outside the destination, a damaged or unsupported entry,
     * or an archive with nothing in it stops it all.
     */
    private fun extractToCache(
        context: Context,
        fis: FileInputStream,
        extractDir: File,
        destinationUri: Uri,
        isActive: () -> Boolean,
        onProgress: (Float) -> Unit
    ): List<Pair<String, File>> {
        val channel = fis.channel
        val channelSize = channel.size()

        // Implement IInStream over a FileChannel so SevenZip can seek through the archive
        // without requiring a copy to a RandomAccessFile.
        val inStream = object : IInStream {
            override fun read(data: ByteArray): Int {
                val n = channel.read(ByteBuffer.wrap(data))
                return if (n == -1) 0 else n
            }

            override fun seek(offset: Long, seekOrigin: Int): Long {
                val newPos = when (seekOrigin) {
                    ISeekableStream.SEEK_SET -> offset
                    ISeekableStream.SEEK_CUR -> channel.position() + offset
                    ISeekableStream.SEEK_END -> channelSize + offset
                    else -> throw SevenZipException("Unknown seek origin: $seekOrigin")
                }
                channel.position(newPos)
                return channel.position()
            }

            override fun close() {
                // The channel is closed by the FileInputStream.use block
            }
        }

        val archive = try {
            SevenZip.openInArchive(null, inStream)
        } catch (e: SevenZipException) {
            throw ArchiveExtractionException(DownloadFailure.EXTRACTION, "Not an archive 7-Zip can open: ${e.message}", e)
        }
        return archive.use {
            val total = archive.numberOfItems
            // Where each entry lands, decided before anything is written.
            val allocator = ArchivePaths.Allocator()
            val paths = arrayOfNulls<String>(total)
            var needed = 0L
            for (index in 0 until total) {
                if (archive.getProperty(index, PropID.IS_FOLDER) as? Boolean == true) continue
                val rawPath = (archive.getProperty(index, PropID.PATH) as? String)?.takeIf { it.isNotBlank() } ?: "file_$index"
                val safePath = ArchivePaths.safeRelativePath(rawPath)
                    ?: throw ArchiveExtractionException(DownloadFailure.EXTRACTION, "Entry outside the destination: $rawPath")
                paths[index] = allocator.allocate(safePath)
                needed += (archive.getProperty(index, PropID.SIZE) as? Long)?.coerceAtLeast(0) ?: 0L
            }
            val files = paths.count { it != null }
            if (files == 0) throw ArchiveExtractionException(DownloadFailure.EXTRACTION, "The archive holds no files")
            if (!ArchiveExtraction.fits(needed, extractDir.usableSpace)) {
                throw ArchiveExtractionException(DownloadFailure.EXTRACTION_NO_SPACE, "Unpacking needs $needed bytes in the app's cache, ${extractDir.usableSpace} free")
            }
            val destinationFree = StorageHelper.getFreeBytes(context, destinationUri.toString())
            if (!ArchiveExtraction.fits(needed, destinationFree)) {
                throw ArchiveExtractionException(DownloadFailure.EXTRACTION_NO_SPACE, "Unpacking needs $needed bytes in the folder, $destinationFree free")
            }

            val results = ArrayList<Pair<String, File>>(files)
            /** What stopped the run, kept apart from the SevenZipException that carries it out. */
            var error: Throwable? = null
            val callback = object : IArchiveExtractCallback {
                var out: OutputStream? = null
                var index = -1

                override fun getStream(index: Int, mode: ExtractAskMode): ISequentialOutStream? {
                    if (!isActive()) throw SevenZipException("Cancelled")
                    val path = paths[index] ?: return null
                    val file = File(extractDir, path)
                    file.parentFile?.mkdirs()
                    val stream = BufferedOutputStream(FileOutputStream(file), Constants.EXTRACTION_BUFFER_SIZE)
                    out = stream
                    this.index = index
                    return ISequentialOutStream { data ->
                        // Checked on every block, not only between entries: a 4 GB image inside
                        // an archive stops at once when the download is paused, stopped or deleted.
                        if (!isActive()) throw SevenZipException("Cancelled")
                        try {
                            stream.write(data)
                        } catch (e: IOException) {
                            if (error == null) error = e
                            throw SevenZipException("Cannot write ${file.name}: ${e.message}")
                        }
                        data.size
                    }
                }

                override fun prepareOperation(mode: ExtractAskMode) {}

                override fun setOperationResult(result: ExtractOperationResult) {
                    val stream = out
                    out = null
                    try {
                        stream?.close()
                    } catch (e: IOException) {
                        if (error == null) error = e
                    }
                    val entry = index
                    index = -1
                    if (entry < 0) return
                    val path = paths[entry] ?: return
                    if (result != ExtractOperationResult.OK || error != null) {
                        if (error == null) error = ArchiveExtractionException(DownloadFailure.EXTRACTION, "Entry $path: $result")
                        throw SevenZipException("Entry $path: $result")
                    }
                    results += path to File(extractDir, path)
                    onProgress(results.size.toFloat() / files)
                }

                override fun setCompleted(complete: Long) {}
                override fun setTotal(total: Long) {}
            }
            try {
                archive.extract(null, false, callback)
            } catch (e: SevenZipException) {
                // A cancellation is carried out of 7-Zip as an exception too: it is a cancellation, not a damaged archive.
                if (!isActive()) throw CancellationException("Extraction cancelled")
                throw cacheFailure(error ?: e)
            } finally {
                // An entry cut short still has its file open; the run's folder is deleted after this.
                runCatching { callback.out?.close() }
                callback.out = null
            }
            if (!isActive()) throw CancellationException("Extraction cancelled")
            error?.let { throw cacheFailure(it) }
            // Some formats report entries they never hand out: every file must have come out.
            if (results.size != files) throw ArchiveExtractionException(DownloadFailure.EXTRACTION, "Only ${results.size} of $files files came out")
            results
        }
    }

    /** A failure while unpacking into the cache: out of room there, or the archive's own fault. */
    private fun cacheFailure(error: Throwable): ArchiveExtractionException = when {
        error is ArchiveExtractionException -> error
        StorageProblems.classify(error) == StorageProblem.NO_SPACE ->
            ArchiveExtractionException(DownloadFailure.EXTRACTION_NO_SPACE, "No room left in the app's cache: ${error.message}", error)
        else -> ArchiveExtractionException(DownloadFailure.EXTRACTION, "Damaged or unsupported archive: ${error.message}", error)
    }

    /** What a failure to read the archive or to write into the folder is: what it says, or what is wrong with the folder now. */
    private fun storageFailure(context: Context, destinationUri: Uri, error: Throwable?): DownloadFailure =
        ArchiveExtraction.writeFailure(error?.let(StorageProblems::classify), SafDocs.problemOf(context, destinationUri.toString()))

    /** Copies the extracted files into the destination, folders included; returns their relative paths there. */
    private suspend fun copyToSaf(
        context: Context,
        files: List<Pair<String, File>>,
        destinationUri: Uri,
        subPath: String
    ): List<String> {
        val folder = ArchiveExtractionUtils.prepareExtractionDestination(context, destinationUri, subPath)
        val root = folder?.let { SafDocs.docUri(it.toString()) }
            ?: throw ArchiveExtractionException(storageFailure(context, destinationUri, null), "Extraction destination not accessible: $destinationUri/$subPath")
        val writer = SafWriter(context, root)
        try {
            return files.map { (path, file) ->
                currentCoroutineContext().ensureActive()
                writer.place(path, file).also { Log.d(TAG, "Copied to SAF: $it") }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) { writer.rollback() }
            if (e is CancellationException || e is ArchiveExtractionException) throw e
            throw ArchiveExtractionException(storageFailure(context, destinationUri, e), "Could not write the unpacked files: ${e.message}", e)
        }
    }

    /**
     * Writes the unpacked files below [root], remembering everything it creates so a failed run
     * can take it all back ([rollback]). Folders already there are reused (any case of the name).
     */
    private class SafWriter(private val context: Context, private val root: Uri) {
        private val resolver = context.contentResolver
        /** Requested folder path (lower case) → its document and its real path (the provider may rename). */
        private val dirs = HashMap<String, Pair<Uri, String>>()
        private val listings = HashMap<Uri, List<SafDocs.Doc>>()
        private val allocator = ArchivePaths.Allocator()
        private val seeded = HashSet<String>()
        private val createdFiles = ArrayList<Uri>()
        private val createdDirs = ArrayList<Uri>()

        /** Puts [file] at [path] (or beside it, see the class); returns where it really is. */
        suspend fun place(path: String, file: File): String {
            val (dir, dirPath) = ensureDir(path.substringBeforeLast('/', ""))
            val name = path.substringAfterLast('/')
            val siblings = listing(dir)
            // Everything already in the folder is off limits for the names handed out.
            if (seeded.add(dirPath.lowercase(Locale.ROOT))) siblings.forEach { allocator.reserve(join(dirPath, it.name)) }
            val existing = siblings.firstOrNull { it.name == name } ?: siblings.firstOrNull { it.name.equals(name, ignoreCase = true) }
            val size = file.length()
            val same = existing != null && !existing.isDirectory && existing.size == size &&
                CopyCheck.verdict(size, existing.size, SafDocs.samples(file, size), SafDocs.samples(context, existing.uri, size)) == CopyCheck.Verdict.SAME
            when (ArchiveExtraction.existing(size, existing?.size?.takeIf { !existing.isDirectory }, existing?.isDirectory == true, same)) {
                // An earlier unpack of the same archive (or the same file from elsewhere): taken as it is.
                ArchiveExtraction.Existing.SAME -> return join(dirPath, existing!!.name)
                ArchiveExtraction.Existing.NONE, ArchiveExtraction.Existing.OTHER -> Unit
            }
            val finalName = allocator.allocate(join(dirPath, name)).substringAfterLast('/')
            val part = DocumentsContract.createDocument(resolver, dir, OCTET_STREAM, LibraryMoveRules.partName(finalName))
                ?: throw IOException("Cannot create ${LibraryMoveRules.partName(finalName)}")
            createdFiles += part
            write(file, part)
            // Null (no row yet right after creating) or -1 (no sizes at all): nothing to check against.
            val written = SafDocs.stat(context, part)?.size
            if (written != null && written != -1L && written != size) throw IOException("Short write of $finalName: $written of $size bytes")
            val renamed = runCatching { DocumentsContract.renameDocument(resolver, part, finalName) }.getOrNull()
            val placed = if (renamed != null) {
                createdFiles[createdFiles.lastIndex] = renamed
                renamed
            } else {
                // A provider that cannot rename gets the file written again under its own name.
                val direct = DocumentsContract.createDocument(resolver, dir, OCTET_STREAM, finalName) ?: throw IOException("Cannot create $finalName")
                createdFiles += direct
                write(file, direct)
                SafDocs.stat(context, direct)?.size?.let { if (it != size && it != -1L) throw IOException("Short write of $finalName") }
                if (SafDocs.delete(context, part)) createdFiles.remove(part)
                direct
            }
            // The provider never overwrites: it may have given the file another free name.
            return join(dirPath, SafDocs.stat(context, placed)?.name ?: finalName)
        }

        private suspend fun write(file: File, target: Uri) {
            val buffer = ByteArray(Constants.EXTRACTION_BUFFER_SIZE)
            val output = resolver.openOutputStream(target, "wt") ?: throw IOException("Cannot write $target")
            BufferedOutputStream(output, Constants.EXTRACTION_BUFFER_SIZE).use { out ->
                file.inputStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
            }
        }

        private fun ensureDir(requested: String): Pair<Uri, String> {
            if (requested.isEmpty()) return root to ""
            dirs[requested.lowercase(Locale.ROOT)]?.let { return it }
            val (parent, parentPath) = ensureDir(requested.substringBeforeLast('/', ""))
            val name = requested.substringAfterLast('/')
            val siblings = listing(parent)
            val existing = siblings.firstOrNull { it.isDirectory && it.name == name } ?: siblings.firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
            val result = if (existing != null) {
                existing.uri to join(parentPath, existing.name)
            } else {
                // A file in the way makes the provider pick another folder name: the real one is read back.
                val created = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
                    ?: throw IOException("Cannot create folder $requested")
                createdDirs += created
                listings[created] = emptyList()
                created to join(parentPath, SafDocs.stat(context, created)?.name ?: name)
            }
            dirs[requested.lowercase(Locale.ROOT)] = result
            return result
        }

        private fun listing(dir: Uri): List<SafDocs.Doc> = listings.getOrPut(dir) { SafDocs.list(context, dir).orEmpty() }

        /** Deletes what this run created: its files, then its folders (deepest first) once empty. */
        fun rollback() {
            createdFiles.forEach { SafDocs.delete(context, it) }
            createdDirs.asReversed().forEach { dir -> if (SafDocs.list(context, dir)?.isEmpty() == true) SafDocs.delete(context, dir) }
            if (createdFiles.isNotEmpty()) Log.i(TAG, "Removed the ${createdFiles.size} file(s) of a failed unpack")
        }

        private fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"
    }

    private companion object {
        const val OCTET_STREAM = "application/octet-stream"
        /** Process-wide: the leftovers of a killed process are cleared once, before the first run. */
        val tempLock = Mutex()
        @Volatile var tempCleared = false
    }
}
