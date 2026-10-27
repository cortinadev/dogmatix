package com.cortinadev.dogmatix.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Scan requests to one server, for one scan run: at most [perHost] at a time. A server that
 * pushes back (429, 5xx) gets nothing until the moment it asked for, and from then on one
 * request at a time until the run ends. Host names are compared without case; servers do not
 * hold each other up. [clock] is in milliseconds, on the same time line as `delay`.
 */
class ScanHostGate(private val perHost: Int, private val clock: () -> Long = System::currentTimeMillis) {

    private class Host(perHost: Int) {
        val slots = Semaphore(perHost)
        /** Taken on top of a slot once the server has pushed back: one request at a time. */
        val single = Mutex()
        @Volatile var calm = false
        @Volatile var notBefore = 0L
    }

    private val hosts = ConcurrentHashMap<String, Host>()

    private fun host(name: String) = hosts.getOrPut(name.lowercase()) { Host(perHost.coerceAtLeast(1)) }

    suspend fun <T> withSlot(name: String, block: suspend () -> T): T {
        val host = host(name)
        return host.slots.withPermit {
            waitTurn(host)
            // Pushed back before or while this one waited: from now on, one at a time.
            if (host.calm) host.single.withLock { waitTurn(host); block() } else block()
        }
    }

    /** The server asked to wait [waitMs]: every request to it holds off until then, one at a time after. */
    fun pushBack(name: String, waitMs: Long) {
        val host = host(name)
        host.calm = true
        host.notBefore = maxOf(host.notBefore, clock() + waitMs.coerceAtLeast(0))
    }

    /** Until the server's moment; a push-back that comes meanwhile makes the wait longer. */
    private suspend fun waitTurn(host: Host) {
        while (true) {
            val wait = host.notBefore - clock()
            if (wait <= 0) return
            delay(wait)
        }
    }
}
