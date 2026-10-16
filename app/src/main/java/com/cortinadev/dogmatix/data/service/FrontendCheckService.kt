package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.model.ResolvedDownloadPath
import com.cortinadev.dogmatix.data.model.ResolvedDownloadPath.Source
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ApkAssets
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.DgmtxFile
import com.cortinadev.dogmatix.util.FrontendCheck
import com.cortinadev.dogmatix.util.FrontendCheck.Folder
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads what the frontend setups wrote (ES-DE's and iiSU's folders, the console folders) and
 * hands it to [FrontendCheck] for "Check frontends" in Settings. Read only: repairs go through
 * the setups themselves ([EsdeConfigService], [IisuConfigService], [FrontendShortcutService]).
 */
@Singleton
class FrontendCheckService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val consoleRepository: ConsoleRepository,
    private val pathResolver: ConsoleDownloadPathResolver
) {

    suspend fun check(): List<FrontendCheck.Finding> = withContext(Dispatchers.IO) {
        val consoles = consoleRepository.getAllConsoles().first()
        val downloadDir = settingsRepository.downloadDirectory.first()
        val customDirs = settingsRepository.consoleDownloadDirectories.first()
        val resolved = pathResolver.resolveAll(consoles.map { it.id }, downloadDir, settingsRepository.separateByConsole.first(), customDirs)
        // Same folder list the setups match against frontend systems (see FrontendShortcutService).
        val folders = consoles.mapNotNull { resolved.getValue(it.id).subPath.takeIf(String::isNotEmpty)?.lowercase() }.distinct()
        val component = DgmtxFile.launchComponent(context.packageName)
        esde(folders, component) + iisu(folders, component) + shortcuts(consoles, resolved, downloadDir, customDirs)
    }

    private suspend fun esde(folders: List<String>, component: String): List<FrontendCheck.Finding> {
        val uri = settingsRepository.esdeDirectory.first()
        val installed = ApkAssets.isInstalled(context, EsdeConfigService.ESDE_PACKAGE)
        val folder = folderState(uri)
        val root = FrontendFolders.root(context, uri)
        if (folder != Folder.OK || root == null) {
            return FrontendCheck.evaluateEsde(FrontendCheck.EsdeInput(installed, if (folder == Folder.OK) Folder.NO_ACCESS else folder, component))
        }
        val custom = readQuietly(root, "custom_systems/es_systems.xml")
        val bundled = if (installed) ApkAssets.readText(context, EsdeConfigService.ESDE_PACKAGE, EsdeConfigService.BUNDLED_SYSTEMS_ENTRY) else null
        val systems = FrontendCheck.esdeSystems(folders, bundled, custom)
        val names = systems.values.toSet()

        val gamelistDirs = FrontendFolders.find(context, root, "gamelists")?.let { FrontendFolders.children(context, it.uri) }.orEmpty()
        // One gamelist in memory at a time: scraped ones run to megabytes.
        val listed = names.filterTo(HashSet()) { system ->
            gamelistDirs[system]?.let { readQuietly(it.uri, "gamelist.xml") }?.let(FrontendCheck::listsShortcut) == true
        }
        val media = FrontendFolders.find(context, root, "downloaded_media")?.let { FrontendFolders.children(context, it.uri) }.orEmpty()
        val covers = names.filterTo(HashSet()) { system ->
            media[system]?.let { FrontendFolders.find(context, it.uri, "covers/${DgmtxFile.SHORTCUT_NAME}.png") } != null
        }
        return FrontendCheck.evaluateEsde(
            FrontendCheck.EsdeInput(
                installed = installed,
                folder = Folder.OK,
                component = component,
                findRules = readQuietly(root, "custom_systems/es_find_rules.xml"),
                customSystems = custom,
                systems = systems,
                listed = listed,
                covers = covers
            )
        )
    }

    private suspend fun iisu(folders: List<String>, component: String): List<FrontendCheck.Finding> {
        val uri = settingsRepository.iisuDirectory.first()
        val installed = ApkAssets.isInstalled(context, IisuConfigService.IISU_PACKAGE)
        var folder = folderState(uri)
        var dataDir: Uri? = null
        if (folder == Folder.OK) {
            // Same rule as the setup: the iiSULauncher folder itself, or its parent.
            val picked = StorageHelper.getDocumentFile(context, uri)
            dataDir = when {
                picked == null -> null
                picked.name.equals(IisuConfigService.DATA_DIR_NAME, ignoreCase = true) -> picked.uri
                else -> FrontendFolders.find(context, picked.uri, IisuConfigService.DATA_DIR_NAME)?.takeIf { it.isDirectory }?.uri
            }
            if (dataDir == null) folder = Folder.WRONG
        }
        // "" = there but unreadable, which the check reports like a damaged file.
        val emuladores = dataDir?.let { dir ->
            runCatching { FrontendFolders.readIfPresent(context, dir, "${IisuConfigService.EMULATORS_DIR}/${IisuConfigService.EMULATORS_FILE}") }
                .getOrDefault("")
        }
        return FrontendCheck.evaluateIisu(FrontendCheck.IisuInput(installed, folder, component, emuladores, folders))
    }

    private fun shortcuts(
        consoles: List<ConsoleEntity>,
        resolved: Map<String, ResolvedDownloadPath>,
        downloadDir: String,
        customDirs: Map<String, String>
    ): List<FrontendCheck.Finding> {
        val rootUri = FrontendFolders.root(context, downloadDir)
        val rootFolders = rootUri?.let { FrontendFolders.children(context, it) }.orEmpty()
        val listings = HashMap<Uri, Set<String>>()
        fun filesIn(dir: Uri): Set<String> = listings.getOrPut(dir) {
            StorageHelper.listChildren(context, dir).filter { !it.isDirectory }.mapTo(HashSet()) { it.name }
        }
        val shortcuts = consoles.mapNotNull { console ->
            val path = resolved.getValue(console.id)
            val shortName = ConsoleFormatter.getConsoleShortName(console.id)
            val present = when (path.source) {
                Source.UNSET -> return@mapNotNull null
                Source.ROOT -> rootUri?.let { DgmtxFile.rootShortcutFile(shortName) in filesIn(it) } == true
                Source.CUSTOM -> customDirs[console.id]?.let { FrontendFolders.root(context, it) }
                    ?.let { DgmtxFile.SHORTCUT_FILE in filesIn(it) } == true
                Source.DETECTED, Source.WILL_CREATE -> rootFolders[path.subPath.lowercase()]?.takeIf { it.isDirectory }
                    ?.let { DgmtxFile.SHORTCUT_FILE in filesIn(it.uri) } == true
            }
            FrontendCheck.Shortcut(shortName, present)
        }
        return listOf(FrontendCheck.evaluateShortcuts(downloadDir.isNotBlank() || customDirs.isNotEmpty(), shortcuts))
    }

    private fun folderState(uri: String): Folder = when {
        uri.isBlank() -> Folder.NOT_SET
        !FrontendFolders.isAccessible(context, uri) -> Folder.NO_ACCESS
        else -> Folder.OK
    }

    /** A file the check cannot read counts as missing: the setup that repairs it reports why. */
    private fun readQuietly(dir: Uri, path: String): String? =
        runCatching { FrontendFolders.readIfPresent(context, dir, path) }.getOrNull()
}
