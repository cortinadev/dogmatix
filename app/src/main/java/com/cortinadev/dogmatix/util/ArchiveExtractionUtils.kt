package com.cortinadev.dogmatix.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.util.Constants.EXTRACTION_BUFFER_SIZE
import com.cortinadev.dogmatix.util.Constants.MAX_ARCHIVE_ENTRIES
import java.io.InputStream
import java.io.OutputStream

object ArchiveExtractionUtils {

    private const val TAG = "ArchiveExtractionUtils"

    fun validateArchiveFile(context: Context, archiveUri: Uri): DocumentFile? {
        val archiveFile = DocumentFile.fromSingleUri(context, archiveUri)
        if (archiveFile == null || !archiveFile.exists()) {
            Log.e(TAG, "Archive file not found: $archiveUri")
            return null
        }
        return archiveFile
    }

    fun getFileExtension(fileName: String): String {
        return fileName.substringAfterLast(".", "")
    }

    fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[<>:\"/\\\\|?*]"), "_")
    }

    fun copyStream(
        inputStream: InputStream, 
        outputStream: OutputStream, 
        bufferSize: Int = EXTRACTION_BUFFER_SIZE
    ): Long {
        val buffer = ByteArray(bufferSize)
        var totalBytes = 0L
        var bytesRead: Int
        
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
            totalBytes += bytesRead
        }
        
        return totalBytes
    }
    
    fun validateEntryCount(entryCount: Int): Boolean {
        if (entryCount > MAX_ARCHIVE_ENTRIES) {
            Log.w(TAG, "Archive has too many entries: $entryCount (max: $MAX_ARCHIVE_ENTRIES)")
            return false
        }
        return true
    }
    
    fun calculateProgress(current: Long, total: Long): Float {
        return if (total > 0) {
            (current.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
    }
    
    fun logProgress(extractedCount: Int, totalCount: Int, fileName: String) {
        if (extractedCount % 10 == 0 || extractedCount == totalCount) {
            Log.d(TAG, "Extracted $extractedCount/$totalCount files. Current: $fileName")
        }
    }
    
    /**
     * The folder an archive unpacks into ([destinationUri]/[subPath], created when missing), or
     * null when it cannot be reached or made. Never the root instead: the files would land outside
     * the console's folder without anyone noticing.
     */
    fun prepareExtractionDestination(
        context: Context,
        destinationUri: Uri,
        subPath: String
    ): Uri? {
        if (destinationUri == Uri.EMPTY) return null
        val folder = StorageHelper.createDirectory(context, destinationUri.toString(), subPath)
        if (folder == null) Log.w(TAG, "Extraction destination not accessible: $destinationUri/$subPath")
        return folder?.uri
    }
}
