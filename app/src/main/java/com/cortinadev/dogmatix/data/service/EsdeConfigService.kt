package com.cortinadev.dogmatix.data.service

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.util.ApkAssets
import com.cortinadev.dogmatix.util.DgmtxFile
import com.cortinadev.dogmatix.util.EsdeXml
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-button ES-DE setup: deploys the `.dgmtx` shortcuts and then writes everything ES-DE
 * needs into its application data directory (chosen once via SAF): the DOGMATIX find rule,
 * a `custom_systems/es_systems.xml` override per platform (based on the installed ES-DE's own
 * bundled definitions, read from its APK, so no emulator entry is lost or outdated), a
 * gamelist entry with `<altemulator>` so only the shortcut launches Dogmatix, and the banner
 * as the entry's cover art. Existing files are merged, never truncated; ES-DE should be
 * closed while this runs (it rewrites gamelists on exit) and restarted afterwards.
 */
@Singleton
class EsdeConfigService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val shortcuts: FrontendShortcutService
) {

    data class Result(
        val shortcutsWritten: Int,
        val shortcutsFailed: Int,
        /** Systems that now list the shortcut (folder matched an ES-DE system). */
        val systems: Int,
        /** Console folders with no matching ES-DE system (nothing written for them). */
        val foldersWithoutSystem: Int
    )

    class EsdeNotInstalledException : Exception()

    suspend fun configure(esdeDirUri: String): Result = withContext(Dispatchers.IO) {
        val bundled = ApkAssets.readText(context, ESDE_PACKAGE, BUNDLED_SYSTEMS_ENTRY)
            ?: throw EsdeNotInstalledException()
        val esdeDir = StorageHelper.getDocumentFile(context, esdeDirUri)
            ?.takeIf { it.isDirectory && it.canWrite() }
            ?: throw FrontendSetupException.FolderNotWritable()

        val deployed = shortcuts.deployShortcuts()
        // Folder name == ES-DE system name only when the shortcut sits inside the shared ROM
        // tree (DETECTED/WILL_CREATE); root and custom folders are not scanned by ES-DE.
        if (deployed.folders.isEmpty()) throw FrontendSetupException.NoConsoleFolders()

        // 1. Find rule: this build's package + the activity's fixed class name.
        EsdeXml.findRulesWithDogmatix(
            readExisting(esdeDir, "custom_systems/es_find_rules.xml"),
            DgmtxFile.launchComponent(context.packageName)
        )?.let { StorageHelper.writeTextSafely(context, esdeDir, "custom_systems", "es_find_rules.xml", it) }

        // 2. System overrides.
        val patch = EsdeXml.patchCustomSystems(readExisting(esdeDir, "custom_systems/es_systems.xml"), bundled, deployed.folders)
        if (patch.changed) StorageHelper.writeTextSafely(context, esdeDir, "custom_systems", "es_systems.xml", patch.content)

        // 3. Per system: gamelist entry + banner as cover art.
        val shortcutFile = "${DgmtxFile.SHORTCUT_NAME}.${DgmtxFile.EXTENSION}"
        for (system in patch.configured) {
            EsdeXml.gamelistWithShortcut(
                readExisting(esdeDir, "gamelists/$system/gamelist.xml"),
                shortcutFile,
                DgmtxFile.SHORTCUT_NAME
            )?.let { StorageHelper.writeTextSafely(context, esdeDir, "gamelists/$system", "gamelist.xml", it) }
            copyBannerIfMissing(esdeDir, esdeDirUri, "downloaded_media/$system/covers")
        }

        Result(
            shortcutsWritten = deployed.written,
            shortcutsFailed = deployed.failed,
            systems = patch.configured.size,
            foldersWithoutSystem = patch.missing.size
        )
    }

    /** Whether ES-DE is installed on this device (the automatic setup needs its APK). */
    fun isEsdeInstalled(): Boolean = ApkAssets.isInstalled(context, ESDE_PACKAGE)

    /**
     * Text of an existing file; null only when it is genuinely absent. A file that exists but
     * cannot be read aborts the setup instead — treating it as absent would hand the mergers a
     * `null` and truncate the user's file to our minimal version on the next write.
     */
    private fun readExisting(root: DocumentFile, path: String): String? {
        val file = StorageHelper.findFile(root, path)?.takeIf { it.isFile } ?: return null
        return try {
            StorageHelper.readText(context, file)
        } catch (e: Exception) {
            throw FrontendSetupException.ConfigUnreadable(e)
        }
    }

    /** Copies the bundled banner as the shortcut's cover, keeping any art already there. */
    private fun copyBannerIfMissing(esdeDir: DocumentFile, esdeDirUri: String, coversPath: String) {
        val name = "${DgmtxFile.SHORTCUT_NAME}.png"
        if (StorageHelper.findFile(esdeDir, "$coversPath/$name") != null) return
        val doc = StorageHelper.createFile(context, esdeDirUri, coversPath, name, "image/png", overwrite = false) ?: return
        runCatching {
            context.assets.open(BANNER_ASSET).use { input ->
                StorageHelper.getOutputStream(context, doc)?.use { input.copyTo(it) }
            }
        }
    }

    companion object {
        /** Must stay listed in the manifest `<queries>` or the bundled systems read nothing. */
        const val ESDE_PACKAGE = "org.es_de.frontend"
        private const val BUNDLED_SYSTEMS_ENTRY = "assets/systems/android/es_systems.xml"
        private const val BANNER_ASSET = "frontend/banner.png"
    }
}
