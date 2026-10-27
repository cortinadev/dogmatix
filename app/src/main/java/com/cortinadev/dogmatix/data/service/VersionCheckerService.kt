package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.FileProvider
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.AppRelease
import com.cortinadev.dogmatix.util.AppUpdates
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Where the update stands; Settings and the update dialog draw it. */
sealed interface UpdateState {
    /** Nothing known yet in this process (no check, no release kept from an earlier one). */
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    /** [fraction] is null while the size is unknown. */
    data class Downloading(val release: AppRelease, val fraction: Float?, val bytes: Long) : UpdateState
    data class Ready(val release: AppRelease, val file: File) : UpdateState
    /** [release] is null when the check itself failed. */
    data class Failed(val release: AppRelease?, val error: UpdateError) : UpdateState
}

/** [OLDER]: the APK downloaded is not newer than the installed app. */
enum class UpdateError { NETWORK, BAD_APK, SIGNATURE, STORAGE, OLDER }

/**
 * New versions: checks the GitHub releases of the fork (on start, at most every 6 h, and on
 * demand from Settings), downloads the APK into the cache and hands it to the system installer,
 * which asks the user to confirm (and, the first time, to allow installs from Dogmatix).
 */
@Singleton
class VersionCheckerService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {

    companion object {
        private const val TAG = "VersionChecker"
        private const val REQUEST_TIMEOUT = 10_000 // 10 seconds
        private const val UPDATES_DIR = "updates"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The update dialog is open: the startup notice, or the user asked for it. */
    private val _dialogOpen = MutableStateFlow(false)
    val dialogOpen: StateFlow<Boolean> = _dialogOpen.asStateFlow()

    /** The user asked to update: the installer opens as soon as the download is ready. */
    private val _installPending = MutableStateFlow(false)
    val installPending: StateFlow<Boolean> = _installPending.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    val currentVersion: String by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: "1.0"
    }

    /** On app start: the release kept from the last check, then a new check when one is due. */
    fun start() {
        scope.launch {
            val autoCheck = settingsRepository.updateAutoCheck.first()
            val known = AppUpdates.decode(settingsRepository.updateLatest.first())
            if (known != null && AppUpdates.isNewer(known.version, currentVersion)) {
                _state.value = UpdateState.Available(known)
            } else {
                // Updated (or nothing newer): the APK that installed it is no longer needed.
                updatesDir().deleteRecursively()
            }
            if (autoCheck && AppUpdates.checkDue(System.currentTimeMillis(), settingsRepository.updateCheckedAt.first())) {
                check(manual = false)?.join()
            }
            if (autoCheck) {
                val release = (_state.value as? UpdateState.Available)?.release
                if (AppUpdates.shouldPrompt(release, currentVersion, settingsRepository.updateDismissed.first())) {
                    _dialogOpen.value = true
                }
            }
        }
    }

    /** "Check for updates" in Settings: a release found opens the dialog. */
    fun checkNow() {
        check(manual = true)
    }

    private fun check(manual: Boolean): Job? {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return checkJob
        val before = _state.value
        if (manual) _state.value = UpdateState.Checking
        return scope.launch {
            val answer = fetchLatest()
            if (answer == null) {
                // A failed automatic check leaves things as they were; a manual one says so.
                _state.value = if (manual) UpdateState.Failed(null, UpdateError.NETWORK) else before
                return@launch
            }
            val release = answer.getOrNull()
            settingsRepository.setUpdateCheckResult(release?.let(AppUpdates::encode).orEmpty(), System.currentTimeMillis())
            if (release != null && AppUpdates.isNewer(release.version, currentVersion)) {
                _state.value = UpdateState.Available(release)
                if (manual) _dialogOpen.value = true
            } else {
                _state.value = UpdateState.UpToDate
                _dialogOpen.value = false
            }
        }.also { checkJob = it }
    }

