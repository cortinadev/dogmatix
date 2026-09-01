package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.cortinadev.dogmatix.data.model.ResolvedDownloadPath
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.DgmtxFile
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a `Dogmatix.dgmtx` shortcut into every console's download folder, so frontends
 * (ES-DE, Daijishō…) that scan those folders can offer "open this platform in Dogmatix"
 * as if it were a game. Folders that don't exist yet are created, like a download would.
 */
@Singleton
class FrontendShortcutService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val consoleRepository: ConsoleRepository,
    private val pathResolver: ConsoleDownloadPathResolver
) {

    data class Result(
        val written: Int,
        val failed: Int,
        val skipped: Int,
        /**
         * Distinct per-console folder names (lowercased) the consoles resolve to — what the
         * frontend setups match against their systems, so they don't re-resolve everything.
         * Root and custom folders resolve to no per-console name and are not listed.
         */
        val folders: List<String> = emptyList()
    )

    suspend fun deployShortcuts(): Result = withContext(Dispatchers.IO) {
        val consoles = consoleRepository.getAllConsoles().first()
        val customDirs = settingsRepository.consoleDownloadDirectories.first()
        val downloadDir = settingsRepository.downloadDirectory.first()
        val separateByConsole = settingsRepository.separateByConsole.first()
        val resolved = pathResolver.resolveAll(consoles.map { it.id }, downloadDir, separateByConsole, customDirs)

        var written = 0
        var failed = 0
        var skipped = 0
        for (console in consoles) {
            val path = resolved.getValue(console.id)
            val baseUri = customDirs[console.id] ?: downloadDir
            if (path.source == ResolvedDownloadPath.Source.UNSET || baseUri.isEmpty()) {
                skipped++
                continue
            }
            // With "separate by console" off every console shares the root folder, so the
            // file name has to carry the console; otherwise the plain name reads best in lists.
            val fileName = if (path.source == ResolvedDownloadPath.Source.ROOT) {
                "★ Search for more ${ConsoleFormatter.getConsoleShortName(console.id)} games....${DgmtxFile.EXTENSION}"
            } else {
                "${DgmtxFile.SHORTCUT_NAME}.${DgmtxFile.EXTENSION}"
            }
            val ok = runCatching {
                val doc = StorageHelper.createFile(
                    context = context,
                    uriString = baseUri,
                    subPath = path.subPath,
                    fileName = fileName,
                    // Not text/plain: SAF appends the mime type's extension to the display
                    // name ("Dogmatix.dgmtx.txt"); octet-stream keeps the name untouched.
                    mimeType = "application/octet-stream",
                    overwrite = true
                ) ?: return@runCatching false
                StorageHelper.getOutputStream(context, doc)?.use { stream ->
                    stream.write(DgmtxFile.contentForConsole(console.id, console.name).toByteArray(Charsets.UTF_8))
                    true
                } ?: false
            }.getOrDefault(false)
            if (ok) written++ else failed++
        }
        val folders = consoles.mapNotNull { resolved.getValue(it.id).subPath.takeIf(String::isNotEmpty)?.lowercase() }.distinct()
        Result(written, failed, skipped, folders)
    }
}
