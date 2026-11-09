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
        physicalControllers: List<PhysicalControllerSnapshot>,
        bindings: ControllerBindingProfile = ControllerBindingProfile.DEFAULT
    ): VirtualControllerFrame {
        val handsBySide = handFrame.hands
            .filter { it.side != HandSide.UNKNOWN }
            .associateBy { it.side }

        return VirtualControllerFrame(
            timestampMs = handFrame.timestampMs,
            left = mapSide(HandSide.LEFT, handsBySide[HandSide.LEFT], physicalControllers, bindings.left),
            right = mapSide(HandSide.RIGHT, handsBySide[HandSide.RIGHT], physicalControllers, bindings.right)
        )
    }

    private fun mapSide(
        side: HandSide,
        hand: TrackedHand?,
        physicalControllers: List<PhysicalControllerSnapshot>,
        binding: ControllerHandBinding
    ): VirtualControllerState {
        val physical = physicalControllers.firstOrNull { it.hand == side }
            ?: physicalControllers.firstOrNull { it.mapToBothHands }
        val pressedKeys = physical?.pressedKeyCodes.orEmpty()
        val selectedTrigger = when (binding.triggerSource) {
            TriggerAxisSource.LEFT_TRIGGER -> physical?.leftTrigger ?: 0f
            TriggerAxisSource.RIGHT_TRIGGER -> physical?.rightTrigger ?: 0f
            TriggerAxisSource.EITHER -> maxOf(
                physical?.leftTrigger ?: 0f,
                physical?.rightTrigger ?: 0f
            )
            TriggerAxisSource.NONE -> 0f
        }
        val trigger = maxOf(hand?.pinchStrength ?: 0f, selectedTrigger).coerceIn(0f, 1f)
        val digitalGrip = if (binding.gripKeyCode != 0 && binding.gripKeyCode in pressedKeys) {
            1f
        } else {
            0f
        }
        val selectedStick = when (binding.stickSource) {
            StickAxisSource.LEFT -> physical?.let { it.stickX to it.stickY } ?: (0f to 0f)
            StickAxisSource.RIGHT -> physical?.let { it.rightStickX to it.rightStickY } ?: (0f to 0f)
            StickAxisSource.NONE -> 0f to 0f
        }
        val virtualButtons = buildSet {
            fun mapButton(target: VirtualGamepadButton, sourceKeyCode: Int) {
                if (sourceKeyCode > 0 && sourceKeyCode in pressedKeys) add(target)
            }
            mapButton(VirtualGamepadButton.A, binding.buttonAKeyCode)
            mapButton(VirtualGamepadButton.B, binding.buttonBKeyCode)
            mapButton(VirtualGamepadButton.X, binding.buttonXKeyCode)
            mapButton(VirtualGamepadButton.Y, binding.buttonYKeyCode)
            mapButton(VirtualGamepadButton.MENU, binding.menuKeyCode)
        }

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
            grip = maxOf(hand?.let(::estimateGripStrength) ?: 0f, digitalGrip),
            pinchPressed = hand?.isPinching ?: false,
            stickX = selectedStick.first.coerceIn(-1f, 1f),
            stickY = selectedStick.second.coerceIn(-1f, 1f),
            pressedKeyCodes = physical?.pressedKeyCodes ?: emptySet(),
            source = source,
            physicalDeviceName = physical?.deviceName,
            physicalDeviceId = physical?.deviceId,
            virtualButtons = virtualButtons
        )
    }

    /**
     * Re-expresses mapped physical controls for the Sandbox simulation. Camera-only
     * hands are omitted; their landmarks continue through the independent hand frame.
     */
    fun toPhysicalSnapshots(frame: VirtualControllerFrame): List<PhysicalControllerSnapshot> =
        listOf(frame.left, frame.right).mapNotNull { state ->
            val deviceId = state.physicalDeviceId ?: return@mapNotNull null
            val pressed = state.pressedKeyCodes.toMutableSet()
            if (state.grip >= DIGITAL_GRIP_THRESHOLD) pressed += GamepadButtonCodes.BUTTON_L1
            PhysicalControllerSnapshot(
                deviceId = deviceId,
                deviceName = state.physicalDeviceName ?: "Android gamepad",
                hand = state.hand,
                stickX = state.stickX,
                stickY = state.stickY,
                rightStickX = 0f,
                rightStickY = 0f,
                leftTrigger = state.trigger,
                rightTrigger = 0f,
                pressedKeyCodes = pressed,
                updatedAtMs = frame.timestampMs
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
        const val DIGITAL_GRIP_THRESHOLD = 0.5f
        const val MIN_PALM_LENGTH = 0.0001f
        const val OPEN_HAND_RATIO = 2.05f
        const val GRIP_RANGE = 1.15f
    }
}
