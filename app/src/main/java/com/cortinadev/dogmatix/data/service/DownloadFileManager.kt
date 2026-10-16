package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ActionTypes
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.MultiDisc
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadFileManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val consoleRepository: ConsoleRepository,
    private val pathResolver: ConsoleDownloadPathResolver,
    // coleccion-gestion: deletions go to the trash; smart storage may route a download elsewhere.
    private val history: ActionHistoryService,
    private val storageRouter: StorageRouter
) {

    fun createDownloadItem(file: DownloadableFileEntity): DownloadItemModel {
        return DownloadItemModel(
            name = file.name,
            fileName = file.fileName,
            downloadSpeed = 0f,
            progress = 0f,
            status = DownloadStatus.DOWNLOADING,
            downloadedBytes = 0L,
            fileSize = file.fileSize,
            startedAt = System.currentTimeMillis()
        )
    }

    fun createDocumentFile(
        file: DownloadableFileEntity,
        downloadDirectoryUri: String,
        subPath: String
    ): DocumentFile? {
        val decodedFileName = FileParsingUtils.decodeUrlEncodedFileName(file.fileName)
        return StorageHelper.createFile(
            context = context,
            uriString = downloadDirectoryUri,
            subPath = subPath,
            fileName = decodedFileName,
            mimeType = "application/octet-stream",
            overwrite = true
        )
    }

    /** The partially written file of a previous attempt, if any. */
    fun findExistingFile(file: DownloadableFileEntity, downloadDirectoryUri: String, subPath: String): DocumentFile? {
        val name = FileParsingUtils.decodeUrlEncodedFileName(file.fileName)
        return runCatching { StorageHelper.createDirectory(context, downloadDirectoryUri, subPath)?.findFile(name)?.takeIf { it.isFile } }.getOrNull()
    }

    fun getAppendOutputStream(documentFile: DocumentFile): java.io.OutputStream? =
        runCatching { context.contentResolver.openOutputStream(documentFile.uri, "wa") }.getOrNull()

    fun getOutputStream(documentFile: DocumentFile): java.io.OutputStream? {
        return StorageHelper.getOutputStream(context, documentFile)
    }

    suspend fun deleteFile(documentFile: DocumentFile): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                StorageHelper.deleteFile(documentFile)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun deleteFileByName(
        file: DownloadableFileEntity,
        deleteFile: Boolean = false,
        extractedFiles: List<String> = emptyList()
    ): Boolean {
        if (!deleteFile) return true

        return try {
            val downloadDirectoryUri = getDownloadDirectoryUri(file)
            val subPath = getSubPath(file)
            val decodedFileName = FileParsingUtils.decodeUrlEncodedFileName(file.fileName)

            val directory = StorageHelper.createDirectory(
                context = context,
                uriString = downloadDirectoryUri.toString(),
                subPath = subPath
            ) ?: return false

            if (extractedFiles.isNotEmpty()) {
                // Extracted entries are paths relative to the console folder ("Disc 1/track01.bin");
                // a disc may since have moved into its "<game>.m3u" folder.
                val toDelete = extractedFiles.mapNotNull { extractedPath ->
                    StorageHelper.findFile(directory, extractedPath)?.takeIf { it.isFile }
                        ?: inDiscSet(directory, extractedPath.substringAfterLast('/'))
                }
                val deletedAny = toTrash(file, toDelete)
                // Then the folders the extraction created, deepest first, once nothing is left in them.
                val folders = extractedFiles.flatMap { path ->
                    val parts = path.split('/').dropLast(1)
                    parts.indices.map { i -> parts.take(i + 1).joinToString("/") }
                }.distinct().sortedByDescending { it.count { c -> c == '/' } }
                folders.forEach { folder ->
                    StorageHelper.findFile(directory, folder)?.takeIf { it.isDirectory && it.listFiles().isEmpty() }?.delete()
                }
                return deletedAny
            }

            val archiveFile = directory.findFile(decodedFileName) ?: inDiscSet(directory, decodedFileName)
            if (archiveFile != null && archiveFile.exists()) {
                // A partial download (stopped or failed) is not worth keeping in the trash.
                val complete = file.fileSize <= 0 || archiveFile.isDirectory || archiveFile.length() >= file.fileSize
                return if (complete) toTrash(file, listOf(archiveFile)) else archiveFile.delete()
            }

            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * coleccion-gestion: [docs] go to the trash as one history entry (Undo brings them back);
     * whatever the trash cannot take is deleted for good, as before.
     */
    private suspend fun toTrash(file: DownloadableFileEntity, docs: List<DocumentFile>): Boolean {
        if (docs.isEmpty()) return false
        val requests = docs.map { TrashRequest(it.uri, it.parentFile?.uri) }
        val result = runCatching { history.trash(requests, ActionTypes.DOWNLOAD_DELETE, file.name) }.getOrNull()
        val leftovers = result?.failed?.map { it.uri }?.toSet() ?: requests.map { it.uri }.toSet()
        val deleted = docs.filter { it.uri in leftovers }.count { runCatching { it.delete() }.getOrDefault(false) }
        storageRouter.forget(file.fileName)
        return (result?.moved ?: 0) + deleted > 0
    }

    /** A disc already gathered into its `<game>.m3u/` folder (see MultiDiscService). */
    private fun inDiscSet(directory: DocumentFile, fileName: String): DocumentFile? {
        val disc = MultiDisc.discOf(fileName) ?: return null
        return directory.findFile("${disc.setStem}.m3u")?.takeIf { it.isDirectory }?.findFile(fileName)
    }

    suspend fun getDownloadDirectoryUri(file: DownloadableFileEntity): Uri {
        // coleccion-gestion: wait out a library move; smart storage may send the file to the other volume.
        storageRouter.awaitIdle()
        val uriString = storageRouter.redirectFor(file)?.rootUri
            ?: settingsRepository.consoleDownloadDirectories.first()[file.consoleId]
            ?: settingsRepository.downloadDirectory.first()

        if (uriString.isEmpty()) return Uri.EMPTY

        val uri = uriString.toUri()
        try {
            val df = DocumentFile.fromTreeUri(context, uri)
            if (df == null || !df.exists() || !df.canWrite()) {
                Log.e("DownloadFileManager", "Tree URI is no longer valid: $uriString")
                return Uri.EMPTY
            }
        } catch (e: Exception) {
            Log.e("DownloadFileManager", "Error validating Tree URI: ${e.message}")
            return Uri.EMPTY
        }

        return uri
    }

    suspend fun getSubPath(file: DownloadableFileEntity): String {
        storageRouter.redirectFor(file)?.let { return it.subPath }
        if (consoleRepository.getConsoleById(file.consoleId) == null) {
            val separateByConsole = settingsRepository.separateByConsole.first()
            val hasCustomDir = settingsRepository.consoleDownloadDirectories.first().containsKey(file.consoleId)
            return if (separateByConsole && !hasCustomDir) "Unknown" else ""
        }
        return pathResolver.resolve(settingsRepository, file.consoleId).subPath
    }
}
