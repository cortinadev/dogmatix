package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.RommPlatformMapper
import com.cortinadev.dogmatix.util.RommSource
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RommUploadService"
private const val CHUNK_SIZE = 8 * 1024 * 1024
private const val ATTEMPTS = 2

enum class UploadStatus { UPLOADING, DONE, FAILED }

data class UploadState(val status: UploadStatus, val progress: Float = 0f, val message: String = "")

/**
 * Pushes finished downloads to the RomM server (Settings → RomM). Observes the downloads list
 * the way [LibraryIndexService] does, so [DownloadService] needs no RomM knowledge; the state
 * lives here and the Downloads screen shows it next to each row.
 */
@Singleton
class RommUploadService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val downloadService: DownloadService,
    private val downloadableFileDao: DownloadableFileDao,
    private val downloadFileManager: DownloadFileManager,
    private val rommClient: RommClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLock = Mutex()

    private val _uploads = MutableStateFlow<Map<String, UploadState>>(emptyMap())
    /** Upload state per download file name; absent = nothing to show. */
    val uploads: StateFlow<Map<String, UploadState>> = _uploads.asStateFlow()

    init {
        // Fires once per download, only when the file is really in place (see DownloadService.finished).
        scope.launch {
            downloadService.finished.collect { fileName ->
                if (settingsRepository.rommAutoUpload.first()) launch { enqueue(fileName) }
            }
        }
    }

    fun hasActive(): Boolean = _uploads.value.values.any { it.status == UploadStatus.UPLOADING }

    fun retry(fileName: String) {
        scope.launch { enqueue(fileName) }
    }

    private suspend fun enqueue(fileName: String) {
        val file = downloadService.entityFor(fileName) ?: downloadableFileDao.getFileByFileName(fileName) ?: return
        if (RommSource.isDownloadFrom(rommClient.configuredBaseUrl(), file.downloadUrl)) return   // it came from RomM
        val platformId = settingsRepository.rommPlatformMap.first()[file.consoleId]
        if (platformId == null) {
            Log.i(TAG, "No RomM platform mapped for ${file.consoleId}; skipping ${file.fileName}")
            return
        }
        val names = downloadService.uploadCandidates(fileName)
        _uploads.update { it + (fileName to UploadState(UploadStatus.UPLOADING)) }
        uploadLock.withLock {
            val result = runCatching { uploadAll(file, names, platformId) }
            _uploads.update {
                it + (fileName to result.fold(
                    onSuccess = { UploadState(UploadStatus.DONE, 1f) },
                    onFailure = { e -> Log.w(TAG, "RomM upload failed for $fileName: ${e.message}"); UploadState(UploadStatus.FAILED, message = e.message.orEmpty()) }
                ))
            }
        }
    }

    private suspend fun uploadAll(file: DownloadableFileEntity, names: List<String>, platformId: Int) {
        val dirUri = downloadFileManager.getDownloadDirectoryUri(file)
        if (dirUri == android.net.Uri.EMPTY) throw RommException("Download directory not accessible")
        val directory = StorageHelper.createDirectory(context, dirUri.toString(), downloadFileManager.getSubPath(file))
            ?: throw RommException("Could not open the download folder")
        val docs = names.mapNotNull { directory.findFile(it) }.filter { it.isFile }
        if (docs.isEmpty()) throw RommException("File not found on disk: ${names.joinToString()}")

        val totalBytes = docs.sumOf { it.length() }
        var sent = 0L
        docs.forEach { doc ->
            val name = doc.name ?: return@forEach
            var lastError: Throwable? = null
            for (attempt in 0 until ATTEMPTS) {
                try {
                    if (attempt > 0) delay(3_000L)
                    uploadOne(doc.uri, name, doc.length(), platformId) { done ->
                        _uploads.update { it + (file.fileName to UploadState(UploadStatus.UPLOADING, (sent + done).toFloat() / totalBytes.coerceAtLeast(1))) }
                    }
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e
                    // "already exists" means a previous attempt did land: nothing to redo.
                    if (e is JsonHttp.HttpException && e.code == 400 && e.body?.contains("already exists") == true) { lastError = null; break }
                }
            }
            lastError?.let { throw it }
            sent += doc.length()
        }
    }

    private suspend fun uploadOne(uri: android.net.Uri, name: String, size: Long, platformId: Int, onProgress: (Long) -> Unit) {
        val chunks = RommPlatformMapper.chunkCount(size, CHUNK_SIZE)
        val uploadId = rommClient.uploadStart(platformId, name, size, chunks)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(CHUNK_SIZE)
                var index = 0
                var done = 0L
                while (index < chunks) {
                    var filled = 0
                    while (filled < buffer.size) {
                        val n = input.read(buffer, filled, buffer.size - filled)
                        if (n < 0) break
                        filled += n
                    }
                    rommClient.uploadChunk(uploadId, index, buffer, filled)
                    done += filled
                    onProgress(done)
                    index++
                    if (filled < buffer.size) break
                }
            } ?: throw RommException("Could not read $name")
            rommClient.uploadComplete(uploadId)
            Log.i(TAG, "Uploaded $name to RomM platform $platformId")
        } catch (e: Exception) {
            rommClient.uploadCancel(uploadId)
            throw e
        }
    }
}
