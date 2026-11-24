package com.cortinadev.dogmatix.data.service

import android.net.Uri
import android.os.ParcelFileDescriptor
import com.cortinadev.dogmatix.util.ConversionFormat
import com.cortinadev.dogmatix.util.RomConversions
import com.cortinadev.dogmatix.util.SeekableSource
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * One file a conversion reads or writes: a SAF document, opened on demand. [open] takes a
 * `ContentResolver.openFileDescriptor` mode ("r", "rw", "rwt"). On local storage (internal, SD
 * card, USB: ExternalStorageProvider) the descriptor is a real file, so it seeks; native code can
 * open it again by [procPath] while the descriptor stays open.
 */
class ConversionFile(val name: String, val uri: Uri, val size: Long, private val opener: (String) -> ParcelFileDescriptor) {
    fun open(mode: String = "r"): ParcelFileDescriptor = opener(mode)

    companion object {
        /** A path native code can `open()` for [pfd] while it is open (`/proc/self/fd/N`). */
        fun procPath(pfd: ParcelFileDescriptor): String = "/proc/self/fd/${pfd.fd}"
    }
}

/**
 * What a conversion converts: the [main] file (the `.iso`, `.cue`, `.gdi`…) and the files it
 * names ([companions]: the tracks of a sheet, by their name as written in it), all in one folder.
 * [systemNames] are the names of its console ([RomConversions.namesFor]).
 */
class ConversionInput(val systemNames: Set<String>, val main: ConversionFile, val companions: List<ConversionFile> = emptyList()) {
    val files: List<ConversionFile> get() = listOf(main) + companions
    val totalBytes: Long get() = files.sumOf { it.size.coerceAtLeast(0) }
}

/**
 * Where a conversion writes: [file] is the `<name>.dogmatix-part` document next to the input,
 * created empty; [finalName] is what it is renamed to once checked. Open it with "rw" (seekable
 * on local storage) or "rwt".
 */
class ConversionOutput(val finalName: String, val file: ConversionFile)

/** Why a file is not converted (read from its content, not its name). */
enum class NotConvertible {
    /** It already is in the target format (an Xbox ISO that is an XISO). */
    ALREADY_CONVERTED,
    /** The content is not what the format needs (no Xbox file system, not a disc image…). */
    UNRECOGNISED,
    /** It is what it should be but broken or cut short. */
    DAMAGED,
    /** A file its sheet (`.cue` / `.gdi`) names is not next to it. */
    INCOMPLETE
}

/** What [RomConverter.inspect] found: whether it converts and, when it can be told cheaply, the output's size. */
data class ConversionInspection(val reason: NotConvertible? = null, val outputBytes: Long? = null) {
    val convertible: Boolean get() = reason == null
}

/** The check of a written output, handed back by [RomConverter.convert] (it knows what it wrote). */
fun interface ConversionCheck {
    /** True when [output] (the finished part file) stands for the input. Reads it; cancellable. */
    suspend fun verify(output: ConversionFile, onProgress: (Float) -> Unit): Boolean
}

/** A conversion failed for a reason the converter knows; anything else is classified by [RomConversionService]. */
class ConversionException(val reason: NotConvertible, message: String) : IOException(message)

/**
 * A ROM converter, bound with `@Binds @IntoSet` (see `di/ConversionModule`). [RomConversionService]
 * runs them one at a time, writes to a part file, has the output checked, and only then retires
 * the original; a converter only reads its input and writes its output.
 *
 * Every call runs on a background dispatcher and must stop soon after its coroutine is cancelled
 * (check `currentCoroutineContext().ensureActive()` between blocks; native code gets a cancel flag
 * from `isActive`) and throw [kotlinx.coroutines.CancellationException]; the service then deletes
 * the part file.
 */
interface RomConverter {
    val format: ConversionFormat

    /** By names only, no reading: whether a file called [fileName] of a system known by [systemNames] is for this converter. */
    fun supports(systemNames: Set<String>, fileName: String): Boolean = RomConversions.formatFor(systemNames, fileName) == format

    /** Reads what it needs of [input] (headers, a sheet) to tell whether it converts, and how big the output will be if cheap. */
    suspend fun inspect(input: ConversionInput): ConversionInspection

    /**
     * Writes [input] converted into [output]. [onProgress] gets 0..1. Throws [ConversionException]
     * for content it cannot convert, an IOException for storage errors. Returns the check of what
     * it wrote, run by the service before the original is touched.
     */
    suspend fun convert(input: ConversionInput, output: ConversionOutput, onProgress: (Float) -> Unit): ConversionCheck
}

/** Threads for RVZ and CHD on this device ([RomConversions.threadsFor]). */
fun conversionThreads(context: android.content.Context): Int {
    val lowRam = runCatching { context.getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice == true }.getOrDefault(false)
    return RomConversions.threadsFor(Runtime.getRuntime().availableProcessors(), lowRam)
}

/** [SeekableSource] over an open descriptor (positional reads: no shared file position). */
class DescriptorSource(pfd: ParcelFileDescriptor) : SeekableSource, AutoCloseable {
    private val stream = FileInputStream(pfd.fileDescriptor)
    private val channel: FileChannel = stream.channel
    private val owner = pfd

    override val size: Long = runCatching { channel.size() }.getOrElse { pfd.statSize }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(buffer, offset, length), position)

    override fun close() {
        runCatching { stream.close() }
        runCatching { owner.close() }
    }

    companion object {
        /** Opens [file] for reading; an IOException when the provider hands out a pipe that cannot seek. */
        fun open(file: ConversionFile): DescriptorSource {
            val pfd = file.open("r")
            val source = DescriptorSource(pfd)
            if (runCatching { source.channel.position(0) }.isFailure) {
                source.close()
                throw IOException("${file.name} cannot be read at random positions")
            }
            return source
        }
    }
}
