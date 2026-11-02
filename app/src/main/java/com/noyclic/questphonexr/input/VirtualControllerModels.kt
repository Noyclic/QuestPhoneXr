package com.noyclic.questphonexr.input

import com.noyclic.questphonexr.tracking.HandSide
import kotlin.math.sqrt

/** Camera-relative pose; calibration is still required before it can become an XR pose. */
data class HandPoseSample(
    val imageX: Float,
    val imageY: Float,
    val relativeDepth: Float,
    val palmForward: Vector3f,
    val palmNormal: Vector3f
)

data class Vector3f(val x: Float, val y: Float, val z: Float) {
    fun normalized(): Vector3f {
        val length = sqrt(x * x + y * y + z * z)
        return if (length > 0.00001f) Vector3f(x / length, y / length, z / length) else ZERO
    }

    operator fun minus(other: Vector3f) = Vector3f(x - other.x, y - other.y, z - other.z)

    fun cross(other: Vector3f) = Vector3f(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    companion object {
        val ZERO = Vector3f(0f, 0f, 0f)
    }
}

/** Snapshot of one Android gamepad. A device is not assigned to a hand until explicitly mapped. */
data class PhysicalControllerSnapshot(
    val deviceId: Int,
    val deviceName: String,
    val hand: HandSide?,
    val stickX: Float,
    val stickY: Float,
    val rightStickX: Float,
    val rightStickY: Float,
    val leftTrigger: Float,
    val rightTrigger: Float,
    val pressedKeyCodes: Set<Int>,
    val updatedAtMs: Long
)

enum class ControllerInputSource {
    NONE,
    HAND_TRACKING,
    GAMEPAD,
    HAND_AND_GAMEPAD
}

/** Device-independent state for the sandbox and future per-game integration adapters. */
data class VirtualControllerState(
    val hand: HandSide,
    val isTracked: Boolean,
    val pose: HandPoseSample?,
    val trigger: Float,
    val grip: Float,
    val pinchPressed: Boolean,
    val stickX: Float,
    val stickY: Float,
    val pressedKeyCodes: Set<Int>,
    val source: ControllerInputSource,
    val physicalDeviceName: String? = null
)

data class VirtualControllerFrame(
    val timestampMs: Long,
    val left: VirtualControllerState,
    val right: VirtualControllerState
)
