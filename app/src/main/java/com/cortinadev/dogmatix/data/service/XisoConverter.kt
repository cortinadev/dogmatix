package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.util.ConversionFormat
import com.cortinadev.dogmatix.util.Xdvdfs
import com.cortinadev.dogmatix.util.XdvdfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Original Xbox Redump ISO → XISO ([Xdvdfs]): the game partition rewritten compact, as
 * `extract-xiso -r` does. Pure Kotlin over the SAF descriptors; the check re-reads the output
 * and compares the tree and the CRC-32 of every file with what was copied.
 */
@Singleton
class XisoConverter @Inject constructor() : RomConverter {

    override val format = ConversionFormat.XISO

    override suspend fun inspect(input: ConversionInput): ConversionInspection = withContext(Dispatchers.IO) {
        DescriptorSource.open(input.main).use { source ->
            val volume = Xdvdfs.detect(source)
            when (volume.layout) {
                Xdvdfs.Layout.XISO -> ConversionInspection(NotConvertible.ALREADY_CONVERTED)
                Xdvdfs.Layout.UNKNOWN -> ConversionInspection(NotConvertible.UNRECOGNISED)
                Xdvdfs.Layout.REDUMP -> try {
                    val cancel = cancelCheck()
                    ConversionInspection(outputBytes = Xdvdfs.plan(source, volume, cancel).totalBytes)
                } catch (_: XdvdfsException) {
                    ConversionInspection(NotConvertible.DAMAGED)
                }
            }
        }
    }

    override suspend fun convert(input: ConversionInput, output: ConversionOutput, onProgress: (Float) -> Unit): ConversionCheck =
        withContext(Dispatchers.IO) {
            val cancel = cancelCheck()
            DescriptorSource.open(input.main).use { source ->
                val volume = Xdvdfs.detect(source)
                when (volume.layout) {
                    Xdvdfs.Layout.XISO -> throw ConversionException(NotConvertible.ALREADY_CONVERTED, "${input.main.name} already is an XISO")
                    Xdvdfs.Layout.UNKNOWN -> throw ConversionException(NotConvertible.UNRECOGNISED, "${input.main.name} has no Xbox file system")
                    Xdvdfs.Layout.REDUMP -> Unit
                }
                val plan = try {
                    Xdvdfs.plan(source, volume, cancel)
                } catch (e: XdvdfsException) {
                    throw ConversionException(NotConvertible.DAMAGED, e.message.orEmpty())
                }
                val written = output.file.open("rwt").use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { raw ->
                        BufferedOutputStream(raw, 1 shl 20).use { out ->
                            val result = try {
                                Xdvdfs.write(source, plan, { b, o, l -> out.write(b, o, l) }, onProgress, cancel)
                            } catch (e: XdvdfsException) {
                                throw ConversionException(NotConvertible.DAMAGED, e.message.orEmpty())
                            }
                            out.flush()
                            // On the storage before it is checked and the original goes.
                            runCatching { raw.fd.sync() }
                            result
                        }
                    }
                }
                ConversionCheck { file, progress ->
                    withContext(Dispatchers.IO) {
                        val check = cancelCheck()
                        DescriptorSource.open(file).use { Xdvdfs.verify(it, plan, written, progress, check) }
                    }
                }
            }
        }

    /** Throws CancellationException once the calling coroutine is cancelled (called between blocks). */
    private suspend fun cancelCheck(): () -> Unit {
        val job = currentCoroutineContext()[Job]
        return { job?.ensureActive() }
    }
}
