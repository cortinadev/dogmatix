package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.util.ArchiveExtractionUtils
import com.cortinadev.dogmatix.util.ArchivePaths
import com.cortinadev.dogmatix.util.Constants
import com.cortinadev.dogmatix.util.StorageHelper
import kotlinx.coroutines.Dispatchers
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
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ArchiveExtractorService"

@Singleton
class ArchiveExtractorService @Inject constructor() {

    /**
     * Extracts an archive from a SAF URI (used for HTTP downloads where the file
     * lands in SAF storage directly).
     */
    suspend fun extractArchive(
        context: Context,
        archiveUri: Uri,
        destinationUri: Uri,
        subPath: String = "",
        onProgress: (Float) -> Unit = {}
    ): List<String> = withContext(Dispatchers.IO) {
        val extractDir = File(context.cacheDir, "extraction_temp/${System.currentTimeMillis()}")
        extractDir.mkdirs()

        try {
            // Open the SAF archive as a seekable FileChannel — no need to copy the archive
            // to a temp file first, saving potentially gigabytes of cache space.
            val pfd = context.contentResolver.openFileDescriptor(archiveUri, "r")
                ?: return@withContext emptyList<String>().also {
                    Log.e(TAG, "Could not open file descriptor for $archiveUri")
                }

            val cachedFiles = pfd.use {
                FileInputStream(it.fileDescriptor).use { fis ->
                    extractToCache(fis, extractDir, onProgress)
                }
            }
            if (cachedFiles.isEmpty()) return@withContext emptyList<String>()

            copyToSaf(context, cachedFiles, destinationUri, subPath)
        } catch (e: Throwable) {
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            emptyList()
        } finally {
            extractDir.deleteRecursively()
        }
    }

    /**
     * Extracts an archive that is already on the local filesystem (used for torrent
     * downloads where the file lives in cacheDir). Avoids writing the compressed
     * archive to SAF at all — extracts straight to a temp dir then copies to SAF.
     */
    suspend fun extractArchiveFile(
        context: Context,
        archiveFile: File,
        destinationUri: Uri,
        subPath: String = "",
        onProgress: (Float) -> Unit = {}
    ): List<String> = withContext(Dispatchers.IO) {
        val extractDir = File(context.cacheDir, "extraction_temp/${System.currentTimeMillis()}")
        extractDir.mkdirs()

        try {
            val cachedFiles = FileInputStream(archiveFile).use { fis ->
                extractToCache(fis, extractDir, onProgress)
            }
            if (cachedFiles.isEmpty()) return@withContext emptyList<String>()

            copyToSaf(context, cachedFiles, destinationUri, subPath)
        } catch (e: Throwable) {
            Log.e(TAG, "Extraction of ${archiveFile.name} failed: ${e.message}", e)
            emptyList()
        } finally {
            extractDir.deleteRecursively()
        }
    }

    /** Extracts every entry under [extractDir], keeping the archive's folders; returns (relative path, file). */
    private fun extractToCache(
        fis: FileInputStream,
        extractDir: File,
        onProgress: (Float) -> Unit
    ): List<Pair<String, File>> {
        val results = mutableListOf<Pair<String, File>>()
        val paths = ArchivePaths.Allocator()
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

        try {
            SevenZip.openInArchive(null, inStream).use { archive ->
                val total = archive.numberOfItems

                archive.extract(null, false, object : IArchiveExtractCallback {
                    var out: OutputStream? = null
                    var dest: File? = null
                    var destPath: String? = null
                    var skip = false
                    var done = 0

                    override fun getStream(index: Int, mode: ExtractAskMode): ISequentialOutStream? {
                        val isFolder = archive.getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
                        if (isFolder) { skip = true; return null }

                        val rawPath = (archive.getProperty(index, PropID.PATH) as? String)?.takeIf { it.isNotBlank() } ?: "file_$index"
                        val safePath = ArchivePaths.safeRelativePath(rawPath)
                        if (safePath == null) {
                            Log.w(TAG, "Skipping archive entry outside the destination: $rawPath")
                            skip = true
                            return null
                        }
                        val path = paths.allocate(safePath)

                        val file = File(extractDir, path)
                        file.parentFile?.mkdirs()
                        dest = file
                        destPath = path
                        val stream = BufferedOutputStream(FileOutputStream(file), Constants.EXTRACTION_BUFFER_SIZE)
                        out = stream
                        skip = false

                        return ISequentialOutStream { data -> stream.write(data); data.size }
                    }

                    override fun prepareOperation(mode: ExtractAskMode) {}

                    override fun setOperationResult(result: ExtractOperationResult) {
                        try { out?.close() } catch (_: Exception) {}
                        out = null

                        if (!skip && result == ExtractOperationResult.OK) {
                            val file = dest
                            val path = destPath
                            if (file != null && path != null) { results.add(path to file); done++ }
                            onProgress(if (total > 0) done.toFloat() / total else 1f)
                        } else if (!skip) {
                            Log.w(TAG, "Entry result: $result for ${dest?.name}")
                        }
                        dest = null
                        destPath = null
                        skip = false
                    }

                    override fun setCompleted(complete: Long) {}
                    override fun setTotal(total: Long) {}
                })
            }
        } catch (e: SevenZipException) {
            Log.e(TAG, "7-zip extraction error: ${e.message}")
        }

        return results
    }

    /** Copies the extracted files into the destination, folders included; returns their relative paths. */
    private fun copyToSaf(
        context: Context,
        files: List<Pair<String, File>>,
        destinationUri: Uri,
        subPath: String
    ): List<String> {
        val destUri = ArchiveExtractionUtils.prepareExtractionDestination(context, destinationUri, subPath)
        val root = StorageHelper.getDocumentFile(context, destUri.toString()) ?: run {
            Log.e(TAG, "Extraction destination not accessible: $destUri")
            return emptyList()
        }
        val dirs = HashMap<String, DocumentFile?>()
        val copied = mutableListOf<String>()
        val buffer = ByteArray(Constants.EXTRACTION_BUFFER_SIZE)

        for ((path, file) in files) {
            val parent = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            val dir = dirs.getOrPut(parent) { StorageHelper.createDirectory(root, parent) } ?: continue
            runCatching { dir.findFile(name)?.delete() }
            val outUri = runCatching { dir.createFile("application/octet-stream", name) }.getOrNull()?.uri ?: run {
                Log.e(TAG, "Could not create $path in ${dir.uri}")
                continue
            }

            context.contentResolver.openOutputStream(outUri)?.use { raw ->
                BufferedOutputStream(raw, Constants.EXTRACTION_BUFFER_SIZE).use { buffOut ->
                    file.inputStream().use { input ->
                        var n: Int
                        while (input.read(buffer).also { n = it } != -1) buffOut.write(buffer, 0, n)
                    }
                }
            }
            copied.add(path)
            Log.d(TAG, "Copied to SAF: $path")
        }

        return copied
    }
}
