package com.noyclic.questphonexr.input

import com.noyclic.questphonexr.tracking.HandPoint
import com.noyclic.questphonexr.tracking.HandSide
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.tracking.TrackedHand
import kotlin.math.sqrt

/**
 * Combines camera hand tracking and assigned Android gamepads into stable left/right states.
 * The hand pose remains camera-relative until the calibration stage is implemented.
 */
class VirtualControllerMapper {

    fun map(
        handFrame: HandTrackingFrame,
        physicalControllers: List<PhysicalControllerSnapshot>
    ): VirtualControllerFrame {
        val handsBySide = handFrame.hands
            .filter { it.side != HandSide.UNKNOWN }
            .associateBy { it.side }

        return VirtualControllerFrame(
            timestampMs = handFrame.timestampMs,
            left = mapSide(HandSide.LEFT, handsBySide[HandSide.LEFT], physicalControllers),
            right = mapSide(HandSide.RIGHT, handsBySide[HandSide.RIGHT], physicalControllers)
        )
    }

    private fun mapSide(
        side: HandSide,
        hand: TrackedHand?,
        physicalControllers: List<PhysicalControllerSnapshot>
    ): VirtualControllerState {
        val physical = physicalControllers.firstOrNull { it.hand == side }
        val trigger = maxOf(
            hand?.pinchStrength ?: 0f,
            physical?.leftTrigger ?: 0f,
            physical?.rightTrigger ?: 0f
        ).coerceIn(0f, 1f)

        val source = when {
            hand != null && physical != null -> ControllerInputSource.HAND_AND_GAMEPAD
            hand != null -> ControllerInputSource.HAND_TRACKING
            physical != null -> ControllerInputSource.GAMEPAD
            else -> ControllerInputSource.NONE
        }

        return VirtualControllerState(
            hand = side,
            isTracked = hand != null,
            pose = hand?.let(::toPose),
            trigger = trigger,
            grip = hand?.let(::estimateGripStrength) ?: 0f,
            pinchPressed = hand?.isPinching ?: false,
            stickX = physical?.stickX ?: 0f,
            stickY = physical?.stickY ?: 0f,
            pressedKeyCodes = physical?.pressedKeyCodes ?: emptySet(),
            source = source,
            physicalDeviceName = physical?.deviceName
        )
    }

    private fun toPose(hand: TrackedHand): HandPoseSample? {
        val wrist = hand.points.getOrNull(0) ?: return null
        val orientationPoints = if (hand.worldPoints.size >= LANDMARK_COUNT) {
            hand.worldPoints
        } else {
            hand.points
        }
        val poseWrist = orientationPoints.getOrNull(0) ?: return null
        val indexMcp = orientationPoints.getOrNull(5) ?: return null
        val middleMcp = orientationPoints.getOrNull(9) ?: return null
        val pinkyMcp = orientationPoints.getOrNull(17) ?: return null

        val acrossPalm = vector(indexMcp) - vector(pinkyMcp)
        val forward = (vector(middleMcp) - vector(poseWrist)).normalized()
        val normal = acrossPalm.cross(forward).normalized()

        return HandPoseSample(
            imageX = wrist.x.coerceIn(0f, 1f),
            imageY = wrist.y.coerceIn(0f, 1f),
            relativeDepth = wrist.z,
            palmForward = forward,
            palmNormal = normal
        )
    }

    /** Estimates fist/grip separately from the thumb/index pinch trigger. */
    private fun estimateGripStrength(hand: TrackedHand): Float {
        val points = if (hand.worldPoints.size >= LANDMARK_COUNT) hand.worldPoints else hand.points
        val wrist = points.getOrNull(0) ?: return 0f
        val middleMcp = points.getOrNull(9) ?: return 0f
        val palmLength = distance(wrist, middleMcp).coerceAtLeast(MIN_PALM_LENGTH)
        val tipIndices = intArrayOf(8, 12, 16, 20)
        val tipDistances = tipIndices
            .map { points.getOrNull(it) }
            .filterNotNull()
            .map { distance(wrist, it) / palmLength }
        if (tipDistances.size != tipIndices.size) return 0f

        val averageRatio = tipDistances.average().toFloat()
        return ((OPEN_HAND_RATIO - averageRatio) / GRIP_RANGE).coerceIn(0f, 1f)
    }

    private fun vector(point: HandPoint) = Vector3f(point.x, point.y, point.z)

    private fun distance(a: HandPoint, b: HandPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private companion object {
        const val LANDMARK_COUNT = 21
        const val MIN_PALM_LENGTH = 0.0001f
        const val OPEN_HAND_RATIO = 2.05f
        const val GRIP_RANGE = 1.15f
    }
}