    /**
     * The latest release: success(null) when GitHub has none with an APK (or answers 404),
     * null when it could not be asked.
     */
    private fun fetchLatest(): Result<AppRelease?>? = runCatching {
        val connection = URL(AppUpdates.LATEST_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = REQUEST_TIMEOUT
            connection.readTimeout = REQUEST_TIMEOUT
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Dogmatix/$currentVersion")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Result.success(AppUpdates.parseRelease(connection.inputStream.bufferedReader().use { it.readText() }))
                HttpURLConnection.HTTP_NOT_FOUND -> Result.success(null)
                else -> throw IOException("HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.w(TAG, "Update check failed: ${it.message}") }.getOrNull()

    fun openDialog() {
        _dialogOpen.value = true
    }

    /**
     * The dialog closes. "Later" on the startup notice is remembered for that version; a download
     * in progress goes on and the dialog comes back when it is ready.
     */
    fun closeDialog() {
        _dialogOpen.value = false
        val current = _state.value
        if (current is UpdateState.Available) {
            scope.launch { settingsRepository.setUpdateDismissed(current.release.version) }
        }
        if (current !is UpdateState.Downloading) _installPending.value = false
    }

    /** "Update": downloads the APK (or reuses one already downloaded) and installs it when ready. */
    fun download() {
        val release = when (val current = _state.value) {
            is UpdateState.Available -> current.release
            is UpdateState.Failed -> current.release ?: return
            is UpdateState.Ready -> {
                _installPending.value = true
                return
            }
            else -> return
        }
        if (downloadJob?.isActive == true) return
        _installPending.value = true
        _state.value = UpdateState.Downloading(release, if (release.apkSize > 0) 0f else null, 0L)
        downloadJob = scope.launch {
            val file = File(updatesDir(), safeName(release))
            val result = runCatching {
                if (!(file.isFile && verify(file, release) == null)) fetchApk(release, file)
                verify(file, release)
            }
            _state.value = result.fold(
                onSuccess = { error ->
                    if (error == null) UpdateState.Ready(release, file)
                    else {
                        file.delete()
                        UpdateState.Failed(release, error)
                    }
                },
                onFailure = { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Update download failed: ${e.message}")
                    UpdateState.Failed(release, if (e is IOException && e.message?.contains("ENOSPC") == true) UpdateError.STORAGE else UpdateError.NETWORK)
                }
            )
            // The dialog comes back with the result if it was hidden during the download.
            if (_installPending.value) _dialogOpen.value = true
        }
    }

    fun cancelDownload() {
        val current = _state.value as? UpdateState.Downloading ?: return
        val job = downloadJob ?: return
        _installPending.value = false
        // After the job has stopped: a block read in flight would draw "Downloading" again.
        scope.launch {
            job.cancelAndJoin()
            _state.value = UpdateState.Available(current.release)
            updatesDir().listFiles()?.forEach { it.delete() }
        }
    }

    /** The system installer is up: the dialog is done (cancelling there leaves "Install" in Settings). */
    fun onInstallerOpened() {
        _installPending.value = false
        _dialogOpen.value = false
    }

    /** Opens the system installer on [file] (needs REQUEST_INSTALL_PACKAGES; Android asks the user to allow it once). */
    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private suspend fun fetchApk(release: AppRelease, file: File) = withContext(Dispatchers.IO) {
        val dir = updatesDir().apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, file.name + ".part")
        val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = REQUEST_TIMEOUT
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "Dogmatix/$currentVersion")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            var done = 0L
            var shown = -1
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != shown) {
                            shown = percent
                            _state.value = UpdateState.Downloading(release, if (total > 0) done.toFloat() / total else null, done)
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Truncated: $done of $total bytes")
            if (!part.renameTo(file)) throw IOException("Could not rename ${part.name}")
        } finally {
            connection.disconnect()
            part.delete()
        }
    }

    /**
     * The file is a Dogmatix APK of the expected size, newer than this app and, when it updates
     * this very package, signed like it (Android would refuse it after the download otherwise).
     * A debug build downloads the release, which is another package: only the name and the
     * version are checked there. The rules are [AppUpdates.checkApk].
     */
    private fun verify(file: File, release: AppRelease): UpdateError? {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.path, flags)
        val ours = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull()
        val apk = AppUpdates.ApkFacts(
            fileSize = file.length(),
            packageName = archive?.packageName,
            versionName = archive?.versionName,
            versionCode = archive?.longVersionCode ?: 0L,
            signers = archive?.signingInfo?.apkContentsSigners?.mapTo(HashSet()) { it.toCharsString() }
        )
        val installed = AppUpdates.InstalledApp(
            packageName = context.packageName,
            versionName = currentVersion,
            versionCode = ours?.longVersionCode ?: 0L,
            signers = ours?.signingInfo?.apkContentsSigners?.mapTo(HashSet()) { it.toCharsString() }
        )
        return when (AppUpdates.checkApk(release.apkSize, apk, installed)) {
            null -> null
            AppUpdates.ApkProblem.SIZE, AppUpdates.ApkProblem.NOT_AN_APK, AppUpdates.ApkProblem.OTHER_APP -> UpdateError.BAD_APK
            AppUpdates.ApkProblem.NOT_NEWER -> UpdateError.OLDER
            AppUpdates.ApkProblem.SIGNATURE -> UpdateError.SIGNATURE
        }
    }

    private fun updatesDir() = File(context.cacheDir, UPDATES_DIR)

    private fun safeName(release: AppRelease): String =
        release.apkName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "dogmatix-${release.version}.apk" }
}
