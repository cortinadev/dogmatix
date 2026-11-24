package com.cortinadev.dogmatix.util.rvz

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdDecompressCtx
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Zstandard through zstd-jni (BSD-2-Clause; zstd itself BSD-3-Clause). Thread-safe: native
 * contexts are pooled and freed by [close].
 */
class RvzCodec(private val level: Int = RvzFormat.DEFAULT_LEVEL) : Closeable {
    private val compressors = ConcurrentLinkedQueue<ZstdCompressCtx>()
    private val decompressors = ConcurrentLinkedQueue<ZstdDecompressCtx>()
    private val all = ConcurrentLinkedQueue<Closeable>()

    fun compress(src: ByteArray, off: Int, len: Int): ByteArray {
        val ctx = compressors.poll() ?: ZstdCompressCtx().setLevel(level).also { all += it }
        try {
            val dst = ByteArray(bound(len))
            val n = ctx.compressByteArray(dst, 0, dst.size, src, off, len)
            return dst.copyOf(n)
        } finally {
            compressors += ctx
        }
    }

    /** Decompresses a frame whose output is at most [maxOut] bytes. */
    fun decompress(src: ByteArray, off: Int, len: Int, maxOut: Int): ByteArray {
        val ctx = decompressors.poll() ?: ZstdDecompressCtx().also { all += it }
        try {
            val dst = ByteArray(maxOut)
            val n = ctx.decompressByteArray(dst, 0, dst.size, src, off, len)
            return if (n == dst.size) dst else dst.copyOf(n)
        } finally {
            decompressors += ctx
        }
    }

    fun bound(len: Int): Int = Zstd.compressBound(len.toLong()).toInt()

    override fun close() {
        while (true) (all.poll() ?: break).close()
        compressors.clear()
        decompressors.clear()
    }
}
