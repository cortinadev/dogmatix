package com.cortinadev.dogmatix.ui.common

/**
 * One ZL / ZR trigger, so that one physical pull is one press however the pad reports it: as a
 * key (digital triggers), as an analog axis, or as both at once (the Retroid Pocket's analog
 * triggers send the key and the axis together, which used to count every pull twice).
 *
 * The axis has two thresholds: it is pressed past [PRESS] and only released below [RELEASE], so
 * a trigger resting near half travel, or a noisy hall sensor, cannot fire again and again.
 */
class TriggerPress {
    private var key = false
    private var axis = false
    private var held = false

    /** Key down / up of the trigger. True when this starts a press. */
    fun onKey(down: Boolean): Boolean {
        key = down
        return update()
    }

    /** New axis reading (0 = rest, 1 = fully pulled). True when this starts a press. */
    fun onAxis(value: Float): Boolean {
        axis = if (axis) value > RELEASE else value > PRESS
        return update()
    }

    private fun update(): Boolean {
        val now = key || axis
        val pressed = now && !held
        held = now
        return pressed
    }

    companion object {
        const val PRESS = 0.6f
        const val RELEASE = 0.25f
    }
}
