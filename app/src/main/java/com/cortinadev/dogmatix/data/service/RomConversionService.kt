package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ActionState
import com.cortinadev.dogmatix.util.ActionTypes
import com.cortinadev.dogmatix.util.ArchiveExtraction
import com.cortinadev.dogmatix.util.CollectionHealth
import com.cortinadev.dogmatix.util.ConversionFormat
import com.cortinadev.dogmatix.util.DiskFile
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.LibraryKeys
import com.cortinadev.dogmatix.util.LibraryMoveRules
import com.cortinadev.dogmatix.util.MultiDisc
import com.cortinadev.dogmatix.util.NoSavingRecord
import com.cortinadev.dogmatix.util.NoSavingRecords
import com.cortinadev.dogmatix.util.RomConversions
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.StorageProblem
import com.cortinadev.dogmatix.util.StorageProblems
import com.cortinadev.dogmatix.util.UndoKinds
import com.cortinadev.dogmatix.util.VersionGrouping
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Where a conversion was asked from: it decides what happens to the original (see [RomConversionService]). */
enum class ConversionOrigin {
    /** Downloads: the original goes to the trash, one history entry undoes the conversion. */
    DOWNLOADS,
    /** Collection → Free up space: the original is deleted for good, like everything that tool frees. */
    FREE_SPACE
}

enum class ConversionState {
    QUEUED, CONVERTING, VERIFYING, FINISHING, DONE, FAILED, CANCELLED;

    val active: Boolean get() = this == QUEUED || this == CONVERTING || this == VERIFYING || this == FINISHING
}

/** Why a conversion did not end in a converted file. */
enum class ConversionError {
    /** The file is no longer where it was. */
    NOT_FOUND,
    ALREADY_CONVERTED,
    UNRECOGNISED,
    DAMAGED,
    /** A track its sheet names is missing. */
    INCOMPLETE,
    NO_SPACE,
    /** The folder cannot be written (card out, grant lost); [ConversionJob.problem] says which. */
    STORAGE,
    /** The output did not match its input: it was deleted, the original is untouched. */
    VERIFY_FAILED,
    /** The original could not be put away (or the output could not take its name): both stay as they were. */
    RETIRE_FAILED,
    /** The converted copy saved too little against the input ([RomConversions.savesSpace]): deleted, the original untouched. */
    NO_SAVING,
    UNKNOWN
}

/** A file of a conversion target: its name in its folder, its document URI and size. */
data class ConversionDoc(val name: String, val uri: String, val size: Long)

/**
 * What to convert: [main] and the files it names ([companions]), in the folder [parentUri] of the
 * SAF tree [treeUri]. [downloadFileName] ties it to a row of Downloads.
 */
data class ConversionTarget(
    val title: String,
    val consoleId: String?,
    val systemNames: Set<String>,
    val format: ConversionFormat,
    val main: ConversionDoc,
    val companions: List<ConversionDoc>,
    val parentUri: String,
    val treeUri: String,
    val downloadFileName: String? = null
) {
    /** A conversion is keyed by its main file. */
    val id: String get() = main.uri
    val inputBytes: Long get() = main.size.coerceAtLeast(0) + companions.sumOf { it.size.coerceAtLeast(0) }
    val outputName: String get() = RomConversions.outputName(format, main.name)
}

/** One conversion, queued, running or over, as the screens show it. */
data class ConversionJob(
    val id: String,
    val title: String,
    val format: ConversionFormat,
    val origin: ConversionOrigin,
    val downloadFileName: String?,
    val consoleId: String?,
    val state: ConversionState,
    /** Of the current phase (converting, then verifying), 0..1. */
    val progress: Float = 0f,
    val inputBytes: Long,
    val outputBytes: Long? = null,
    val error: ConversionError? = null,
    val problem: StorageProblem? = null
) {
    val savedBytes: Long? get() = RomConversions.savedBytes(inputBytes, outputBytes)

    /**
     * Converted and found to save nothing: not offered again (Downloads, Free up space). Remembered
     * across restarts ([NoSavingRecords]) while the file and the converter's profile stay the same.
     */
    val savesNothing: Boolean get() = state == ConversionState.FAILED && error == ConversionError.NO_SAVING
}

