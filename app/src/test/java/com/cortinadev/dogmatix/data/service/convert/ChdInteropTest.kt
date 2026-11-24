package com.cortinadev.dogmatix.data.service.convert

import com.cortinadev.dogmatix.data.service.convert.chd.ChannelInput
import com.cortinadev.dogmatix.data.service.convert.chd.ChdInput
import com.cortinadev.dogmatix.util.MultiDisc
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Converts every image in `$DOGMATIX_CHD_INTEROP/in` (cue, gdi, iso) into `…/out` so chdman can
 * check the results (`chdman verify`, `info`, `extractcd`), and logs the timings to `…/out/timing.txt`.
 * Skipped unless the variable is set. Options: DOGMATIX_CHD_MATRIX (default "4:BEST"),
 * DOGMATIX_CHD_ISO_AS=cd|dvd|psp|all (default all), DOGMATIX_CHD_ONLY=<part of a file name>.
 */
class ChdInteropTest {

    @Test
    fun convertFolder() {
        val root = System.getenv("DOGMATIX_CHD_INTEROP")
        assumeTrue("DOGMATIX_CHD_INTEROP not set", !root.isNullOrBlank())
        val inDir = File(root, "in")
        val outDir = File(root, "out").apply { mkdirs() }
        // DOGMATIX_CHD_MATRIX="4:BEST,1:FAST" runs every image once per threads:compression pair.
        val matrix = (System.getenv("DOGMATIX_CHD_MATRIX") ?: "4:BEST").split(',').map {
            val (t, c) = it.trim().split(':')
            t.toInt() to ChdCompression.valueOf(c)
        }
        val isoAs = System.getenv("DOGMATIX_CHD_ISO_AS") ?: "all"
        val only = System.getenv("DOGMATIX_CHD_ONLY")
        val log = StringBuilder()
        val open = mutableListOf<RandomAccessFile>()
        fun input(f: File): ChdInput = ChannelInput(RandomAccessFile(f, "r").also { open += it }.channel)

        try {
            inDir.listFiles().orEmpty().sortedBy { it.name }.filter { only == null || it.name.contains(only) }.forEach { file ->
                val ext = file.extension.lowercase()
                val jobs: List<Pair<String, () -> ChdSource>> = when (ext) {
                    "cue", "gdi" -> listOf(file.nameWithoutExtension to {
                        val text = file.readText()
                        val names = if (ext == "cue") MultiDisc.cueFiles(text) else MultiDisc.gdiFiles(text)
                        ChdSource.Sheet(file.name, text, names.associateWith { input(File(inDir, it)) })
                    })
                    "iso" -> buildList {
                        if (isoAs == "all" || isoAs == "cd") add("${file.nameWithoutExtension}.cd" to { ChdSource.CdImage(file.name, input(file)) })
                        if (isoAs == "all" || isoAs == "dvd") add("${file.nameWithoutExtension}.dvd" to { ChdSource.Dvd(input(file)) })
                        if (isoAs == "all" || isoAs == "psp") add("${file.nameWithoutExtension}.psp" to { ChdSource.Dvd(input(file)) })
                    }
                    else -> emptyList()
                }
                for ((threads, compression) in matrix) for ((name, source) in jobs) {
                    val options = when {
                        name.endsWith(".psp") -> ChdProfile.PSP.options(compression, threads)
                        else -> ChdOptions(compression, null, threads)
                    }
                    val out = File(outDir, "$name.$threads.$compression.chd")
                    val start = System.nanoTime()
                    val result = RandomAccessFile(out, "rw").use { ChdEngine.create(source(), it.channel, options) }
                    val createS = (System.nanoTime() - start) / 1e9
                    val vStart = System.nanoTime()
                    val verify = RandomAccessFile(out, "r").use {
                        ChdEngine.verify(ChannelInput(it.channel),
                            (result as? ChdResult.Success)?.summary?.rawSha1, threads)
                    }
                    val verifyS = (System.nanoTime() - vStart) / 1e9
                    val logical = (result as? ChdResult.Success)?.summary?.logicalBytes ?: 0
                    log.append(String.format(java.util.Locale.ROOT,
                        "%s\t%s\tthreads=%d\t%s\tcreate=%.2fs (%.1f MB/s logical)\tverify=%.2fs\t%s\t%s\n",
                        name, compression, threads, if (verify is ChdResult.Success) "VERIFY_OK" else "VERIFY_FAIL $verify",
                        createS, logical / 1e6 / createS, verifyS, (result as? ChdResult.Success)?.summary ?: result,
                        Runtime.getRuntime().availableProcessors()))
                }
            }
        } finally {
            open.forEach { it.close() }
            File(outDir, "timing.txt").appendText(log.toString())
            print(log)
        }
    }
}
