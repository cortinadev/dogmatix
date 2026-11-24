package com.cortinadev.dogmatix.util.rvz

import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs tasks on [threads] workers and hands their results back in submission order, with at
 * most [window] tasks in flight (that bounds memory: each task holds at most one 2 MiB unit).
 */
internal class OrderedPipeline<T>(threads: Int, private val window: Int, private val consume: (T) -> Unit) : Closeable {
    private val pool: ExecutorService = Executors.newFixedThreadPool(threads) { r ->
        Thread(r, "rvz-${COUNTER.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val pending = ArrayDeque<Future<T>>()

    fun submit(task: Callable<T>) {
        if (pending.size >= window) consume(take())
        pending.addLast(pool.submit(task))
    }

    fun drain() {
        while (pending.isNotEmpty()) consume(take())
    }

    private fun take(): T = try {
        pending.removeFirst().get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    override fun close() {
        for (f in pending) f.cancel(true)
        pending.clear()
        pool.shutdownNow()
    }

    private companion object {
        val COUNTER = AtomicInteger()
    }
}

/** Thrown when the caller's `isCancelled` turns true. */
class RvzCancelledException : Exception("Cancelled")
