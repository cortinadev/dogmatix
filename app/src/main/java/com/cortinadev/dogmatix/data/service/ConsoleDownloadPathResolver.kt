package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.cortinadev.dogmatix.data.model.ResolvedDownloadPath
import com.cortinadev.dogmatix.data.model.ResolvedDownloadPath.Source
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ConsoleFolderAliases
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for "where does console X download to?".
 * Priority: custom per-console dir > existing matching folder in the download dir > default folder.
 */
@Singleton
class ConsoleDownloadPathResolver @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    suspend fun resolve(settings: SettingsRepository, consoleId: String): ResolvedDownloadPath =
        resolveAll(settings, listOf(consoleId)).getValue(consoleId)

    suspend fun resolveAll(settings: SettingsRepository, consoleIds: Collection<String>): Map<String, ResolvedDownloadPath> {
        val customDirs = settings.consoleDownloadDirectories.first()
        val downloadDir = settings.downloadDirectory.first()
        val separateByConsole = settings.separateByConsole.first()
        return resolveAll(consoleIds, downloadDir, separateByConsole, customDirs)
    }

    suspend fun resolveAll(
        consoleIds: Collection<String>,
        downloadDir: String,
        separateByConsole: Boolean,
        customDirs: Map<String, String>
    ): Map<String, ResolvedDownloadPath> {
        val rootDisplay = FileParsingUtils.toUserReadablePath(downloadDir)
        val existingFolders: List<String> = if (separateByConsole && downloadDir.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                try {
                    StorageHelper.getDocumentFile(context, downloadDir)
                        ?.listFiles().orEmpty()
                        .filter { it.isDirectory }
                        .mapNotNull { it.name }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        } else emptyList()

        return consoleIds.associateWith { consoleId ->
            val custom = customDirs[consoleId]
            when {
                custom != null -> ResolvedDownloadPath(FileParsingUtils.toUserReadablePath(custom), "", Source.CUSTOM)
                downloadDir.isEmpty() -> ResolvedDownloadPath("", "", Source.UNSET)
                !separateByConsole -> ResolvedDownloadPath(rootDisplay, "", Source.ROOT)
                else -> {
                    val matching = ConsoleFolderAliases.matchingFolders(existingFolders, consoleId)
                    val detected = matching.firstOrNull()
                    if (detected != null) {
                        ResolvedDownloadPath("$rootDisplay/$detected", detected, Source.DETECTED, alternatives = matching.drop(1))
                    } else {
                        val default = FileParsingUtils.sanitizeFolderName(ConsoleFormatter.getConsoleFolderName(consoleId))
                        ResolvedDownloadPath("$rootDisplay/$default", default, Source.WILL_CREATE)
                    }
                }
            }
        }
    }
}
