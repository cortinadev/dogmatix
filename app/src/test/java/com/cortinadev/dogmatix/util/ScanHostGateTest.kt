package com.cortinadev.dogmatix.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Virtual time: the gate's clock is the test scheduler's, the same one `delay` runs on. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanHostGateTest {

    private fun TestScope.gate(perHost: Int) = ScanHostGate(perHost) { currentTime }

    @Test
    fun `at most n requests at a time per server`() = runTest {
        val gate = gate(2)
        var active = 0
        var peak = 0
        (1..5).map {
            launch {
                gate.withSlot("myrient.example") {
                    active++
                    peak = maxOf(peak, active)
                    delay(100)
                    active--
                }
            }
        }.joinAll()
        assertEquals(2, peak)
        // 5 requests of 100 ms, two at a time.
        assertEquals(300L, currentTime)
    }

    @Test
    fun `servers do not hold each other up`() = runTest {
        val gate = gate(1)
        gate.pushBack("slow.example", 5_000)
        launch { gate.withSlot("busy.example") { delay(1_000) } }
        var other = -1L
        launch { gate.withSlot("other.example") { other = currentTime } }
        advanceUntilIdle()
        assertEquals(0L, other)
    }

    @Test
    fun `a push-back holds every request until the moment asked, then lets them through one at a time`() = runTest {
        val gate = gate(2)
        gate.pushBack("myrient.example", 500)
        val starts = mutableListOf<Long>()
        (1..3).map { launch { gate.withSlot("myrient.example") { starts += currentTime; delay(100) } } }.joinAll()
        assertEquals(listOf(500L, 600L, 700L), starts)
    }

    @Test
    fun `a push-back while requests wait makes them wait longer`() = runTest {
        val gate = gate(2)
        gate.pushBack("myrient.example", 500)
        var start = -1L
        launch { gate.withSlot("myrient.example") { start = currentTime } }
        advanceTimeBy(200)
        // Another source of the server got a 429 asking for a second more.
        gate.pushBack("myrient.example", 1_000)
        advanceUntilIdle()
        assertEquals(1_200L, start)
    }

    @Test
    fun `the later moment wins, a shorter push-back does not bring it forward`() = runTest {
        val gate = gate(2)
        gate.pushBack("myrient.example", 2_000)
        gate.pushBack("myrient.example", 100)
        var start = -1L
        launch { gate.withSlot("myrient.example") { start = currentTime } }
        advanceUntilIdle()
        assertEquals(2_000L, start)
    }

    @Test
    fun `requests that were already waiting go one at a time once the server pushed back`() = runTest {
        val gate = gate(2)
        // Two requests hold the server's slots; a third waits for one.
        val starts = mutableListOf<Long>()
        val first = launch { gate.withSlot("myrient.example") { delay(100) } }
        val second = launch { gate.withSlot("myrient.example") { delay(100) } }
        val waiting = (1..2).map { launch { gate.withSlot("myrient.example") { starts += currentTime; delay(100) } } }
        advanceTimeBy(50)
        gate.pushBack("myrient.example", 250) // until t = 300
        listOf(first, second).joinAll()
        waiting.joinAll()
        assertEquals(listOf(300L, 400L), starts)
    }

    @Test
    fun `server names are compared without case`() = runTest {
        val gate = gate(1)
        gate.pushBack("Myrient.Example", 300)
        var start = -1L
        launch { gate.withSlot("myrient.example") { start = currentTime } }
        advanceUntilIdle()
        assertEquals(300L, start)
    }
}
