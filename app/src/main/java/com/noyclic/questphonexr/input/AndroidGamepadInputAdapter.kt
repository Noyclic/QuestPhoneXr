package com.noyclic.questphonexr.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.noyclic.questphonexr.tracking.HandSide

/**
 * Captures standard Android gamepad/joystick events. Joy-Con devices can be assigned to a
 * virtual hand by device ID; the adapter does not assume a universal Joy-Con button layout.
 */
class AndroidGamepadInputAdapter(
    private val onChanged: ((List<PhysicalControllerSnapshot>) -> Unit)? = null
) {
    private data class MutableDeviceState(
        val deviceId: Int,
        var deviceName: String,
        var hand: HandSide? = null,
        var stickX: Float = 0f,
        var stickY: Float = 0f,
        var rightStickX: Float = 0f,
        var rightStickY: Float = 0f,
        var leftTrigger: Float = 0f,
        var rightTrigger: Float = 0f,
        val pressedKeyCodes: MutableSet<Int> = linkedSetOf(),
        var updatedAtMs: Long = 0L
    ) {
        fun freeze() = PhysicalControllerSnapshot(
            deviceId = deviceId,
            deviceName = deviceName,
            hand = hand,
            stickX = stickX,
            stickY = stickY,
            rightStickX = rightStickX,
            rightStickY = rightStickY,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            pressedKeyCodes = pressedKeyCodes.toSet(),
            updatedAtMs = updatedAtMs
        )
    }

    private val lock = Any()
    private val devices = linkedMapOf<Int, MutableDeviceState>()

    /** Assign a connected gamepad to the left or right virtual hand. Pass null to unassign it. */
    fun assignDevice(deviceId: Int, hand: HandSide?) {
        val snapshot = synchronized(lock) {
            val state = devices[deviceId] ?: return
            state.hand = hand
            snapshotLocked()
        }
        onChanged?.invoke(snapshot)
    }

    fun snapshots(): List<PhysicalControllerSnapshot> =
        synchronized(lock) { snapshotLocked() }

    fun onKeyEvent(event: KeyEvent, isDown: Boolean): Boolean {
        val device = InputDevice.getDevice(event.deviceId) ?: return false
        if (!isGamepad(device.sources)) return false

        val snapshot = synchronized(lock) {
            val state = stateFor(device)
            if (isDown) state.pressedKeyCodes.add(event.keyCode)
            else state.pressedKeyCodes.remove(event.keyCode)
            state.updatedAtMs = SystemClock.uptimeMillis()
            snapshotLocked()
        }
        onChanged?.invoke(snapshot)
        return true
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        val device = InputDevice.getDevice(event.deviceId) ?: return false
        if (!isGamepad(event.source) && !isGamepad(device.sources)) return false

        val snapshot = synchronized(lock) {
            val state = stateFor(device)
            state.stickX = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_X))
            state.stickY = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_Y))
            state.rightStickX = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_Z))
            state.rightStickY = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_RZ))
            state.leftTrigger = event.getAxisValue(MotionEvent.AXIS_LTRIGGER).coerceIn(0f, 1f)
            state.rightTrigger = event.getAxisValue(MotionEvent.AXIS_RTRIGGER).coerceIn(0f, 1f)
            state.updatedAtMs = SystemClock.uptimeMillis()
            snapshotLocked()
        }
        onChanged?.invoke(snapshot)
        return true
    }

    private fun stateFor(device: InputDevice): MutableDeviceState =
        devices.getOrPut(device.id) {
            MutableDeviceState(device.id, device.name ?: "Gamepad " + device.id)
        }

    private fun snapshotLocked() = devices.values.map { it.freeze() }

    private fun isGamepad(source: Int): Boolean {
        val gamepad = (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        val joystick = (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        return gamepad || joystick
    }

    private fun applyDeadZone(value: Float): Float {
        val clamped = value.coerceIn(-1f, 1f)
        return if (kotlin.math.abs(clamped) < STICK_DEAD_ZONE) 0f else clamped
    }

    private companion object {
        const val STICK_DEAD_ZONE = 0.12f
    }
}
