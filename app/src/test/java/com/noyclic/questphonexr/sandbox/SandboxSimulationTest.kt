package com.noyclic.questphonexr.sandbox

import com.noyclic.questphonexr.input.GamepadButtonCodes
import com.noyclic.questphonexr.input.PhysicalControllerSnapshot
import com.noyclic.questphonexr.tracking.HandSide
import org.junit.Assert.assertEquals
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
