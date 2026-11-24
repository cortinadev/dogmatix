package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.cortinadev.dogmatix.data.service.convert.RvzEngine
import com.cortinadev.dogmatix.data.service.convert.RvzFailure
import com.cortinadev.dogmatix.data.service.convert.RvzResult
import com.cortinadev.dogmatix.util.ConversionFormat
import com.cortinadev.dogmatix.util.StorageProblem
import com.cortinadev.dogmatix.util.StorageProblemException
import com.cortinadev.dogmatix.util.rvz.DiscKind
import com.cortinadev.dogmatix.util.rvz.RvzOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GameCube / Wii ISO, GCM or WBFS → RVZ, with [RvzEngine] (Dolphin's defaults). The engine
 * returns the plain disc's digest; the check decodes the whole RVZ and compares it with that.
 * The output is written in place through a seekable descriptor ("rw"), which the
 * external-storage provider (internal, SD) hands out.
 */
@Singleton
class RvzConverter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val engine: RvzEngine
) : RomConverter {

    override val format = ConversionFormat.RVZ

    private val threads: Int by lazy { conversionThreads(context) }

    override suspend fun inspect(input: ConversionInput): ConversionInspection = withContext(Dispatchers.IO) {
        val probe = input.main.open("r").use { engine.probe(it) }
        // The RVZ's size is only known once written: no estimate.
        when (probe.kind) {
            DiscKind.GAMECUBE, DiscKind.WII -> ConversionInspection()
            DiscKind.WBFS -> if (probe.discSize > 0) ConversionInspection() else ConversionInspection(NotConvertible.UNRECOGNISED)
            DiscKind.RVZ, DiscKind.WIA, DiscKind.OTHER_CONTAINER -> ConversionInspection(NotConvertible.ALREADY_CONVERTED)
            DiscKind.NKIT, DiscKind.UNKNOWN -> ConversionInspection(NotConvertible.UNRECOGNISED)
        }
    }

    override suspend fun convert(input: ConversionInput, output: ConversionOutput, onProgress: (Float) -> Unit): ConversionCheck =
        withContext(Dispatchers.IO) {
            val cancelled = cancelFlag()
            val result = input.main.open("r").use { inPfd ->
                output.file.open("rw").use { outPfd ->
                    val r = engine.convert(inPfd, outPfd, RvzOptions(threads = threads), onProgress, cancelled)
                    // On the storage before it is checked and the original goes.
                    if (r is RvzResult.Success) runCatching { outPfd.fileDescriptor.sync() }
                    r
                }
            }
            val digest = when (result) {
                is RvzResult.Success -> result.digest
                is RvzResult.Cancelled -> throw CancellationException("RVZ conversion of ${input.main.name} cancelled")
                is RvzResult.Failure -> throw failureOf(input.main.name, result)
            }
            ConversionCheck { file, progress ->
                withContext(Dispatchers.IO) {
                    val isCancelled = cancelFlag()
                    when (val check = file.open("r").use { engine.verify(it, digest, threads, progress, isCancelled) }) {
                        is RvzResult.Success -> true
                        is RvzResult.Cancelled -> throw CancellationException("RVZ check cancelled")
                        is RvzResult.Failure -> when (check.reason) {
                            RvzFailure.MISMATCH, RvzFailure.CORRUPT -> false
                            else -> throw failureOf(file.name, check)
                        }
                    }
                }
            }
        }

    /** What the conversion service makes of an engine failure: the content's fault, or the storage's. */
    private fun failureOf(name: String, failure: RvzResult.Failure): IOException {
        val message = "$name: ${failure.reason} ${failure.detail}".trim()
        return when (failure.reason) {
            RvzFailure.NOT_A_DISC, RvzFailure.NKIT -> ConversionException(NotConvertible.UNRECOGNISED, message)
            RvzFailure.ALREADY_COMPRESSED -> ConversionException(NotConvertible.ALREADY_CONVERTED, message)
            RvzFailure.TRUNCATED, RvzFailure.CORRUPT -> ConversionException(NotConvertible.DAMAGED, message)
            RvzFailure.NO_SPACE -> StorageProblemException(StorageProblem.NO_SPACE, message)
            RvzFailure.OUTPUT_NOT_SEEKABLE, RvzFailure.READ_ERROR, RvzFailure.WRITE_ERROR,
            RvzFailure.MISMATCH, RvzFailure.INTERNAL -> IOException(message)
        }
    }

    /** True once the calling coroutine is cancelled (polled by the engine between blocks). */
    private suspend fun cancelFlag(): () -> Boolean {
        val job = currentCoroutineContext()[Job]
        return { job?.isActive == false }
    }
}
