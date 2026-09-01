package com.cortinadev.dogmatix.util

import android.content.Context
import java.io.File
import android.os.storage.StorageManager
import android.os.StatFs
import android.os.Environment
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.OutputStream

object StorageHelper {
    private const val TAG = "StorageHelper"

    fun getDocumentFile(context: Context, uriString: String): DocumentFile? {
        return try {
            val uri = uriString.toUri()
            if (uri.scheme == "content") {
                if (DocumentsContract.isTreeUri(uri)) {
                    DocumentFile.fromTreeUri(context, uri)
                } else {
                    DocumentFile.fromSingleUri(context, uri)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting document file for $uriString: ${e.message}")
            null
        }
    }

    fun createDirectory(context: Context, uriString: String, subPath: String): DocumentFile? {
        val baseDocument = getDocumentFile(context, uriString) ?: run {
            Log.e(TAG, "Could not get base document for URI: $uriString")
            return null
        }
        
        try {
            if (!baseDocument.exists()) {
                Log.e(TAG, "Base directory does not exist: ${baseDocument.uri}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking existence of base directory: ${e.message}")
            return null
        }

        // Extra check: try to read the directory to ensure it's physically present
        try {
            if (!baseDocument.canRead()) {
                Log.e(TAG, "Base directory is inaccessible (might be physically deleted): ${baseDocument.uri}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Base directory is inaccessible (might be physically deleted): ${baseDocument.uri}. Error: ${e.message}")
            return null
        }

        return createDirectory(baseDocument, subPath)
    }

    /** The [createDirectory] walk starting from an already-resolved [DocumentFile]. */
    fun createDirectory(base: DocumentFile, subPath: String): DocumentFile? {
        var currentDir = base
        for (part in subPath.split("/").filter { it.isNotEmpty() }) {
            val existingDir = try {
                currentDir.findFile(part)
            } catch (e: Exception) {
                Log.w(TAG, "Error finding sub-directory '$part' in '${currentDir.uri}': ${e.message}")
                null
            }

            currentDir = if (existingDir != null && existingDir.isDirectory) {
                existingDir
            } else {
                try {
                    currentDir.createDirectory(part) ?: run {
                        Log.e(TAG, "Failed to create sub-directory '$part' in '${currentDir.uri}' (returned null)")
                        return null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception creating sub-directory '$part' in '${currentDir.uri}': ${e.message}")
                    return null
                }
            }
        }
        return currentDir
    }

    /** The document at [path] under [root] ("a/b/c"); null when any segment is missing. */
    fun findFile(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            current = try {
                current.findFile(part)
            } catch (e: Exception) {
                Log.w(TAG, "Error finding '$part' under ${current.uri}: ${e.message}")
                null
            } ?: return null
        }
        return current
    }

    /**
     * Reads [file] as UTF-8 text. Throws when it cannot be read: callers that treat a missing
     * file specially must locate it with [findFile] first, so a transient read failure is never
     * mistaken for "no file yet".
     */
    fun readText(context: Context, file: DocumentFile): String =
        context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Could not open ${file.uri}")

    /**
     * Writes [content] to [dir]/[subPath]/[fileName] without a window where the target is
     * missing or truncated: the text goes to a `.tmp` sibling first and the target is only
     * replaced once the temporary holds it all. If the old file cannot be deleted, nothing is
     * replaced; if the provider refuses the rename, the content (still in memory) is written
     * into a fresh target. Throws [IOException] on failure.
     */
    fun writeTextSafely(context: Context, dir: DocumentFile, subPath: String, fileName: String, content: String) {
        val directory = if (subPath.isEmpty()) dir else createDirectory(dir, subPath)
            ?: throw IOException("Could not create directory $subPath")
        val tmpName = "$fileName.tmp"
        runCatching { directory.findFile(tmpName)?.delete() }
        // application/octet-stream keeps the display name untouched (text mimes gain ".txt").
        val tmp = directory.createFile("application/octet-stream", tmpName)
            ?: throw IOException("Could not create $tmpName")
        val bytes = content.toByteArray(Charsets.UTF_8)
        try {
            context.contentResolver.openOutputStream(tmp.uri)?.use { it.write(bytes) }
                ?: throw IOException("Could not write $tmpName")
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            throw e
        }
        val existing = runCatching { directory.findFile(fileName) }.getOrNull()
        if (existing != null && runCatching { existing.delete() }.getOrDefault(false) != true) {
            // Refusing beats renaming next to it: the provider would de-duplicate the name and
            // the frontend would keep reading the untouched original while we report success.
            runCatching { tmp.delete() }
            throw IOException("Could not replace $fileName")
        }
        if (runCatching { tmp.renameTo(fileName) }.getOrDefault(false)) return
        val target = directory.createFile("application/octet-stream", fileName)
            ?: throw IOException("Could not create $fileName")
        context.contentResolver.openOutputStream(target.uri)?.use { it.write(bytes) }
            ?: throw IOException("Could not write $fileName")
        runCatching { tmp.delete() }
    }

    fun createFile(
        context: Context,
        uriString: String,
        subPath: String,
        fileName: String,
        mimeType: String = "application/octet-stream",
        overwrite: Boolean = true
    ): DocumentFile? {
        val directory = createDirectory(context, uriString, subPath) ?: run {
            Log.e(TAG, "Failed to create/access directory for $fileName in $uriString")
            return null
        }
        if (overwrite) {
            try {
                directory.findFile(fileName)?.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Error deleting existing file $fileName: ${e.message}")
            }
        } else {
            try {
                val existing = directory.findFile(fileName)
                if (existing != null) return existing
            } catch (e: Exception) {
                Log.w(TAG, "Error checking for existing file $fileName: ${e.message}")
            }
        }
        return try {
            directory.createFile(mimeType, fileName) ?: run {
                Log.e(TAG, "Failed to create file $fileName in ${directory.uri} (returned null)")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating file $fileName in ${directory.uri}: ${e.message}")
            null
        }
    }

    fun getOutputStream(context: Context, documentFile: DocumentFile): OutputStream? {
        return try {
            context.contentResolver.openOutputStream(documentFile.uri)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening output stream: ${e.message}")
            null
        }
    }

    fun deleteFile(documentFile: DocumentFile?): Boolean {
        return documentFile?.delete() == true
    }

    fun isValidUri(context: Context, uriString: String): Boolean {
        if (uriString.isEmpty()) return false
        val documentFile = getDocumentFile(context, uriString)
        return documentFile != null && documentFile.exists() && documentFile.canWrite()
    }

    /**
     * Free bytes on the volume behind a SAF tree URI (e.g. "primary:ROMs" → shared storage),
     * or null when the volume cannot be resolved to a path.
     */
    fun getFreeBytes(context: Context, uriString: String): Long? {
        return try {
            val uri = uriString.toUri()
            val docId = if (DocumentsContract.isTreeUri(uri)) DocumentsContract.getTreeDocumentId(uri) else return null
            val volumeId = docId.substringBefore(':')
            val dir: File? = if (volumeId == "primary") {
                Environment.getExternalStorageDirectory()
            } else {
                val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                manager.storageVolumes.firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) }
                    ?.let { volume -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) volume.directory else null }
            }
            dir?.let { StatFs(it.path).availableBytes }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading free space for $uriString: ${e.message}")
            null
        }
    }
}
