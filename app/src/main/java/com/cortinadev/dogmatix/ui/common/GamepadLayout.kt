package com.cortinadev.dogmatix.ui.common

import android.view.KeyEvent
import com.cortinadev.dogmatix.R

/**
 * How the pad's buttons are *named* (and coloured) in the legend. Dogmatix always acts on the position of a
 * button (Android reports BUTTON_A for the south face button, BUTTON_B for the east one…), so
 * changing the layout never changes what a button does: it only relabels the legend for pads
 * that print other names on those same positions. To move the actions themselves — pads that
 * report the positions the other way round — see the swap setting ([swapFaceKeyCode]).
 */
enum class GamepadLayout(
    val labelRes: Int,
    /** Names of the south / east / west / north face buttons, in that order. */
    private val faces: List<String>,
    private val bumpers: String,
    private val triggers: String
) {
    XBOX(R.string.gamepad_layout_xbox, listOf("A", "B", "X", "Y"), "LB · RB", "LT · RT"),
    // Same letters on the same positions as Xbox: A always accepts and B always goes back, and
    // whoever holds a pad wired the other way round turns the swap setting on.
    NINTENDO(R.string.gamepad_layout_nintendo, listOf("A", "B", "X", "Y"), "L · R", "ZL · ZR"),
    PLAYSTATION(R.string.gamepad_layout_playstation, listOf("✕", "○", "□", "△"), "L1 · R1", "L2 · R2");

    /** [key] is a legend key in the app's own (Xbox) naming; anything else is left alone. */
    fun glyphFor(key: String): String = when (key) {
        "A" -> faces[0]
        "B" -> faces[1]
        "X" -> faces[2]
        "Y" -> faces[3]
        BUMPERS_KEY -> bumpers
        TRIGGERS_KEY -> triggers
        else -> key
    }

    companion object {
        /** Keys [com.cortinadev.dogmatix.ui.components.LegendEntry] uses for the shoulder buttons. */
        const val BUMPERS_KEY = "LB · RB"
        const val TRIGGERS_KEY = "ZL · ZR"

        fun fromName(name: String?): GamepadLayout = entries.firstOrNull { it.name == name } ?: XBOX
    }
}

/**
 * The keycode to act on when "swap A/B and X/Y" is on, for pads that report the position instead
 * of the printed name. A and B do not become each other but the keys the whole system already
 * understands — confirm and back — because Android would otherwise undo the swap: when a window
 * leaves BUTTON_A / BUTTON_B unhandled it synthesises DPAD_CENTER / BACK from the *original*
 * event, which would fire the action we just moved away.
 */
fun swapFaceKeyCode(keyCode: Int): Int = when (keyCode) {
    KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BACK
    KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_DPAD_CENTER
    KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
    KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
    else -> keyCode
}
