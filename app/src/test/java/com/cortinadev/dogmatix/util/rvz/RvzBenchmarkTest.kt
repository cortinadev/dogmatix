package com.cortinadev.dogmatix.util.rvz

import org.junit.Test
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/**
 * Throughput of convert and verify on bigger synthetic discs. Only runs with RVZ_BENCH set
 * (prints MB/s per thread count; nothing is asserted).
 */
class RvzBenchmarkTest {
    @Test
    fun throughput() {
        if (System.getenv("RVZ_BENCH") == null) return
        val discs = listOf<Pair<String, () -> ByteArray>>(
            "gc" to { SyntheticDiscs.gameCube(size = 256 shl 20, files = 1200, seed = 3) },
            "wii" to { SyntheticDiscs.wii(dataSectors = 64 * 60, files = 600, seed = 4).bytes },
        )
        for ((name, make) in discs) {
            val image = make()
            val iso = File.createTempFile("bench", ".iso").apply { deleteOnExit(); writeBytes(image) }
            val rvz = File.createTempFile("bench", ".rvz").apply { deleteOnExit() }
            for (threads in listOf(1, 2, 4)) {
                // warm-up on the first pass
                repeat(if (threads == 1) 2 else 1) {
                    val t0 = System.nanoTime()
                    FileChannel.open(iso.toPath(), READ).use { input ->
                        FileChannel.open(rvz.toPath(), WRITE, CREATE, TRUNCATE_EXISTING).use { output ->
                            val src = IsoSource(input)
                            RvzWriter(src, DiscLayout.analyze(src), output, RvzOptions(threads = threads)).write()
                        }
                    }
                    val t1 = System.nanoTime()
                    FileChannel.open(rvz.toPath(), READ).use { ch -> RvzReader(ch).use { it.decode(threads = threads) } }
                    val t2 = System.nanoTime()
                    val mb = image.size / 1048576.0
                    println(
                        "BENCH $name threads=$threads iso=${"%.0f".format(mb)}MiB rvz=${rvz.length() / 1048576}MiB " +
                            "convert=${"%.1f".format(mb / ((t1 - t0) / 1e9))}MB/s verify=${"%.1f".format(mb / ((t2 - t1) / 1e9))}MB/s",
                    )
                }
            }
        }
    }
}
