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
}
