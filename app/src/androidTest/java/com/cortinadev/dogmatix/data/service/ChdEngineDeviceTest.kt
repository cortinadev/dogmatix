package com.cortinadev.dogmatix.data.service

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cortinadev.dogmatix.data.service.convert.ChdEngine
import com.cortinadev.dogmatix.data.service.convert.ChdOptions
import com.cortinadev.dogmatix.data.service.convert.ChdProfile
import com.cortinadev.dogmatix.data.service.convert.ChdResult
import com.cortinadev.dogmatix.data.service.convert.ChdSource
import com.cortinadev.dogmatix.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * The CHD engine on ART, through ParcelFileDescriptors like the SAF path: a synthetic CD (cue +
 * two tracks) and a DVD image, created, verified and timed (logcat tag DogmatixPerf). Files live in
 * the sandboxed cache.
 */
@RunWith(AndroidJUnit4::class)
class ChdEngineDeviceTest {

    private val dir = File(TestData.context.cacheDir, "chd-test").apply { deleteRecursively(); mkdirs() }

    private fun compressible(rnd: Random, n: Int): ByteArray {
        val words = listOf("mario ", "zelda ", "sonic ", "ABCD").map { it.toByteArray() }
        val out = java.io.ByteArrayOutputStream(n)
        while (out.size() < n) if (rnd.nextInt(10) < 8) out.write(words[rnd.nextInt(4)]) else out.write(rnd.nextInt(256))
        return out.toByteArray().copyOf(n)
    }

    private fun open(f: File, mode: Int) = ParcelFileDescriptor.open(f, mode)

    private fun convert(source: ChdSource, out: File, options: ChdOptions): ChdResult.Success {
        val start = System.nanoTime()
        val result = open(out, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE).use {
            ChdEngine.create(source, it, options)
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("$result", result is ChdResult.Success)
        result as ChdResult.Success
        val vStart = System.nanoTime()
        val check = open(out, ParcelFileDescriptor.MODE_READ_ONLY).use { ChdEngine.verify(ChdEngine.input(it), result.summary.rawSha1) }
        val vms = (System.nanoTime() - vStart) / 1_000_000
        Log.i("DogmatixPerf", "CHD ${out.name}: ${result.summary.logicalBytes} B in $ms ms, verify $vms ms, " +
            "${result.summary.fileBytes} B, ${options.threads} threads, ${options.compression}")
        assertTrue("$check", check is ChdResult.Success)
        return result
    }

    @Test
    fun cueWithAudioAndDvd() {
        val rnd = Random(9)
        val t1 = File(dir, "Game (Track 1).bin").apply { writeBytes(compressible(rnd, 2352 * 2000)) }
        val t2 = File(dir, "Game (Track 2).bin").apply { writeBytes(ByteArray(2352 * 150) + compressible(rnd, 2352 * 500)) }
        val cue = "FILE \"Game (Track 1).bin\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n" +
            "FILE \"Game (Track 2).bin\" BINARY\n  TRACK 02 AUDIO\n    INDEX 00 00:00:00\n    INDEX 01 00:02:00\n"
        val fds = listOf(t1, t2).map { open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        try {
            val source = ChdSource.Sheet("Game.cue", cue, mapOf(t1.name to ChdEngine.input(fds[0]), t2.name to ChdEngine.input(fds[1])))
            val r = convert(source, File(dir, "game.chd"), ChdOptions())
            assertEquals((2000L + 652) * 2448, r.summary.logicalBytes)
        } finally {
            fds.forEach { it.close() }
        }

        val iso = File(dir, "game.iso").apply { writeBytes(compressible(rnd, 2048 * 8000) + ByteArray(2048 * 2000)) }
        open(iso, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val r = convert(ChdSource.Dvd(ChdEngine.input(fd)), File(dir, "psp.chd"), ChdProfile.PSP.options())
            assertEquals(iso.length(), r.summary.logicalBytes)
            assertTrue(r.summary.fileBytes < iso.length() / 2)
        }
        dir.deleteRecursively()
    }
}
