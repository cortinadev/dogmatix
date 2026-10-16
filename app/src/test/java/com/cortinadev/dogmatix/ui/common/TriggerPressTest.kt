package com.cortinadev.dogmatix.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class TriggerPressTest {

    /** Presses counted while feeding [steps]: a Boolean is a key down / up, a Float an axis reading. */
    private fun presses(vararg steps: Any): Int {
        val trigger = TriggerPress()
        return steps.count { step ->
            when (step) {
                is Boolean -> trigger.onKey(step)
                is Float -> trigger.onAxis(step)
                else -> error("unexpected step $step")
            }
        }
    }

    @Test
    fun digitalTriggerCountsEachClick() {
        assertEquals(2, presses(true, false, true, false))
        // A key down repeated without its up (some pads resend it) is still one press.
        assertEquals(1, presses(true, true, true, false))
    }

    @Test
    fun analogPullIsOnePressAcrossTheWholeTravel() {
        assertEquals(1, presses(0f, 0.2f, 0.5f, 0.7f, 0.9f, 1f, 0.8f, 0.4f, 0.1f, 0f))
    }

    @Test
    fun noiseAroundHalfTravelDoesNotRepeat() {
        // Used to fire on every crossing of 0.5.
        assertEquals(1, presses(0.62f, 0.49f, 0.55f, 0.45f, 0.61f, 0.3f, 0.65f))
        // A light touch below the press threshold never counts.
        assertEquals(0, presses(0.3f, 0.55f, 0.4f, 0f))
    }

    @Test
    fun keyAndAxisOfTheSamePullCountOnce() {
        // Analog triggers that report both: whichever arrives first starts the press.
        assertEquals(1, presses(0.3f, true, 0.7f, 1f, 0.5f, false, 0.1f))
        assertEquals(1, presses(0.7f, true, 1f, false, 0.2f))
        // Two separate pulls are still two presses.
        assertEquals(2, presses(true, 0.9f, false, 0f, 0.8f, true, false, 0f))
    }
}
