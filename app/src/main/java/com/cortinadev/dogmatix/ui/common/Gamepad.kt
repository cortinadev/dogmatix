package com.cortinadev.dogmatix.ui.common

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.focus.FocusRequester
import com.cortinadev.dogmatix.ui.components.LegendEntry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Buttons the app handles itself. A / B / D-pad go through the normal focus system.
 * ZL / ZR switch sections, LB / RB switch panels inside a screen, Select stars a game,
 * R3 collapses / expands the filter panel.
 */
enum class GamepadButton { PREV_TAB, NEXT_TAB, PREV_PANEL, NEXT_PANEL, X, Y, FAVOURITE, TOGGLE_FILTERS, FOCUS_TAB }

/**
 * Process-wide gamepad state: whether one is connected (drives the button legend)
 * and a bus for the shoulder / X / Y presses that screens react to.
 */
object Gamepad {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    val presses = MutableSharedFlow<GamepadButton>(extraBufferCapacity = 8)

    /** One requester per section tab (keyed by route) so focus can be handed to the active tab. */
    val tabFocus: Map<String, FocusRequester> = mapOf(
        "home" to FocusRequester(), "downloads" to FocusRequester(),
        "sources" to FocusRequester(), "settings" to FocusRequester()
    )
    var currentRoute: String = "home"

    /** Requester of the active section tab (screens hand focus back to the header with B). */
    val sectionFocus: FocusRequester get() = tabFocus[currentRoute] ?: tabFocus.getValue("home")

    /**
     * A screen can publish a context-specific button legend here (null = the section default).
     * Wrapped in [Legend] (identity equality) on purpose: StateFlow conflates equal values, and two
     * screens with the same entries (Settings and RomM) would otherwise share one stored reference
     * and the outgoing screen's onDispose would wipe the incoming one's legend.
     */
    val legendOverride = MutableStateFlow<Legend?>(null)

    private var listener: InputManager.InputDeviceListener? = null

    fun startWatching(context: Context) {
        val manager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        refresh()
        if (listener == null) {
            listener = object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) = refresh()
                override fun onInputDeviceRemoved(deviceId: Int) = refresh()
                override fun onInputDeviceChanged(deviceId: Int) = refresh()
            }.also { manager.registerInputDeviceListener(it, null) }
        }
    }

    fun refresh() {
        _connected.value = InputDevice.getDeviceIds().any { id ->
            InputDevice.getDevice(id)?.let { isGamepad(it) } == true
        }
    }

    private fun isGamepad(device: InputDevice): Boolean {
        val sources = device.sources
        val pad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        val stick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        return (pad || stick) && !device.isVirtual
    }

    /**
     * After a section switch the focus ring is hidden; the next D-pad press only brings it
     * back (on the active tab) instead of moving anything. Set by the shell.
     */
    val pointerHidden = MutableStateFlow(false)
    private var swallowNextUp = false

    /** Call from Activity.dispatchKeyEvent, before the views see the key. */
    fun interceptKey(event: KeyEvent): Boolean {
        // Shortcut buttons are consumed entirely (down and up): if their key-up reached Compose
        // with nothing focused, it would re-initialise focus on the first tab.
        val shortcut = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> GamepadButton.PREV_TAB
            KeyEvent.KEYCODE_BUTTON_R2 -> GamepadButton.NEXT_TAB
            KeyEvent.KEYCODE_BUTTON_L1 -> GamepadButton.PREV_PANEL
            KeyEvent.KEYCODE_BUTTON_R1 -> GamepadButton.NEXT_PANEL
            KeyEvent.KEYCODE_BUTTON_X -> GamepadButton.X
            KeyEvent.KEYCODE_BUTTON_Y -> GamepadButton.Y
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadButton.FAVOURITE
            KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadButton.TOGGLE_FILTERS
            else -> null
        }
        if (shortcut != null) {
            if (event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) _connected.value = true
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) presses.tryEmit(shortcut)
            return true
        }
        val dpad = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> true
            else -> false
        }
        if (!dpad) return false
        if (event.action == KeyEvent.ACTION_DOWN && pointerHidden.value) {
            pointerHidden.value = false
            swallowNextUp = true
            presses.tryEmit(GamepadButton.FOCUS_TAB)
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && swallowNextUp) {
            swallowNextUp = false
            return true
        }
        return false
    }

    private var leftTriggerDown = false
    private var rightTriggerDown = false

    /** Some pads report ZL / ZR only as analog axes; treat crossing half travel as a press. */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK &&
            event.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD) return false
        val left = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f
        val right = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f
        if (left && !leftTriggerDown) presses.tryEmit(GamepadButton.PREV_TAB)
        if (right && !rightTriggerDown) presses.tryEmit(GamepadButton.NEXT_TAB)
        leftTriggerDown = left
        rightTriggerDown = right
        return false
    }
}

/** One published legend; plain class so two lists with equal entries are still distinct. */
class Legend(val entries: List<LegendEntry>)
