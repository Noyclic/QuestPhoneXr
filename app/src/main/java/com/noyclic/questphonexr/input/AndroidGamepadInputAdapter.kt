package com.noyclic.questphonexr.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.noyclic.questphonexr.tracking.HandSide
import kotlin.math.abs

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

    /** Scans Android's currently connected input devices and returns recognized gamepads. */
    fun refreshConnectedDevices(): List<PhysicalControllerSnapshot> {
        val snapshot = synchronized(lock) {
            val connected = InputDevice.getDeviceIds()
                .mapNotNull { id -> InputDevice.getDevice(id) }
                .filter { isGamepad(it.sources) }
            val connectedIds = connected.map { it.id }.toSet()
            devices.keys.retainAll(connectedIds)
            connected.forEach(::stateFor)
            snapshotLocked()
        }
        onChanged?.invoke(snapshot)
        return snapshot
    }

    /** Assign one connected gamepad to the left or right virtual hand. Null releases it. */
    fun assignDevice(deviceId: Int, hand: HandSide?) {
        val snapshot = synchronized(lock) {
            val state = devices[deviceId] ?: return
            if (hand != null) {
                // One physical controller per virtual hand prevents nondeterministic selection.
                devices.values
                    .filter { it.deviceId != deviceId && it.hand == hand }
                    .forEach { it.hand = null }
            }
            state.hand = hand
            snapshotLocked()
        }
        onChanged?.invoke(snapshot)
    }

    /** Remove state immediately after Android reports that an input device has disconnected. */
    fun onDeviceRemoved(deviceId: Int) {
        val snapshot = synchronized(lock) {
            devices.remove(deviceId)
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
            val leftStick = StickAxisFilter.normalize(
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y)
            )
            val rightStick = StickAxisFilter.normalize(
                axisWithFallback(event, MotionEvent.AXIS_RX, MotionEvent.AXIS_Z),
                axisWithFallback(event, MotionEvent.AXIS_RY, MotionEvent.AXIS_RZ)
            )
            state.stickX = leftStick.x
            state.stickY = leftStick.y
            state.rightStickX = rightStick.x
            state.rightStickY = rightStick.y
            state.leftTrigger = maxOf(
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE)
            ).coerceIn(0f, 1f)
            state.rightTrigger = maxOf(
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS)
            ).coerceIn(0f, 1f)
            state.updatedAtMs = SystemClock.uptimeMillis()
            snapshotLocked()
        }
        onChanged?.invoke(snapshot)
        return true
    }

    private fun axisWithFallback(event: MotionEvent, preferredAxis: Int, fallbackAxis: Int): Float {
        val preferred = event.getAxisValue(preferredAxis)
        return if (abs(preferred) > AXIS_FALLBACK_EPSILON) {
            preferred
        } else {
            event.getAxisValue(fallbackAxis)
        }
    }

    private fun stateFor(device: InputDevice): MutableDeviceState =
        devices.getOrPut(device.id) {
            MutableDeviceState(device.id, device.name ?: "Gamepad " + device.id)
        }.also {
            it.deviceName = device.name ?: it.deviceName
        }

    private fun snapshotLocked() = devices.values.map { it.freeze() }

    private fun isGamepad(source: Int): Boolean {
        val gamepad = (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        val joystick = (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        return gamepad || joystick
    }

    private companion object {
        const val AXIS_FALLBACK_EPSILON = 0.001f
    }
}
