package com.noyclic.questphonexr.input

import com.noyclic.questphonexr.tracking.HandPoint
import com.noyclic.questphonexr.tracking.HandSide
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.tracking.TrackedHand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualControllerMapperTest {

    private val mapper = VirtualControllerMapper()

    @Test
    fun mapsLeftAndRightHandsWithoutDependingOnDetectionOrder() {
        val left = hand(HandSide.LEFT, pinch = 0.9f)
        val right = hand(HandSide.RIGHT, pinch = 0.2f)

        val frame = mapper.map(HandTrackingFrame(123L, listOf(right, left)), emptyList())

        assertEquals(HandSide.LEFT, frame.left.hand)
        assertEquals(0.9f, frame.left.trigger, 0.001f)
        assertEquals(HandSide.RIGHT, frame.right.hand)
        assertEquals(0.2f, frame.right.trigger, 0.001f)
    }

    @Test
    fun ignoresUnknownHandSideInsteadOfGuessing() {
        val frame = mapper.map(
            HandTrackingFrame(123L, listOf(hand(HandSide.UNKNOWN, pinch = 1f))),
            emptyList()
        )

        assertFalse(frame.left.isTracked)
        assertFalse(frame.right.isTracked)
        assertEquals(ControllerInputSource.NONE, frame.left.source)
    }

    @Test
    fun digitalShoulderButtonsCanActAsTriggerAndGrip() {
        val physical = PhysicalControllerSnapshot(
            deviceId = 12,
            deviceName = "Joy-Con (R)",
            hand = HandSide.RIGHT,
            stickX = 0f,
            stickY = 0f,
            rightStickX = 0f,
            rightStickY = 0f,
            leftTrigger = 0f,
            rightTrigger = 0f,
            pressedKeyCodes = setOf(
                GamepadButtonCodes.BUTTON_R1,
                GamepadButtonCodes.BUTTON_R2
            ),
            updatedAtMs = 240L
        )

        val frame = mapper.map(HandTrackingFrame(250L, emptyList()), listOf(physical))

        assertEquals(1f, frame.right.trigger, 0f)
        assertEquals(1f, frame.right.grip, 0f)
    }

    @Test
    fun combinesAssignedGamepadAndHandTracking() {
        val physical = PhysicalControllerSnapshot(
            deviceId = 7,
            deviceName = "Joy-Con (R)",
            hand = HandSide.RIGHT,
            stickX = 0.4f,
            stickY = -0.3f,
            rightStickX = 0f,
            rightStickY = 0f,
            leftTrigger = 0f,
            rightTrigger = 0.7f,
            pressedKeyCodes = setOf(96),
            updatedAtMs = 120L
        )

        val frame = mapper.map(
            HandTrackingFrame(123L, listOf(hand(HandSide.RIGHT, pinch = 0.5f))),
            listOf(physical)
        )

        assertEquals(ControllerInputSource.HAND_AND_GAMEPAD, frame.right.source)
        assertEquals(0.7f, frame.right.trigger, 0.001f)
        assertEquals(0.4f, frame.right.stickX, 0.001f)
        assertTrue(96 in frame.right.pressedKeyCodes)
        assertNotNull(frame.right.pose)
    }

    private fun hand(side: HandSide, pinch: Float): TrackedHand {
        val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        points[0] = HandPoint(0.5f, 0.8f, 0f)
        points[5] = HandPoint(0.4f, 0.5f, 0f)
        points[9] = HandPoint(0.5f, 0.45f, 0f)
        points[17] = HandPoint(0.6f, 0.5f, 0f)
        points[8] = HandPoint(0.52f, 0.4f, 0f)
        points[12] = HandPoint(0.54f, 0.38f, 0f)
        points[16] = HandPoint(0.56f, 0.4f, 0f)
        points[20] = HandPoint(0.58f, 0.43f, 0f)
        return TrackedHand(
            points = points,
            pinchStrength = pinch,
            isPinching = pinch >= 0.5f,
            side = side,
            worldPoints = points
        )
    }

    @Test
    fun oneDualMappedGamepadDrivesBothHandsWithIndependentTriggersAndSticks() {
        val physical = PhysicalControllerSnapshot(
            deviceId = 99,
            deviceName = "Generic Bluetooth Gamepad",
            hand = null,
            stickX = 0.15f,
            stickY = -0.25f,
            rightStickX = 0.65f,
            rightStickY = 0.75f,
            leftTrigger = 0.35f,
            rightTrigger = 0.85f,
            pressedKeyCodes = setOf(
                GamepadButtonCodes.BUTTON_A,
                GamepadButtonCodes.BUTTON_B,
                GamepadButtonCodes.BUTTON_X,
                GamepadButtonCodes.BUTTON_Y,
                GamepadButtonCodes.BUTTON_L1,
                GamepadButtonCodes.BUTTON_R1
            ),
            updatedAtMs = 500L,
            mapToBothHands = true
        )

        val frame = mapper.map(HandTrackingFrame(510L, emptyList()), listOf(physical))

        assertEquals(ControllerInputSource.GAMEPAD, frame.left.source)
        assertEquals(ControllerInputSource.GAMEPAD, frame.right.source)
        assertEquals(0.35f, frame.left.trigger, 0.001f)
        assertEquals(0.85f, frame.right.trigger, 0.001f)
        assertEquals(0.15f, frame.left.stickX, 0.001f)
        assertEquals(0.65f, frame.right.stickX, 0.001f)
        assertTrue(VirtualGamepadButton.X in frame.left.virtualButtons)
        assertTrue(VirtualGamepadButton.A in frame.right.virtualButtons)

        val simulationControllers = mapper.toPhysicalSnapshots(frame)
        assertEquals(2, simulationControllers.size)
        assertEquals(HandSide.LEFT, simulationControllers[0].hand)
        assertEquals(HandSide.RIGHT, simulationControllers[1].hand)
    }

    @Test
    fun customBindingsCanSwapTriggersSticksGripAndFaceButtons() {
        val physical = PhysicalControllerSnapshot(
            deviceId = 101,
            deviceName = "Custom HID Pad",
            hand = HandSide.LEFT,
            stickX = -0.2f,
            stickY = 0.1f,
            rightStickX = 0.7f,
            rightStickY = -0.6f,
            leftTrigger = 0.1f,
            rightTrigger = 0.9f,
            pressedKeyCodes = setOf(
                GamepadButtonCodes.BUTTON_X,
                GamepadButtonCodes.BUTTON_B
            ),
            updatedAtMs = 600L
        )
        val customLeft = ControllerHandBinding.LEFT_DEFAULT.copy(
            triggerSource = TriggerAxisSource.RIGHT_TRIGGER,
            gripKeyCode = GamepadButtonCodes.BUTTON_X,
            stickSource = StickAxisSource.RIGHT,
            buttonAKeyCode = GamepadButtonCodes.BUTTON_B,
            buttonBKeyCode = 0
        )
        val bindings = ControllerBindingProfile(
            left = customLeft,
            right = ControllerHandBinding.RIGHT_DEFAULT
        )

        val left = mapper.map(
            HandTrackingFrame(610L, emptyList()),
            listOf(physical),
            bindings
        ).left

        assertEquals(0.9f, left.trigger, 0.001f)
        assertEquals(1f, left.grip, 0f)
        assertEquals(0.7f, left.stickX, 0.001f)
        assertEquals(-0.6f, left.stickY, 0.001f)
        assertTrue(VirtualGamepadButton.A in left.virtualButtons)
        assertFalse(VirtualGamepadButton.B in left.virtualButtons)
        // Original Android key codes remain available to game-specific adapters.
        assertTrue(GamepadButtonCodes.BUTTON_B in left.pressedKeyCodes)
    }

    @Test
    fun digitalTriggerKeyStillWorksWhenControllerDoesNotExposeAnAnalogTriggerAxis() {
        val physical = PhysicalControllerSnapshot(
            deviceId = 8,
            deviceName = "Digital Trigger Pad",
            hand = HandSide.RIGHT,
            stickX = 0f,
            stickY = 0f,
            rightStickX = 0f,
            rightStickY = 0f,
            leftTrigger = 0f,
            rightTrigger = 0f,
            pressedKeyCodes = setOf(GamepadButtonCodes.BUTTON_R2),
            updatedAtMs = 700L
        )

        val frame = mapper.map(HandTrackingFrame(710L, emptyList()), listOf(physical))

        assertEquals(1f, frame.right.trigger, 0f)
    }

    @Test
    fun defaultFullPadFaceButtonsFollowLeftXYRightABLayout() {
        val physical = PhysicalControllerSnapshot(
            deviceId = 102,
            deviceName = "Generic Xbox Layout Pad",
            hand = null,
            stickX = 0f,
            stickY = 0f,
            rightStickX = 0f,
            rightStickY = 0f,
            leftTrigger = 0f,
            rightTrigger = 0f,
            pressedKeyCodes = setOf(
                GamepadButtonCodes.BUTTON_A,
                GamepadButtonCodes.BUTTON_B,
                GamepadButtonCodes.BUTTON_X,
                GamepadButtonCodes.BUTTON_Y
            ),
            updatedAtMs = 800L,
            mapToBothHands = true
        )

        val frame = mapper.map(HandTrackingFrame(810L, emptyList()), listOf(physical))

        assertTrue(VirtualGamepadButton.X in frame.left.virtualButtons)
        assertTrue(VirtualGamepadButton.Y in frame.left.virtualButtons)
        assertFalse(VirtualGamepadButton.A in frame.left.virtualButtons)
        assertFalse(VirtualGamepadButton.B in frame.left.virtualButtons)
        assertTrue(VirtualGamepadButton.A in frame.right.virtualButtons)
        assertTrue(VirtualGamepadButton.B in frame.right.virtualButtons)
        assertFalse(VirtualGamepadButton.X in frame.right.virtualButtons)
        assertFalse(VirtualGamepadButton.Y in frame.right.virtualButtons)
    }

    @Test
    fun sideSpecificPadOverridesDualMappedPadWithoutLosingOtherHand() {
        val sharedPad = PhysicalControllerSnapshot(
            deviceId = 200,
            deviceName = "Full Size Pad",
            hand = null,
            stickX = 0.1f,
            stickY = 0.2f,
            rightStickX = 0.3f,
            rightStickY = 0.4f,
            leftTrigger = 0.25f,
            rightTrigger = 0.5f,
            pressedKeyCodes = emptySet(),
            updatedAtMs = 900L,
            mapToBothHands = true
        )
        val rightJoyCon = PhysicalControllerSnapshot(
            deviceId = 201,
            deviceName = "Right Joy-Con",
            hand = HandSide.RIGHT,
            stickX = -0.8f,
            stickY = 0.6f,
            rightStickX = 0f,
            rightStickY = 0f,
            leftTrigger = 0f,
            rightTrigger = 0.9f,
            pressedKeyCodes = emptySet(),
            updatedAtMs = 901L
        )

        val frame = mapper.map(
            HandTrackingFrame(910L, emptyList()),
            listOf(sharedPad, rightJoyCon)
        )

        assertEquals(200, frame.left.physicalDeviceId)
        assertEquals(201, frame.right.physicalDeviceId)
        assertEquals(0.25f, frame.left.trigger, 0.001f)
        assertEquals(0.9f, frame.right.trigger, 0.001f)
        assertEquals(0.1f, frame.left.stickX, 0.001f)
        assertEquals(-0.8f, frame.right.stickX, 0.001f)
    }
}
