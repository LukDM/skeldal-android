package cz.gates.skeldal.settings

import android.view.InputDevice

/** Connected physical gamepads, for display in the launcher. */
object Gamepads {
    fun connectedNames(): List<String> = InputDevice.getDeviceIds().toList().mapNotNull { id ->
        val d = InputDevice.getDevice(id) ?: return@mapNotNull null
        val gamepad = (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        // like the engine: a real gamepad has a stick or a D-pad hat (built-in key devices of
        // some phones claim to be gamepads but have no axes)
        val hasAxes = d.getMotionRange(android.view.MotionEvent.AXIS_X) != null ||
            d.getMotionRange(android.view.MotionEvent.AXIS_HAT_X) != null
        if (!d.isVirtual && gamepad && hasAxes) d.name else null
    }.distinct()
}
