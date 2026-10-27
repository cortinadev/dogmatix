package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ActionTypes
import com.cortinadev.dogmatix.util.DownloadPartials
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.LibraryMoveRules
import com.cortinadev.dogmatix.util.MultiDisc
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
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

    // A transfer is written as `<name>.dogmatix-part` and renamed once complete ([promote]): an
    // unfinished file never looks like a game (✓ in the library, the Collection analysis, the
    // frontends), and a file fetched again replaces the old copy only when the new one is whole.

    /** The name a download has on disk once complete. */
    fun finalName(file: DownloadableFileEntity): String = FileParsingUtils.decodeUrlEncodedFileName(file.fileName)

    /** A new, empty partial for [file] (a leftover one is replaced). */
    fun createPartFile(file: DownloadableFileEntity, downloadDirectoryUri: String, subPath: String): DocumentFile? {
        val directory = StorageHelper.createDirectory(context, downloadDirectoryUri, subPath) ?: return null
        val name = LibraryMoveRules.partName(finalName(file))
        fileNamed(StorageHelper.listChildren(context, directory.uri), name)?.let { runCatching { it.delete() } }
        return runCatching { directory.createFile("application/octet-stream", name) }.getOrNull()
    }

    /**
     * The partial of a previous attempt, for a transfer that has a resume record ([expectedTotal]:
     * the length it recorded, null or negative = unknown). Before 1.4 a transfer was written under
     * its final name: one shorter than the recorded length is carried on where it is, and one of
     * exactly that length is a transfer that ended before it was processed (it is used as it is).
     * A final file next to a partial is the old copy of a file fetched again, and is left alone.
     */
    fun findPartialFile(file: DownloadableFileEntity, downloadDirectoryUri: String, subPath: String, expectedTotal: Long?): DocumentFile? =
        runCatching {
            val directory = StorageHelper.createDirectory(context, downloadDirectoryUri, subPath) ?: return@runCatching null
            val children = StorageHelper.listChildren(context, directory.uri)
            fileNamed(children, LibraryMoveRules.partName(finalName(file)))
                ?: fileNamed(children, finalName(file))?.takeIf { DownloadPartials.underFinalName(it.length(), expectedTotal) != null }
        }.getOrNull()

    /** The file called [name] among [children] (one provider query for the folder; `findFile` costs one per entry). */
    private fun fileNamed(children: List<StorageHelper.Child>, name: String): DocumentFile? =
        children.firstOrNull { it.name == name && !it.isDirectory }?.let { DocumentFile.fromSingleUri(context, it.uri) }

    /**
     * Renames the complete [part] to [file]'s final name, replacing an older copy (a file fetched
     * again). A provider that cannot rename gets a copy instead. Returns the file under its final name.
     */
    suspend fun promote(part: DocumentFile, file: DownloadableFileEntity, downloadDirectoryUri: String, subPath: String): DocumentFile =
        withContext(Dispatchers.IO) {
            val name = finalName(file)
            if (part.name == name) return@withContext part
            val directory = StorageHelper.createDirectory(context, downloadDirectoryUri, subPath)
                ?: throw IOException("Could not open the folder of $name")
            fileNamed(StorageHelper.listChildren(context, directory.uri), name)?.let { old ->
                if (!runCatching { old.delete() }.getOrDefault(false)) throw IOException("Could not replace $name")
            }
            val renamed = runCatching { DocumentsContract.renameDocument(context.contentResolver, part.uri, name) }.getOrNull()
                ?.let { DocumentFile.fromSingleUri(context, it) }
            if (renamed != null && renamed.name == name) return@withContext renamed
            // The provider cannot rename (or kept another name): copy into place, then drop the partial.
            val source = renamed ?: part
            val target = directory.createFile("application/octet-stream", name)
                ?: throw IOException("Could not create $name")
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                context.contentResolver.openOutputStream(target.uri)?.use { input.copyTo(it, 1 shl 20) }
                    ?: throw IOException("Could not write $name")
            } ?: throw IOException("Could not read the partial of $name")
            runCatching { source.delete() }
            target
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

    /**
     * The files of a download whose row is deleted. What an unfinished one left goes, for good (it
     * is not worth the trash), and so does its storage redirect: its partial, and the file under
     * the final name that its resume record covers (a pre-1.4 partial, or an archive that came
     * down whole and failed or was stopped while extracting, see [DownloadPartials]).
     * [unfinishedRecordTotal]: the length that record holds, null when the row completed or has
     * no record. The finished file of a completed row goes only with [deleteFile] (to the trash).
     */
    suspend fun deleteDownloadFiles(
        file: DownloadableFileEntity,
        deleteFile: Boolean = false,
        extractedFiles: List<String> = emptyList(),
        unfinishedRecordTotal: Long? = null
    ): Boolean {
        return try {
            val downloadDirectoryUri = getDownloadDirectoryUri(file)
            if (downloadDirectoryUri == Uri.EMPTY) return false
            val subPath = getSubPath(file)
            val decodedFileName = FileParsingUtils.decodeUrlEncodedFileName(file.fileName)

            val directory = StorageHelper.createDirectory(
                context = context,
                uriString = downloadDirectoryUri.toString(),
                subPath = subPath
            ) ?: return false

            val children = StorageHelper.listChildren(context, directory.uri)
            val part = fileNamed(children, LibraryMoveRules.partName(decodedFileName))
            val legacy = if (part == null && unfinishedRecordTotal != null) {
                fileNamed(children, decodedFileName)?.takeIf {
                    DownloadPartials.deleteWithUnfinishedRow(it.length(), unfinishedRecordTotal, hasResumeRecord = true)
                }
            } else null
            val partial = part ?: legacy
            if (partial != null) {
                runCatching { partial.delete() }
                storageRouter.forget(file.fileName)
            }
            if (!deleteFile) return partial != null
            if (legacy != null) return true

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