/**
 * Converts ROMs, one at a time, with the converters bound into the set ([RomConverter]): XISO
 * here; CHD and RVZ plug in the same way.
 *
 * Each conversion writes `<output>.dogmatix-part` next to the input (never over anything), has the
 * converter check it, and only then puts the original away: to the trash, then the part takes its
 * final name (an XISO keeps the ISO's name, so the original has to leave first). Anything that
 * fails on the way leaves the original where it was and the part deleted. From Downloads the
 * trash keeps the original and the history entry undoes the whole conversion ([UndoKinds.CONVERSION]);
 * from Free up space the trash batch is emptied at once, so the space is freed as that tool promises.
 * Afterwards the library's ✓ (same name, or same base name for a new extension), the Collection
 * analysis of that console, the playlists next to it and the download's list of extracted files
 * are brought up to date.
 *
 * ISOs of 4–8 GB take minutes: [ConversionForegroundService] keeps the process (and the CPU) alive
 * while anything is queued, the same way downloads are kept, so leaving the app or turning the
 * screen off does not kill a conversion halfway.
 */
@Singleton
class RomConversionService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    converterSet: Set<@JvmSuppressWildcards RomConverter>,
    private val trashService: TrashService,
    private val history: ActionHistoryService,
    private val downloadService: DownloadService,
    private val downloadFileManager: DownloadFileManager,
    private val libraryIndex: dagger.Lazy<LibraryIndexService>,
    private val libraryScan: dagger.Lazy<LibraryScanService>,
    private val settings: SettingsRepository
) : UndoHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val converters: List<RomConverter> = converterSet.sortedBy { it.format.ordinal }

    /** Formats this build can convert to. */
    val available: Set<ConversionFormat> = converters.mapTo(LinkedHashSet()) { it.format }

    private val _jobs = MutableStateFlow<Map<String, ConversionJob>>(emptyMap())
    /** Every conversion of this process by id (the main file's URI); finished ones stay until replaced. */
    val jobs: StateFlow<Map<String, ConversionJob>> = _jobs.asStateFlow()

    private val _estimates = MutableStateFlow<Map<String, ConversionInspection>>(emptyMap())
    /** What [requestEstimates] found per [estimateKey]: convertible, and the output size when known. */
    val estimates: StateFlow<Map<String, ConversionInspection>> = _estimates.asStateFlow()

    private val queue = Channel<String>(Channel.UNLIMITED)
    private val requests = ConcurrentHashMap<String, Pair<ConversionTarget, ConversionOrigin>>()
    @Volatile private var runningId: String? = null
    @Volatile private var runningJob: Job? = null

    private val estimateQueue = Channel<ConversionTarget>(Channel.UNLIMITED)
    private val estimateAsked = ConcurrentHashMap.newKeySet<String>()

    /**
     * When each download was last converted from Downloads and not undone, by the history: after
     * a restart an XISO (same name, same extension) is otherwise indistinguishable from its ISO.
     */
    val convertedDownloads: StateFlow<Map<String, Long>> = history.entries
        .map { entries ->
            entries.asSequence()
                .filter { it.type == ActionTypes.CONVERSION && it.undoKind == UndoKinds.CONVERSION && ActionState.of(it.state) != ActionState.UNDONE }
                .mapNotNull { entry -> entry.undoData?.let(::parse)?.download?.let { it to entry.createdAt } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, times) -> times.max() }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** The part file of the conversion running now, noted on disk: a process killed halfway leaves it behind. */
    private val partMarker = java.io.File(context.noBackupFilesDir, "conversion_part.txt")

    /** The files found to save nothing ([NoSavingRecords]); null until read from [noSavingFile]. */
    private val noSaving = MutableStateFlow<List<NoSavingRecord>?>(null)
    private val noSavingFile = java.io.File(context.noBackupFilesDir, "conversion_no_saving.json")
    private val noSavingLock = Mutex()

    init {
        scope.launch { loadNoSaving() }
        // The records that apply with the current CHD compression become FAILED · NO_SAVING jobs,
        // the same ones a conversion of this session leaves: the screens need nothing else.
        scope.launch {
            combine(noSaving.filterNotNull(), settings.chdCompression) { records, chd ->
                NoSavingRecords.applying(records) { NoSavingRecords.profileOf(it, chd.name) }
            }.collect(::showNoSaving)
        }
        // Downloaded again: the file is new. Row deleted: nothing left to offer it on.
        scope.launch { downloadService.finished.collect { name -> changeNoSaving { NoSavingRecords.forgetDownloads(it, setOf(name)) } } }
        scope.launch {
            var seen: Set<String>? = null
            downloadService.downloads.collect { rows ->
                val now = rows.mapTo(HashSet()) { it.fileName }
                val gone = seen?.minus(now).orEmpty()
                seen = now
                if (gone.isNotEmpty() && noSaving.value.orEmpty().any { it.downloadFileName in gone }) {
                    changeNoSaving { NoSavingRecords.forgetDownloads(it, gone) }
                }
            }
        }
        scope.launch {
            deleteOrphanPart()
            for (id in queue) {
                val (target, origin) = requests.remove(id) ?: continue
                if (_jobs.value[id]?.state != ConversionState.QUEUED) continue
                val job = scope.launch { run(target, origin) }
                runningId = id
                runningJob = job
                job.join()
                runningId = null
                runningJob = null
            }
        }
        scope.launch {
            for (target in estimateQueue) {
                val key = estimateKey(target)
                val inspection = runCatching { inspect(target) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrElse { ConversionInspection(NotConvertible.UNRECOGNISED) }
                _estimates.update { it + (key to inspection) }
            }
        }
    }

    fun converterFor(systemNames: Set<String>, fileName: String): RomConverter? =
        converters.firstOrNull { it.supports(systemNames, fileName) }

    /** The format a file converts to in this build, by names only (no reading). */
    fun formatFor(systemNames: Set<String>, fileName: String): ConversionFormat? = converterFor(systemNames, fileName)?.format

    // ---- Downloads -----------------------------------------------------------------------------

    /**
     * The format a completed download would convert to, by names: its file or what it unpacked to
     * (as far as this process knows), or its archive's likely content. Cheap: for the legend and
     * the row's button; [targetForDownload] decides on disk.
     */
    fun likelyFormatForDownload(fileName: String): ConversionFormat? {
        val names = RomConversions.namesFor(downloadService.entityFor(fileName)?.consoleId ?: return null)
        downloadService.uploadCandidates(fileName).forEach { candidate -> formatFor(names, candidate)?.let { return it } }
        return RomConversions.likelyFormat(names, FileParsingUtils.decodeUrlEncodedFileName(fileName))?.takeIf { it in available }
    }

    /**
     * The file on disk behind the completed download [fileName], ready to convert; null when it
     * cannot be found or converts to nothing. Looks at what it unpacked to, the file itself and,
     * for an archive unpacked in an earlier session, the files named like it.
     */
    suspend fun targetForDownload(fileName: String): ConversionTarget? = withContext(Dispatchers.IO) {
        val entity = downloadService.entityFor(fileName) ?: return@withContext null
        val root = downloadFileManager.getDownloadDirectoryUri(entity).takeIf { it != Uri.EMPTY } ?: return@withContext null
        val subPath = downloadFileManager.getSubPath(entity)
        var folder = SafDocs.docUri(root.toString()) ?: return@withContext null
        for (part in subPath.split('/').filter { it.isNotEmpty() }) {
            folder = SafDocs.child(context, folder, part)?.takeIf { it.isDirectory }?.uri ?: return@withContext null
        }
        val names = RomConversions.namesFor(entity.consoleId, subPath.substringAfterLast('/'))
        val decoded = FileParsingUtils.decodeUrlEncodedFileName(fileName)
        val wanted = downloadService.uploadCandidates(fileName) +
            // An archive unpacked before a restart: its content is named like it.
            listOf(decoded)
        // Each path where the download left it, then in the "<game>.m3u/" folder MultiDiscService
        // gathers the discs of a multi-disc game into.
        for (path in wanted.flatMap(RomConversions::placesOf).distinct()) {
            val dir = path.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.fold(folder as Uri?) { at, part ->
                at?.let { SafDocs.child(context, it, part)?.takeIf { d -> d.isDirectory }?.uri }
            } ?: continue
            val doc = SafDocs.child(context, dir, path.substringAfterLast('/'))?.takeIf { !it.isDirectory } ?: continue
            targetIn(dir, doc, names, entity.consoleId, root.toString(), fileName)?.let { return@withContext it }
        }
        val base = LibraryKeys.baseName(decoded.substringAfterLast('/').lowercase(Locale.ROOT))
        val setFolder = RomConversions.discSetFolder(decoded)?.let { name ->
            SafDocs.child(context, folder, name)?.takeIf { it.isDirectory }?.uri
        }
        for (dir in listOfNotNull(folder, setFolder)) {
            val files = SafDocs.list(context, dir).orEmpty().filter { !it.isDirectory && !LibraryMoveRules.isPart(it.name) }
            val found = files.filter { LibraryKeys.baseName(it.name.lowercase(Locale.ROOT)) == base }
                .firstNotNullOfOrNull { targetIn(dir, it, names, entity.consoleId, root.toString(), fileName) }
                // An archive whose image is named otherwise ("Halo (USA).zip" → "Halo.iso"): the one
                // convertible file of the same game in the folder; with two or more it is not guessed.
                // By the row's own title (titleKey), not its game key: since 1.5 a clone list can put
                // translated releases under one groupKey, and their images are not this download's.
                ?: files.filter { formatFor(names, it.name) != null && RomConversions.sameTitle(it.name, entity.titleKey.ifEmpty { VersionGrouping.groupKey(entity.name) }) }
                    .singleOrNull()?.let { targetIn(dir, it, names, entity.consoleId, root.toString(), fileName) }
            if (found != null) return@withContext found
        }
        null
    }

    private fun targetIn(dir: Uri, doc: SafDocs.Doc, names: Set<String>, consoleId: String?, treeUri: String, downloadFileName: String?): ConversionTarget? {
        val format = formatFor(names, doc.name) ?: return null
        val companions = sheetCompanions(dir, doc)
        return ConversionTarget(
            title = MultiDisc.stripExtension(doc.name), consoleId = consoleId, systemNames = names, format = format,
            main = ConversionDoc(doc.name, doc.uri.toString(), doc.size), companions = companions,
            parentUri = dir.toString(), treeUri = treeUri, downloadFileName = downloadFileName
        )
    }

    /** The tracks a `.cue` / `.gdi` names in its own folder (nothing for other files). */
    private fun sheetCompanions(dir: Uri, doc: SafDocs.Doc): List<ConversionDoc> {
        val ext = doc.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext != "cue" && ext != "gdi" || doc.size > MAX_SHEET) return emptyList()
        val text = runCatching { context.contentResolver.openInputStream(doc.uri)?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull() ?: return emptyList()
        val refs = RomConversions.trackNames(ext, text)
        return SafDocs.list(context, dir).orEmpty().filter { !it.isDirectory && it.name.lowercase(Locale.ROOT) in refs }
            .map { ConversionDoc(it.name, it.uri.toString(), it.size) }
    }

    // ---- Collection ----------------------------------------------------------------------------

    /**
     * The file of the analysis [file] as a conversion target (its sheet's tracks from [inFolder],
     * the files of its game); null when it converts to nothing in this build.
     */
    fun targetFor(file: DiskFile, inFolder: List<DiskFile>): ConversionTarget? {
        val names = RomConversions.namesFor(file.consoleId, file.topFolder)
        val format = formatFor(names, file.name) ?: return null
        val refs = file.references.orEmpty().mapNotNull { CollectionHealth.resolve(file.folderPath, it)?.lowercase(Locale.ROOT) }.toSet()
        val companions = inFolder.filter { it.parentUri == file.parentUri && it.uri != file.uri && it.path.lowercase(Locale.ROOT) in refs }
            .map { ConversionDoc(it.name, it.uri, it.size) }
        return ConversionTarget(
            title = MultiDisc.stripExtension(file.name), consoleId = file.consoleId, systemNames = names, format = format,
            main = ConversionDoc(file.name, file.uri, file.size), companions = companions,
            parentUri = file.parentUri, treeUri = file.rootUri
        )
    }

    fun estimateKey(target: ConversionTarget): String = "${target.main.uri}|${target.main.size}"

    /** Inspects, in the background and one at a time, the targets not inspected yet; results land in [estimates]. */
    fun requestEstimates(targets: List<ConversionTarget>) {
        for (target in targets) if (estimateAsked.add(estimateKey(target))) estimateQueue.trySend(target)
    }

    // ---- Running -------------------------------------------------------------------------------

    /** Reads [target] with its converter: whether it converts, and the output size when cheap. */
    suspend fun inspect(target: ConversionTarget): ConversionInspection {
        val converter = converters.firstOrNull { it.format == target.format } ?: return ConversionInspection(NotConvertible.UNRECOGNISED)
        return converter.inspect(inputOf(target))
    }

    /** Queues [targets]; one already queued or running is left alone. The foreground service keeps them going. */
    fun enqueue(targets: List<ConversionTarget>, origin: ConversionOrigin) {
        var added = false
        for (target in targets) {
            val id = target.id
            if (_jobs.value[id]?.state?.active == true) continue
            requests[id] = target to origin
            _jobs.update {
                it + (id to ConversionJob(id, target.title, target.format, origin, target.downloadFileName, target.consoleId, ConversionState.QUEUED, inputBytes = target.inputBytes))
            }
            queue.trySend(id)
            added = true
        }
        if (added) startForeground()
    }

    /** Stops a queued or running conversion; the original stays, the part file goes. */
    fun cancel(id: String) {
        if (runningId == id) {
            runningJob?.cancel()
            return
        }
        if (requests.remove(id) != null) setState(id) { it.copy(state = ConversionState.CANCELLED) }
    }

    fun cancelAll() {
        requests.keys.toList().forEach(::cancel)
        runningJob?.cancel()
    }

    /** Whether anything is queued or running (the foreground service stays while it is). */
    val busy: Boolean get() = _jobs.value.values.any { it.state.active }

    private fun startForeground() {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, ConversionForegroundService::class.java))
        }.onFailure { Log.w(TAG, "Could not start the conversion service: ${it.message}") }
    }

    private fun setState(id: String, change: (ConversionJob) -> ConversionJob) =
        _jobs.update { jobs -> jobs[id]?.let { jobs + (id to change(it)) } ?: jobs }

    private fun fileOf(doc: ConversionDoc): ConversionFile {
        val uri = doc.uri.toUri()
        return ConversionFile(doc.name, uri, doc.size) { mode ->
            context.contentResolver.openFileDescriptor(uri, mode) ?: throw IOException("Cannot open ${doc.name}")
        }
    }

    private fun inputOf(target: ConversionTarget) = ConversionInput(target.systemNames, fileOf(target.main), target.companions.map(::fileOf))

    private class Failed(val error: ConversionError, val problem: StorageProblem? = null, message: String) : Exception(message)

    private suspend fun run(target: ConversionTarget, origin: ConversionOrigin) {
        val id = target.id
        setState(id) { it.copy(state = ConversionState.CONVERTING, progress = 0f) }
        val parent = target.parentUri.toUri()
        val partName = LibraryMoveRules.partName(target.outputName)
        var part: Uri? = null
        // The converter reads it as it starts too: what a "saves nothing" is remembered under.
        val profile = runCatching { NoSavingRecords.profileOf(target.format, settings.chdCompression.first().name) }.getOrDefault("")
        try {
            val converter = converters.firstOrNull { it.format == target.format } ?: throw Failed(ConversionError.UNKNOWN, message = "No converter")
            val now = SafDocs.stat(context, target.main.uri.toUri())
            if (now == null || now.size != target.main.size) throw Failed(ConversionError.NOT_FOUND, message = "${target.main.name} is gone or changed")
            SafDocs.problemOf(context, target.treeUri)?.let { throw Failed(ConversionError.STORAGE, it, "Folder not writable") }
            val input = inputOf(target)
            val inspection = converter.inspect(input)
            inspection.reason?.let { throw Failed(errorOf(it), message = "Not convertible: $it") }
            inspection.outputBytes?.let { estimate ->
                if (!RomConversions.savesSpace(target.inputBytes, estimate)) throw Failed(ConversionError.NO_SAVING, message = "Would be $estimate bytes from ${target.inputBytes}")
            }
            val needed = inspection.outputBytes ?: input.totalBytes
            if (!ArchiveExtraction.fits(needed, StorageHelper.getFreeBytes(context, target.treeUri))) {
                throw Failed(ConversionError.NO_SPACE, StorageProblem.NO_SPACE, "Needs $needed bytes")
            }
            SafDocs.child(context, parent, partName)?.let { SafDocs.delete(context, it.uri) }
            part = SafDocs.createFile(context, parent, partName) ?: throw IOException("Cannot create $partName")
            val partUri = part
            runCatching { partMarker.writeText(partUri.toString()) }
            val opener: (String) -> android.os.ParcelFileDescriptor = { mode ->
                context.contentResolver.openFileDescriptor(partUri, mode) ?: throw IOException("Cannot open $partName")
            }
            val check = converter.convert(input, ConversionOutput(target.outputName, ConversionFile(partName, partUri, -1, opener))) { p ->
                setState(id) { it.copy(progress = p.coerceIn(0f, 1f)) }
            }
            val written = SafDocs.stat(context, partUri)?.size ?: -1
            // As big as the input or bigger: not worth it. The part goes (finally), the original stays, no history.
            if (!RomConversions.savesSpace(target.inputBytes, written)) {
                throw Failed(ConversionError.NO_SAVING, message = "$written bytes from ${target.inputBytes}: no space saved")
            }
            setState(id) { it.copy(state = ConversionState.VERIFYING, progress = 0f) }
            val ok = check.verify(ConversionFile(partName, partUri, written, opener)) { p -> setState(id) { it.copy(progress = p.coerceIn(0f, 1f)) } }
            if (!ok) throw Failed(ConversionError.VERIFY_FAILED, message = "The converted file did not match")
            setState(id) { it.copy(state = ConversionState.FINISHING, progress = 1f) }
            val placed = withContext(NonCancellable) { retireAndPlace(target, origin, partUri, written) }
            part = null
            setState(id) { it.copy(state = ConversionState.DONE, progress = 1f, outputBytes = written) }
            if (noSaving.value.orEmpty().any { it.uri == id }) changeNoSaving { NoSavingRecords.forgetUri(it, id) }
            withContext(NonCancellable) { afterConversion(target, placed) }
        } catch (e: CancellationException) {
            setState(id) { it.copy(state = ConversionState.CANCELLED) }
            throw e
        } catch (e: Failed) {
            Log.w(TAG, "Conversion of ${target.main.name} failed: ${e.message}")
            setState(id) { it.copy(state = ConversionState.FAILED, error = e.error, problem = e.problem) }
            if (e.error == ConversionError.NO_SAVING) withContext(NonCancellable) { rememberNoSaving(target, origin, profile) }
        } catch (e: ConversionException) {
            Log.w(TAG, "Conversion of ${target.main.name} failed: ${e.message}")
            setState(id) { it.copy(state = ConversionState.FAILED, error = errorOf(e.reason)) }
        } catch (e: Exception) {
            Log.w(TAG, "Conversion of ${target.main.name} failed", e)
            val problem = StorageProblems.classify(e) ?: SafDocs.problemOf(context, target.treeUri)
            val error = when (problem) {
                null -> ConversionError.UNKNOWN
                StorageProblem.NO_SPACE -> ConversionError.NO_SPACE
                else -> ConversionError.STORAGE
            }
            setState(id) { it.copy(state = ConversionState.FAILED, error = error, problem = problem) }
        } finally {
            part?.let { leftover -> withContext(NonCancellable) { SafDocs.delete(context, leftover) } }
            runCatching { partMarker.delete() }
            // The file is the same after one that saved nothing: its estimate stands, so Free up
            // space keeps its row (locked, with the reason) instead of reading it again.
            if (_jobs.value[id]?.savesNothing != true) {
                _estimates.update { it - estimateKey(target) }
                estimateAsked.remove(estimateKey(target))
            }
        }
    }

    // ---- Saves nothing, across restarts ----------------------------------------------------------

    /** Reads the records and drops those whose file (or one of its tracks) is gone or changed size (and writes back what is left). */
    private suspend fun loadNoSaving() = noSavingLock.withLock {
        val read = runCatching { noSavingFile.takeIf { it.exists() }?.readText() }.getOrNull()?.let(NoSavingRecords::decode).orEmpty()
        val valid = read.filter { record ->
            NoSavingRecords.stillValid(record) { uri ->
                runCatching { SafDocs.stat(context, uri.toUri())?.takeIf { !it.isDirectory }?.size }.getOrNull()
            }
        }
        if (valid.size != read.size) writeNoSaving(valid)
        noSaving.value = valid
    }

    private suspend fun changeNoSaving(change: (List<NoSavingRecord>) -> List<NoSavingRecord>) = noSavingLock.withLock {
        val before = noSaving.value ?: return@withLock
        val after = change(before)
        if (after == before) return@withLock
        noSaving.value = after
        writeNoSaving(after)
    }

    private fun writeNoSaving(records: List<NoSavingRecord>) {
        runCatching {
            if (records.isEmpty()) noSavingFile.delete()
            else {
                val tmp = java.io.File(noSavingFile.parentFile, noSavingFile.name + ".tmp")
                tmp.writeText(NoSavingRecords.encode(records))
                if (!tmp.renameTo(noSavingFile)) { noSavingFile.writeText(tmp.readText()); tmp.delete() }
            }
        }.onFailure { Log.w(TAG, "Could not save the conversions that save nothing: ${it.message}") }
    }

    private suspend fun rememberNoSaving(target: ConversionTarget, origin: ConversionOrigin, profile: String) {
        val record = NoSavingRecord(
            uri = target.id, mainBytes = target.main.size, inputBytes = target.inputBytes, format = target.format, profile = profile,
            title = target.title, consoleId = target.consoleId, downloadFileName = target.downloadFileName, origin = origin.name,
            at = System.currentTimeMillis(),
            companions = target.companions.associate { it.uri to it.size }
        )
        // Still loading (a conversion right at start): wait for the list rather than drop the record.
        noSaving.filterNotNull().first()
        changeNoSaving { NoSavingRecords.add(it, record) }
    }

    /**
     * [applying] as FAILED · NO_SAVING jobs: added where nothing else of the file is on show (a
     * conversion queued, running or done wins), and those no longer applying (profile changed,
     * file forgotten) removed, so the file is offered again.
     */
    private fun showNoSaving(applying: Map<String, NoSavingRecord>) {
        _jobs.update { jobs ->
            val kept = jobs.filterValues { job -> !job.savesNothing || job.id in applying }
            kept + applying.filterKeys { id -> kept[id]?.let { it.state.active || it.state == ConversionState.DONE || it.savesNothing } != true }
                .mapValues { (id, r) ->
                    ConversionJob(
                        id, r.title, r.format, ConversionOrigin.entries.firstOrNull { it.name == r.origin } ?: ConversionOrigin.DOWNLOADS,
                        r.downloadFileName, r.consoleId, ConversionState.FAILED, inputBytes = r.inputBytes, error = ConversionError.NO_SAVING
                    )
                }
        }
    }

    /** The part a conversion cut short by the process dying left (see [partMarker]): deleted once, at the first start. */
    private fun deleteOrphanPart() {
        val uri = runCatching { partMarker.takeIf { it.exists() }?.readText()?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        runCatching {
            val doc = SafDocs.stat(context, uri.toUri())
            // Only a part: once renamed to its final name the URI no longer names it, or names the converted file.
            if (doc != null && !doc.isDirectory && LibraryMoveRules.isPart(doc.name)) {
                SafDocs.delete(context, doc.uri)
                Log.i(TAG, "Deleted the part of an interrupted conversion: ${doc.name}")
            }
        }
        runCatching { partMarker.delete() }
    }

    private fun errorOf(reason: NotConvertible): ConversionError = when (reason) {
        NotConvertible.ALREADY_CONVERTED -> ConversionError.ALREADY_CONVERTED
        NotConvertible.UNRECOGNISED -> ConversionError.UNRECOGNISED
        NotConvertible.DAMAGED -> ConversionError.DAMAGED
        NotConvertible.INCOMPLETE -> ConversionError.INCOMPLETE
    }

    /**
     * The checked [part] takes the original's place: the originals to the trash, then the part
     * renamed to the output name. If either step fails, what went to the trash comes back and the
     * caller deletes the part. From Free up space the trash batch is then emptied for good.
     * Returns the converted file's URI.
     */
    private suspend fun retireAndPlace(target: ConversionTarget, origin: ConversionOrigin, part: Uri, outputBytes: Long): Uri {
        val parent = target.parentUri.toUri()
        val originals = (listOf(target.main) + target.companions).map { TrashRequest(it.uri.toUri(), parent) }
        val trashed = trashService.moveToTrash(originals)
        if (trashed.failed.isNotEmpty()) {
            if (trashed.moved > 0) trashService.restore(trashed.batchId)
            throw Failed(ConversionError.RETIRE_FAILED, SafDocs.problemOf(context, target.treeUri), message = "The original could not be put away")
        }
        val placed = SafDocs.rename(context, part, target.outputName)
        if (placed == null || SafDocs.stat(context, placed)?.name != target.outputName) {
            // Back under the part name (so the caller deletes it) and the originals back in place.
            if (placed != null) SafDocs.delete(context, placed)
            trashService.restore(trashed.batchId)
            throw Failed(ConversionError.RETIRE_FAILED, message = "${target.outputName} could not take its name")
        }
        val saved = RomConversions.savedBytes(target.inputBytes, outputBytes) ?: 0L
        runCatching {
            when (origin) {
                ConversionOrigin.DOWNLOADS -> history.record(
                    ActionTypes.CONVERSION, target.title, originals.size, saved, UndoKinds.CONVERSION,
                    undoData(trashed.batchId, placed, target)
                )
                ConversionOrigin.FREE_SPACE -> {
                    trashService.purge(listOf(trashed.batchId))
                    history.record(ActionTypes.CONVERSION, target.title, originals.size, saved)
                }
            }
        }.onFailure { Log.w(TAG, "Could not record the conversion of ${target.main.name}: ${it.message}") }
        return placed
    }

    /** Playlists, the download's file list, the ✓ and the analysis follow the converted file. */
    private suspend fun afterConversion(target: ConversionTarget, placed: Uri) {
        val oldName = target.main.name
        val newName = target.outputName
        if (!oldName.equals(newName, ignoreCase = false)) {
            runCatching { rewritePlaylists(target.parentUri.toUri(), oldName, newName) }
                .onFailure { Log.w(TAG, "Could not update the playlists of $oldName: ${it.message}") }
            target.downloadFileName?.let { name ->
                downloadService.replaceExtracted(name, (listOf(target.main) + target.companions).map { it.name }.toSet(), newName)
            }
        }
        runCatching { libraryIndex.get().refresh() }
        val removed = (listOf(target.main) + target.companions).mapTo(HashSet()) { it.uri }
        runCatching { libraryScan.get().applyChanges(removed, touchedConsoles = setOfNotNull(target.consoleId)) }
        Log.i(TAG, "Converted ${target.main.name} to ${target.format.label}: $placed")
    }

    /** Every `.m3u` in [folder] that lists [oldName] now lists [newName]. */
    private fun rewritePlaylists(folder: Uri, oldName: String, newName: String) {
        for (doc in SafDocs.list(context, folder).orEmpty()) {
            if (doc.isDirectory || !doc.name.lowercase(Locale.ROOT).endsWith(".m3u") || doc.size > MAX_SHEET) continue
            val text = context.contentResolver.openInputStream(doc.uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: continue
            val rewritten = RomConversions.rewritePlaylist(text, oldName, newName) ?: continue
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { it.write(rewritten.toByteArray(Charsets.UTF_8)) }
                ?: throw IOException("Cannot write ${doc.name}")
        }
    }

    // ---- Undo (UndoKinds.CONVERSION) ------------------------------------------------------------

    private fun undoData(batchId: String, output: Uri, target: ConversionTarget): String = JsonObject().apply {
        addProperty("batch", batchId)
        addProperty("output", output.toString())
        addProperty("name", target.outputName)
        addProperty("console", target.consoleId.orEmpty())
        addProperty("download", target.downloadFileName.orEmpty())
        addProperty("original", target.main.name)
        addProperty("folder", target.parentUri)
        addProperty("input", target.main.uri)
    }.toString()

    private class UndoData(
        val batch: String, val output: Uri, val name: String, val console: String?, val download: String?, val original: String,
        val folder: Uri?,
        val input: String?
    )

    private fun parse(data: String): UndoData? = runCatching {
        val json = JsonParser.parseString(data).asJsonObject
        UndoData(
            json.get("batch").asString, json.get("output").asString.toUri(), json.get("name").asString,
            json.get("console")?.asString?.ifEmpty { null }, json.get("download")?.asString?.ifEmpty { null },
            json.get("original")?.asString.orEmpty(),
            json.get("folder")?.asString?.ifEmpty { null }?.toUri(),
            json.get("input")?.asString?.ifEmpty { null }
        )
    }.getOrNull()

    override suspend fun isAvailable(data: String): Boolean {
        val undo = parse(data) ?: return false
        return trashService.isAvailable(undo.batch) && withContext(Dispatchers.IO) { SafDocs.stat(context, undo.output) != null }
    }

    /** The converted file steps aside (renamed to a part), the originals come back from the trash, then it goes. */
    override suspend fun undo(entryId: Long, data: String): UndoOutcome = withContext(Dispatchers.IO) {
        val undo = parse(data) ?: return@withContext UndoOutcome.FAILED
        val aside = SafDocs.rename(context, undo.output, LibraryMoveRules.partName(undo.name)) ?: return@withContext UndoOutcome.FAILED
        val outcome = trashService.restore(undo.batch)
        if (outcome == UndoOutcome.DONE) {
            SafDocs.delete(context, aside)
            // The playlists that were pointed at the converted file go back to the original.
            if (undo.folder != null && undo.original.isNotEmpty() && undo.original != undo.name) {
                runCatching { rewritePlaylists(undo.folder, undo.name, undo.original) }
                    .onFailure { Log.w(TAG, "Could not restore the playlists of ${undo.original}: ${it.message}") }
            }
            if (undo.download != null && undo.original.isNotEmpty() && undo.original != undo.name) {
                downloadService.replaceExtracted(undo.download, setOf(undo.name), undo.original)
            }
            // The finished job no longer stands: Downloads and Free up space offer the game again.
            _jobs.update { jobs ->
                jobs.filterValues { job ->
                    job.state.active || (job.id != undo.input && (undo.download == null || job.downloadFileName != undo.download))
                }
            }
        } else if (SafDocs.rename(context, aside, undo.name) == null) {
            Log.w(TAG, "Undo of ${undo.name} left it as ${LibraryMoveRules.partName(undo.name)}")
        }
        runCatching { libraryIndex.get().refresh() }
        runCatching { libraryScan.get().applyChanges(emptySet(), touchedConsoles = setOfNotNull(undo.console)) }
        outcome
    }

    private companion object {
        const val TAG = "RomConversion"
        /** A sheet or playlist bigger than this is not one. */
        const val MAX_SHEET = 256 * 1024L
    }
}
