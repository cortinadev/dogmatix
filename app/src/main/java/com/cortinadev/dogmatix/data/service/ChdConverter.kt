package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.os.ParcelFileDescriptor
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.convert.ChdEngine
import com.cortinadev.dogmatix.data.service.convert.ChdFailure
import com.cortinadev.dogmatix.data.service.convert.ChdProfile
import com.cortinadev.dogmatix.data.service.convert.ChdResult
import com.cortinadev.dogmatix.data.service.convert.ChdSource
import com.cortinadev.dogmatix.util.ChdMode
import com.cortinadev.dogmatix.util.ConversionFormat
import com.cortinadev.dogmatix.util.RomConversions
import com.cortinadev.dogmatix.util.StorageProblem
import com.cortinadev.dogmatix.util.StorageProblemException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disc images → CHD with [ChdEngine] (chdman's `createcd` / `createdvd`; lzma + zlib, or zlib alone
 * with Settings → CHD compression on Fast): a `.cue` or `.gdi` with the tracks it names ([ConversionInput.companions]), or a lone `.iso` as a one-track
 * CD (PS2 too: NetherSX2 does not open DVD CHDs) or, for the PSP, as a DVD with 2048-byte hunks.
 * The check decodes the whole CHD and compares its data SHA-1 with the source's.
 */
@Singleton
class ChdConverter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository
) : RomConverter {

    override val format = ConversionFormat.CHD

    private val threads: Int by lazy { conversionThreads(context) }

    override suspend fun inspect(input: ConversionInput): ConversionInspection = withContext(Dispatchers.IO) {
        // The compressed size is only known once written: no estimate.
        opened(input) { source, _ -> ChdEngine.inspect(source)?.let { ConversionInspection(reasonOf(it.reason) ?: NotConvertible.UNRECOGNISED) } ?: ConversionInspection() }
    }

    override suspend fun convert(input: ConversionInput, output: ConversionOutput, onProgress: (Float) -> Unit): ConversionCheck =
        withContext(Dispatchers.IO) {
            val cancelled = cancelFlag()
            // Settings → Downloads → CHD compression, read as each conversion starts.
            val compression = settings.chdCompression.first()
            val result = opened(input) { source, profile ->
                output.file.open("rw").use { out ->
                    val r = ChdEngine.create(source, out, profile.options(compression, threads), onProgress, cancelled)
                    // On the storage before it is checked and the original goes.
                    if (r is ChdResult.Success) runCatching { out.fileDescriptor.sync() }
                    r
                }
            }
            val rawSha1 = when (result) {
                is ChdResult.Success -> result.summary.rawSha1
                is ChdResult.Cancelled -> throw CancellationException("CHD conversion of ${input.main.name} cancelled")
                is ChdResult.Failure -> throw failureOf(input.main.name, result)
            }
            ConversionCheck { file, progress ->
                withContext(Dispatchers.IO) {
                    val isCancelled = cancelFlag()
                    val check = file.open("r").use { ChdEngine.verify(ChdEngine.input(it), rawSha1, threads, progress, isCancelled) }
                    when (check) {
                        is ChdResult.Success -> true
                        is ChdResult.Cancelled -> throw CancellationException("CHD check cancelled")
                        is ChdResult.Failure -> if (check.reason == ChdFailure.CORRUPT) false else throw failureOf(file.name, check)
                    }
                }
            }
        }

    /**
     * Opens [input] as a [ChdSource] (the sheet and its tracks, or the lone image) with its
     * profile, runs [block], and closes every descriptor afterwards.
     */
    private fun <T> opened(input: ConversionInput, block: (ChdSource, ChdProfile) -> T): T {
        val mode = RomConversions.chdMode(input.systemNames, input.main.name)
            ?: throw ConversionException(NotConvertible.UNRECOGNISED, "${input.main.name} is not a disc image CHD takes here")
        val profile = if (mode == ChdMode.DVD) ChdProfile.PSP else ChdProfile.CD
        val open = ArrayList<ParcelFileDescriptor>()
        try {
            val ext = input.main.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val source = if (ext == "cue" || ext == "gdi") {
                val text = input.main.open("r").also { open += it }.let { pfd ->
                    ParcelFileDescriptor.AutoCloseInputStream(pfd.dup()).use { String(it.readBytes(), Charsets.UTF_8) }
                }
                val tracks = input.companions.associate { file -> file.name to ChdEngine.input(file.open("r").also { open += it }) }
                ChdSource.Sheet(input.main.name, text, tracks)
            } else {
                val image = ChdEngine.input(input.main.open("r").also { open += it })
                if (mode == ChdMode.DVD) ChdSource.Dvd(image) else ChdSource.CdImage(input.main.name, image)
            }
            return block(source, profile)
        } finally {
            open.forEach { runCatching { it.close() } }
        }
    }

    private fun reasonOf(failure: ChdFailure): NotConvertible? = when (failure) {
        ChdFailure.MISSING_FILE -> NotConvertible.INCOMPLETE
        ChdFailure.CORRUPT -> NotConvertible.DAMAGED
        ChdFailure.BAD_INPUT, ChdFailure.UNSUPPORTED -> NotConvertible.UNRECOGNISED
        else -> null
    }

    /** What the conversion service makes of an engine failure: the content's fault, or the storage's. */
    private fun failureOf(name: String, failure: ChdResult.Failure): IOException {
        val message = "$name: ${failure.reason} ${failure.message}".trim()
        reasonOf(failure.reason)?.let { return ConversionException(it, message) }
        return if (failure.reason == ChdFailure.NO_SPACE) StorageProblemException(StorageProblem.NO_SPACE, message) else IOException(message)
    }

    /** True once the calling coroutine is cancelled (polled by the engine between hunks). */
    private suspend fun cancelFlag(): () -> Boolean {
        val job = currentCoroutineContext()[Job]
        return { job?.isActive == false }
    }
}
