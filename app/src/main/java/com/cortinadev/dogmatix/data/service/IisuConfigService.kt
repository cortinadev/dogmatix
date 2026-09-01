package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.cortinadev.dogmatix.util.ApkAssets
import com.cortinadev.dogmatix.util.DgmtxFile
import com.cortinadev.dogmatix.util.IisuJson
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-button iiSU setup: deploys the `.dgmtx` shortcuts and registers Dogmatix as an emulator
 * in iiSU's `emuladores.json`, which lives in shared storage
 * (`Android/media/com.iisulauncher/iiSULauncher/Emuladores/`) and is therefore reachable
 * through SAF, so the whole thing works like the ES-DE setup.
 *
 * The live file is patched when it is there; on a fresh iiSU install the defaults bundled
 * inside its APK are used as the base, so no console definition is lost. Only consoles whose
 * `shortName` matches a folder we deployed a shortcut into are touched, and the entry is
 * appended last: the console keeps its own default emulator and the shortcut is pointed at
 * Dogmatix with iiSU's per-ROM *Override Emulator* (its equivalent of ES-DE's `<altemulator>`).
 *
 * iiSU ships `emuladores.json` as a versioned artifact it can update from its own repository,
 * so applying such an update drops these additions; running the setup again puts them back.
 */
@Singleton
class IisuConfigService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val shortcuts: FrontendShortcutService
) {

    data class Result(
        val shortcutsWritten: Int,
        val shortcutsFailed: Int,
        /** Consoles that now list the shortcut (folder matched an iiSU console). */
        val consoles: Int,
        /** Console folders with no matching iiSU console (nothing written for them). */
        val foldersWithoutConsole: Int
    )

    class IisuNotInstalledException : Exception()

    suspend fun configure(iisuDirUri: String): Result = withContext(Dispatchers.IO) {
        val picked = StorageHelper.getDocumentFile(context, iisuDirUri)
            ?.takeIf { it.isDirectory && it.canWrite() }
            ?: throw FrontendSetupException.FolderNotWritable()
        // The right pick is Android/media/com.iisulauncher/iiSULauncher. A parent picked by
        // mistake would get a fresh Emuladores/ tree iiSU never reads, and a success toast on
        // top — so descend into iiSULauncher when it is there, refuse otherwise.
        val iisuDir = if (picked.name.equals(DATA_DIR_NAME, ignoreCase = true)) {
            picked
        } else {
            StorageHelper.findFile(picked, DATA_DIR_NAME)?.takeIf { it.isDirectory }
                ?: throw FrontendSetupException.WrongFolder()
        }

        val deployed = shortcuts.deployShortcuts()
        // A console's iiSU `shortName` is its folder name, so only shortcuts that live in a
        // per-console subfolder can be matched at all.
        if (deployed.folders.isEmpty()) throw FrontendSetupException.NoConsoleFolders()

        val live = StorageHelper.findFile(iisuDir, "$EMULATORS_DIR/$EMULATORS_FILE")?.takeIf { it.isFile }
        val base = if (live != null) {
            // A file that exists but cannot be read must abort: falling through to the bundled
            // defaults would replace the user's whole configuration with factory settings.
            try {
                StorageHelper.readText(context, live)
            } catch (e: Exception) {
                throw FrontendSetupException.ConfigUnreadable(e)
            }
        } else {
            // Fresh iiSU that has not extracted its defaults yet: start from the APK's copy.
            ApkAssets.readText(context, IISU_PACKAGE, BUNDLED_EMULATORS_ENTRY)
                ?: throw IisuNotInstalledException()
        }

        val patch = try {
            IisuJson.patchEmuladores(base, deployed.folders, DgmtxFile.launchComponent(context.packageName))
        } catch (e: RuntimeException) {
            throw FrontendSetupException.ConfigCorrupt(e)
        }
        if (patch.changed) StorageHelper.writeTextSafely(context, iisuDir, EMULATORS_DIR, EMULATORS_FILE, patch.content)

        Result(
            shortcutsWritten = deployed.written,
            shortcutsFailed = deployed.failed,
            consoles = patch.configured.size,
            foldersWithoutConsole = patch.missing.size
        )
    }

    companion object {
        /** Must stay listed in the manifest `<queries>` or the APK fallback reads nothing. */
        const val IISU_PACKAGE = "com.iisulauncher"

        /** iiSU's data folder inside `Android/media/com.iisulauncher/` — what the user picks. */
        const val DATA_DIR_NAME = "iiSULauncher"

        private const val EMULATORS_DIR = "Emuladores"
        private const val EMULATORS_FILE = "emuladores.json"
        private const val BUNDLED_EMULATORS_ENTRY = "assets/emuladores_default.json"
    }
}
