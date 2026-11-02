package com.noyclic.questphonexr.sandbox

import com.noyclic.questphonexr.input.GamepadButtonCodes
import com.noyclic.questphonexr.input.PhysicalControllerSnapshot
import com.noyclic.questphonexr.tracking.HandPoint
import com.noyclic.questphonexr.tracking.HandSide
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.tracking.TrackedHand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxSimulationTest {
    @Test
    fun resetCreatesAStarterSetOfGrabbableObjects() {
        val simulation = SandboxSimulation()

        val snapshot = simulation.snapshot()

        assertEquals(4, snapshot.objects.size)
        assertEquals(0, snapshot.grabbedObjectCount)
        assertEquals(1, snapshot.objects.first().id)
    }

    @Test
    fun leftControllerCanGrabAndMoveNearestObject() {
        val simulation = SandboxSimulation()
        val left = controller(
            hand = HandSide.LEFT,
            leftTrigger = 1f
        )

        simulation.update(listOf(left), 1f / 30f)

        val grabbed = simulation.snapshot()
        assertEquals(1, grabbed.grabbedObjectCount)
        val held = grabbed.objects.single { it.heldBy == HandSide.LEFT }
        assertEquals(-0.65f, held.position.x, 0.001f)
        assertEquals(-0.1f, held.position.y, 0.001f)

        simulation.update(
            listOf(left.copy(stickX = 0.5f)),
            1f / 30f
        )
        val moved = simulation.snapshot().objects.single { it.heldBy == HandSide.LEFT }
        assertEquals(-0.2f, moved.position.x, 0.001f)
    }

    @Test
    fun releasingTriggerDropsThePreviouslyHeldObject() {
        val simulation = SandboxSimulation()
        simulation.update(
            listOf(controller(HandSide.RIGHT, rightTrigger = 1f)),
            1f / 30f
        )
        assertEquals(1, simulation.snapshot().grabbedObjectCount)

        simulation.update(
            listOf(controller(HandSide.RIGHT)),
            1f / 30f
        )

        assertEquals(0, simulation.snapshot().grabbedObjectCount)
        assertTrue(simulation.snapshot().objects.none { it.heldBy == HandSide.RIGHT })
    }

    @Test
    fun digitalShoulderAndTriggerButtonsCanGrabObjects() {
        val simulation = SandboxSimulation()
        val digitalController = controller(
            hand = HandSide.LEFT,
            keys = setOf(GamepadButtonCodes.BUTTON_L1, GamepadButtonCodes.BUTTON_L2)
        )

        simulation.update(listOf(digitalController), 1f / 30f)

        assertEquals(1, simulation.snapshot().grabbedObjectCount)
    }

    @Test
    fun cameraPinchGrabsAnObjectAndUsesWristPositionAsCursor() {
        val simulation = SandboxSimulation()
        val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        points[0] = HandPoint(0.2f, 0.4f, 0f)
        val hand = TrackedHand(
            points = points,
            pinchStrength = 0.9f,
            isPinching = true,
            side = HandSide.LEFT
        )

        simulation.update(
            emptyList(),
            HandTrackingFrame(100L, listOf(hand)),
            1f / 30f
        )

        val snapshot = simulation.snapshot()
        assertTrue(snapshot.leftHand.cameraTracked)
        assertTrue(snapshot.leftHand.grabbing)
        assertEquals(-0.9f, snapshot.leftHand.cursor.x, 0.001f)
        assertEquals(1, snapshot.grabbedObjectCount)
        assertEquals(HandSide.LEFT, snapshot.objects.single { it.heldBy != null }.heldBy)
    }

    @Test
    fun openTrackedHandMovesItsCursorWithoutGrabbing() {
        val simulation = SandboxSimulation()
        val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        points[0] = HandPoint(0.8f, 0.3f, 0f)
        val hand = TrackedHand(
            points = points,
            pinchStrength = 0.1f,
            isPinching = false,
            side = HandSide.RIGHT
        )

        simulation.update(
            emptyList(),
            HandTrackingFrame(200L, listOf(hand)),
            1f / 30f
        )

        val snapshot = simulation.snapshot()
        assertTrue(snapshot.rightHand.cameraTracked)
        assertFalse(snapshot.rightHand.grabbing)
        assertEquals(0.9f, snapshot.rightHand.cursor.x, 0.001f)
        assertEquals(0, snapshot.grabbedObjectCount)
    }

    @Test
    fun cameraTrackingLossDropsHeldObjectWithoutArtificialThrow() {
        val simulation = SandboxSimulation()
        val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        points[0] = HandPoint(0.5f, 0.384f, 0f)
        val pinching = TrackedHand(
            points = points,
            pinchStrength = 0.9f,
            isPinching = true,
            side = HandSide.LEFT
        )

        simulation.update(emptyList(), HandTrackingFrame(100L, listOf(pinching)), 1f / 30f)
        assertEquals(1, simulation.snapshot().grabbedObjectCount)
        assertEquals(HandSide.LEFT, simulation.snapshot().objects.single { it.heldBy != null }.heldBy)

        simulation.update(emptyList(), HandTrackingFrame(200L, emptyList()), 1f / 30f)

        val dropped = simulation.snapshot().objects.single { it.id == 2 }
        assertEquals(null, dropped.heldBy)
        assertTrue(dropped.velocity.length() < 0.12f)
        assertEquals(0, simulation.snapshot().grabbedObjectCount)
    }

    @Test
    fun throwingAnObjectIntoTargetAwardsPointAndRespawnsIt() {
        val simulation = SandboxSimulation()
        val pickupPoints = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        pickupPoints[0] = HandPoint(0.5f, 0.5f, 0f)
        val pinching = TrackedHand(
            points = pickupPoints,
            isPinching = true,
            side = HandSide.LEFT
        )
        simulation.update(emptyList(), HandTrackingFrame(100L, listOf(pinching)), 1f / 30f)

        val targetPoints = pickupPoints.toMutableList()
        targetPoints[0] = HandPoint(0.5f, 0.895f, 0f)
        simulation.update(
            emptyList(),
            HandTrackingFrame(
                133L,
                listOf(pinching.copy(points = targetPoints))
            ),
            1f / 30f
        )
        simulation.update(
            emptyList(),
            HandTrackingFrame(
                166L,
                listOf(pinching.copy(points = targetPoints, isPinching = false))
            ),
            1f / 30f
        )

        val snapshot = simulation.snapshot()
        assertEquals(1, snapshot.score)
        assertEquals(0, snapshot.grabbedObjectCount)
        assertEquals(0.25f, snapshot.objects.single { it.type == SandboxObjectType.BALL }.position.y, 0.001f)
    }

    @Test
    fun cameraCursorSmoothingReducesSuddenLandmarkJitter() {
        val smoother = SandboxCursorSmoother(response = 0.5f)
        val first = SandboxVector3(0f, 0f, -2.5f)
        val second = SandboxVector3(1f, 0f, -2.5f)

        assertEquals(first, smoother.update(first))
        assertEquals(0.5f, smoother.update(second).x, 0.001f)
        smoother.reset()
        assertEquals(second, smoother.update(second))
    }

    @Test
    fun spawnCubeAddsAnotherObject() {
        val simulation = SandboxSimulation()
        simulation.spawnCube()

        assertEquals(5, simulation.snapshot().objects.size)
    }

    @Test
    fun frameDeltaIsClampedAndObjectsStayAboveTheFloor() {
        val simulation = SandboxSimulation()
        repeat(120) {
            simulation.update(emptyList(), 10f)
        }

        assertTrue(simulation.snapshot().objects.all { it.position.y >= -1.181f })
    }

    private fun controller(
        hand: HandSide,
        stickX: Float = 0f,
        stickY: Float = 0f,
        leftTrigger: Float = 0f,
        rightTrigger: Float = 0f,
        keys: Set<Int> = emptySet()
    ) = PhysicalControllerSnapshot(
        deviceId = if (hand == HandSide.LEFT) 1 else 2,
        deviceName = if (hand == HandSide.LEFT) "Left Joy-Con" else "Right Joy-Con",
        hand = hand,
        stickX = stickX,
        stickY = stickY,
        rightStickX = 0f,
        rightStickY = 0f,
        leftTrigger = leftTrigger,
        rightTrigger = rightTrigger,
        pressedKeyCodes = keys,
        updatedAtMs = 0L
    )
}
