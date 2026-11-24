package com.cortinadev.dogmatix.data.service.convert

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.util.rvz.Lfg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * RvzEngine on the device: zstd-jni's .so loads for this ABI, a tiny GameCube image converts
 * through ParcelFileDescriptors, verifies and is smaller; a pipe as output is refused. Files live
 * in the instrumentation sandbox (filesDir is redirected by the test runner).
 */
@RunWith(AndroidJUnit4::class)
class RvzEngineTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "rvz-test").apply { mkdirs() }
    private val engine = RvzEngine()

    private fun tinyGameCube(): ByteArray {
        val b = ByteArray(0x200000 + 0x400)
        "GDTE01".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        b[0x1C] = 0xC2.toByte(); b[0x1D] = 0x33; b[0x1E] = 0x9F.toByte(); b[0x1F] = 0x3D
        // A "file" of compressible text, then junk to the end.
        val text = "Dogmatix RVZ instrumented test. ".toByteArray()
        for (i in 0x8000 until 0x48000) b[i] = text[i % text.size]
        val lfg = Lfg()
        var p = 0x48000L
        while (p < b.size) {
            val n = minOf(b.size - p, 0x8000 - p % 0x8000).toInt()
            lfg.initForOffset("GDTE".toByteArray(), 0, p)
            lfg.fill(b, p.toInt(), n)
            p += n
        }
        return b
    }

    @Test
    fun convertsAndVerifiesThroughFileDescriptors() {
        val iso = File(dir, "tiny.iso").apply { writeBytes(tinyGameCube()) }
        val rvz = File(dir, "tiny.rvz").apply { delete(); createNewFile() }
        val converted = ParcelFileDescriptor.open(iso, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            ParcelFileDescriptor.open(rvz, ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                assertEquals(com.cortinadev.dogmatix.util.rvz.DiscKind.GAMECUBE, engine.probe(input).kind)
                engine.convert(input, output, onProgress = {})
            }
        }
        if (converted !is RvzResult.Success) fail("convert: $converted")
        converted as RvzResult.Success
        assertTrue("rvz ${rvz.length()} vs iso ${iso.length()}", rvz.length() < iso.length() / 4)
        val verified = ParcelFileDescriptor.open(rvz, ParcelFileDescriptor.MODE_READ_ONLY).use {
            engine.verify(it, converted.digest)
        }
        assertTrue("verify: $verified", verified is RvzResult.Success)

        // A flipped byte in the data must fail verification.
        val bytes = rvz.readBytes().also { it[it.size - 5] = (it[it.size - 5] + 1).toByte() }
        File(dir, "bad.rvz").writeBytes(bytes)
        val bad = ParcelFileDescriptor.open(File(dir, "bad.rvz"), ParcelFileDescriptor.MODE_READ_ONLY).use {
            engine.verify(it, converted.digest)
        }
        assertTrue("bad: $bad", bad is RvzResult.Failure)
        dir.deleteRecursively()
    }

    @Test
    fun refusesAPipeAsOutput() {
        val iso = File(dir, "tiny2.iso").apply { writeBytes(tinyGameCube()) }
        val (read, write) = ParcelFileDescriptor.createPipe()
        try {
            val result = ParcelFileDescriptor.open(iso, ParcelFileDescriptor.MODE_READ_ONLY).use { engine.convert(it, write) }
            assertEquals(RvzResult.Failure(RvzFailure.OUTPUT_NOT_SEEKABLE, "The destination is not a regular file"), result)
        } finally {
            read.close(); write.close()
            dir.deleteRecursively()
        }
    }
}
