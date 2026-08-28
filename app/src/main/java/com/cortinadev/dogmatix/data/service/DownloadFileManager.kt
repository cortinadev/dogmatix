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
import com.cortinadev.dogmatix.util.FileParsingUtils
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
    private val pathResolver: ConsoleDownloadPathResolver
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
                var deletedAny = false
                extractedFiles.forEach { extractedFileName ->
                    val fileToDelete = directory.findFile(extractedFileName)
                    if (fileToDelete?.delete() == true) {
                        deletedAny = true
                    }
                }
                return deletedAny
            }

            val archiveFile = directory.findFile(decodedFileName)
            if (archiveFile != null && archiveFile.exists()) {
                return archiveFile.delete()
            }

            false
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getDownloadDirectoryUri(file: DownloadableFileEntity): Uri {
        val uriString = settingsRepository.consoleDownloadDirectories.first()[file.consoleId]
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
        if (consoleRepository.getConsoleById(file.consoleId) == null) {
            val separateByConsole = settingsRepository.separateByConsole.first()
            val hasCustomDir = settingsRepository.consoleDownloadDirectories.first().containsKey(file.consoleId)
            return if (separateByConsole && !hasCustomDir) "Unknown" else ""
        }
        return pathResolver.resolve(settingsRepository, file.consoleId).subPath
    }
}
