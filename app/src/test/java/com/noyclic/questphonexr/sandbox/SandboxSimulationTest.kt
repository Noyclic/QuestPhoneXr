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
    fun handResultFreshnessUsesCallbackArrivalTimeRatherThanCaptureTimestamp() {
        // A camera frame can be captured long before MediaPipe finishes inference.
        // Once its callback arrives, freshness starts at callback delivery.
        assertFalse(isSandboxHandFrameStale(10_100L, 10_000L, 500L))
        assertTrue(isSandboxHandFrameStale(10_501L, 10_000L, 500L))
        assertTrue(isSandboxHandFrameStale(10_100L, 0L, 500L))
        assertTrue(isSandboxHandFrameStale(10_100L, 10_000L, -1L))
    }

    @Test
    fun handPoseTimeoutAllowsSlowPhoneInferenceToPublishBetweenResults() {
        assertEquals(1_865L, sandboxHandFrameStaleTimeoutMs(1.34f))
        assertEquals(900L, sandboxHandFrameStaleTimeoutMs(30f))
        assertEquals(1_200L, sandboxHandFrameStaleTimeoutMs(0f))
        assertEquals(1_200L, sandboxHandFrameStaleTimeoutMs(Float.NaN))
        assertEquals(2_200L, sandboxHandFrameStaleTimeoutMs(0.5f))
    }

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
        // Picking up preserves the cube's position relative to the palm instead of
        // snapping its center directly onto the controller cursor.
        assertEquals(-0.9f, held.position.x, 0.001f)
        assertEquals(-0.25f, held.position.y, 0.001f)
        assertEquals(-3.0f, held.position.z, 0.001f)

        simulation.update(
            listOf(left.copy(stickX = 0.5f)),
            1f / 30f
        )
        val moved = simulation.snapshot().objects.single { it.heldBy == HandSide.LEFT }
        assertEquals(1.29f, moved.position.x, 0.001f)
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
    fun cameraPinchGrabsAnObjectAtTheFingertipMidpoint() {
        val simulation = SandboxSimulation()
        val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        points[0] = HandPoint(0.2f, 0.4f, 0f)
        points[4] = HandPoint(0.46f, 0.51f, 0f)
        points[8] = HandPoint(0.54f, 0.49f, 0f)
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
        assertEquals(0f, snapshot.leftHand.cursor.x, 0.001f)
        assertEquals(0f, snapshot.leftHand.cursor.y, 0.001f)
        assertEquals(21, snapshot.leftHand.landmarks.size)
        assertEquals(
            snapshot.leftHand.cursor,
            (snapshot.leftHand.landmarks[4] + snapshot.leftHand.landmarks[8]) * 0.5f
        )
        // Browser touch uses the actual index fingertip, not the pinch midpoint.
        assertEquals(snapshot.leftHand.landmarks[8], snapshot.leftHand.touchPoint)
        assertEquals(1, snapshot.grabbedObjectCount)
        assertEquals(HandSide.LEFT, snapshot.objects.single { it.heldBy != null }.heldBy)
    }

    @Test
    fun browserInteractionModeKeepsPinchForClicksInsteadOfGrabbingObjects() {
        val simulation = SandboxSimulation()
        val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        points[0] = HandPoint(0.2f, 0.4f, 0f)
        points[4] = HandPoint(0.46f, 0.51f, 0f)
        points[8] = HandPoint(0.54f, 0.49f, 0f)
        val hand = TrackedHand(
            points = points,
            pinchStrength = 0.9f,
            isPinching = true,
            side = HandSide.LEFT
        )
        val frame = HandTrackingFrame(120L, listOf(hand))

        simulation.setBrowserInteractionMode(true)
        simulation.update(emptyList(), frame, 1f / 30f)
        val browserSnapshot = simulation.snapshot()

        assertTrue(browserSnapshot.leftHand.pinching)
        assertFalse(browserSnapshot.leftHand.grabbing)
        assertEquals(0, browserSnapshot.grabbedObjectCount)

        simulation.setBrowserInteractionMode(false)
        simulation.update(emptyList(), HandTrackingFrame(136L, listOf(hand)), 1f / 30f)
        assertTrue(simulation.snapshot().leftHand.grabbing)
        assertEquals(1, simulation.snapshot().grabbedObjectCount)
    }

    @Test
    fun curledThreeFingerPowerGripGrabsWithoutPinching() {
        val simulation = SandboxSimulation()
        val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        points[0] = HandPoint(0.51f, 0.73f, 0f)
        val fingers = listOf(
            5 to HandPoint(0.44f, 0.52f, 0f),
            9 to HandPoint(0.48f, 0.50f, 0f),
            13 to HandPoint(0.53f, 0.50f, 0f),
            17 to HandPoint(0.58f, 0.52f, 0f)
        )
        for ((mcpIndex, mcp) in fingers) {
            points[mcpIndex] = mcp
            points[mcpIndex + 1] = HandPoint(mcp.x, mcp.y - 0.025f, 0f)
            points[mcpIndex + 2] = HandPoint(mcp.x + 0.01f, mcp.y - 0.01f, 0f)
            // Keep the distal segment above the detector's minimum relative chain length.
            points[mcpIndex + 3] = HandPoint(mcp.x + 0.02f, mcp.y + 0.005f, 0f)
        }
        val hand = TrackedHand(
            points = points,
            pinchStrength = 0.1f,
            isPinching = false,
            side = HandSide.LEFT
        )

        simulation.update(emptyList(), HandTrackingFrame(150L, listOf(hand)), 1f / 30f)

        val snapshot = simulation.snapshot()
        assertTrue(snapshot.leftHand.grabbing)
        assertEquals(1, snapshot.grabbedObjectCount)
        assertEquals(HandSide.LEFT, snapshot.objects.single { it.heldBy != null }.heldBy)
    }

    @Test
    fun openTrackedHandMovesItsCursorWithoutGrabbing() {
        val simulation = SandboxSimulation()
        val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        points[0] = HandPoint(0.8f, 0.3f, 0f)
        points[8] = HandPoint(0.72f, 0.22f, 0f)
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
        assertEquals(0.22f * 2.55f * 2.4f, snapshot.rightHand.cursor.x, 0.002f)
        assertEquals(0.28f * 2.55f * 1.8f, snapshot.rightHand.cursor.y, 0.002f)
        assertEquals(0, snapshot.grabbedObjectCount)
    }

    @Test
    fun openFingertipCanPushBallWithoutGrabbingIt() {
        val simulation = SandboxSimulation()
        val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        points[0] = HandPoint(0.5f, 0.55f, 0f)
        points[5] = HandPoint(0.57f, 0.46f, 0f)
        points[17] = HandPoint(0.43f, 0.46f, 0f)
        // The index fingertip maps close to the starter ball at (0, 0.25, -3.15).
        points[8] = HandPoint(0.5f, 0.4576f, 0f)
        val openHand = TrackedHand(
            points = points,
            pinchStrength = 0.1f,
            isPinching = false,
            side = HandSide.LEFT
        )

        simulation.update(
            emptyList(),
            HandTrackingFrame(175L, listOf(openHand)),
            1f / 30f
        )

        val snapshot = simulation.snapshot()
        val ball = snapshot.objects.single { it.type == SandboxObjectType.BALL }
        assertTrue(snapshot.leftHand.cameraTracked)
        assertFalse(snapshot.leftHand.grabbing)
        assertEquals(0, snapshot.grabbedObjectCount)
        assertTrue("fingertip contact should push the free ball", ball.position.z > -3.15f)
    }

    @Test
    fun cameraTrackedInteractionCursorStaysImmediateWhileRenderPoseInterpolates() {
        val simulation = SandboxSimulation()
        fun trackedHand(indexX: Float): TrackedHand {
            val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
            points[5] = HandPoint(0.59f, 0.4f, 0f)
            points[17] = HandPoint(0.41f, 0.4f, 0f)
            points[8] = HandPoint(indexX, 0.5f, 0f)
            return TrackedHand(points = points, side = HandSide.RIGHT)
        }

        simulation.update(
            emptyList(),
            HandTrackingFrame(100L, listOf(trackedHand(0.6f))),
            1f / 30f
        )
        simulation.update(
            emptyList(),
            HandTrackingFrame(133L, listOf(trackedHand(0.8f))),
            1f / 30f
        )

        val expectedX = (0.8f - 0.5f) * 2.55f * 2.4f
        val handSnapshot = simulation.snapshot().rightHand
        assertEquals(expectedX, handSnapshot.cursor.x, 0.002f)
        assertTrue(handSnapshot.landmarks[8].x > (0.6f - 0.5f) * 2.55f * 2.4f)
        assertTrue(handSnapshot.landmarks[8].x < expectedX)
    }

    @Test
    fun joyConStickMovesPlayerWhileCameraTrackedHandRemainsIndependent() {
        val simulation = SandboxSimulation()
        val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        points[8] = HandPoint(0.5f, 0.5f, 0f)
        val cameraHand = TrackedHand(
            points = points,
            pinchStrength = 0.1f,
            isPinching = false,
            side = HandSide.LEFT
        )
        val leftJoyCon = controller(
            hand = HandSide.LEFT,
            stickX = 0.75f,
            stickY = -0.5f
        )

        simulation.update(
            listOf(leftJoyCon),
            HandTrackingFrame(300L, listOf(cameraHand)),
            1f / 30f
        )

        val snapshot = simulation.snapshot()
        val moved = snapshot.leftHand
        assertTrue(moved.cameraTracked)
        assertEquals(0.06f, snapshot.playerPosition.x, 0.001f)
        assertEquals(-0.04f, snapshot.playerPosition.z, 0.001f)
        // The hand follows the camera in local space; the stick moves the player, not the hand.
        // The camera-tracked hand moves with the player in world space, but the stick
        // does not take control of its local pose.
        assertEquals(snapshot.playerPosition.x, moved.cursor.x, 0.001f)
        assertEquals(snapshot.playerPosition.z - 2.55f, moved.cursor.z, 0.001f)
        assertEquals(moved.cursor, moved.landmarks[8])
    }

    @Test
    fun unassignedJoyConCanStillDriveSandboxLocomotion() {
        val simulation = SandboxSimulation()
        val unassigned = controller(
            hand = HandSide.LEFT,
            stickX = 0.5f,
            stickY = -1f
        ).copy(hand = null)

        simulation.update(listOf(unassigned), 0.05f)

        val position = simulation.snapshot().playerPosition
        assertEquals(0.06f, position.x, 0.001f)
        assertEquals(-0.12f, position.z, 0.001f)
    }

    @Test
    fun rightJoyConCanMovePlayerWhenLeftControllerIsUnavailable() {
        val simulation = SandboxSimulation()
        simulation.update(
            listOf(controller(hand = HandSide.RIGHT, stickX = -1f, stickY = 1f)),
            0.05f
        )

        val position = simulation.snapshot().playerPosition
        assertEquals(-0.12f, position.x, 0.001f)
        assertEquals(0.12f, position.z, 0.001f)
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
        targetPoints[4] = HandPoint(0.5f, 0.895f, 0f)
        targetPoints[8] = HandPoint(0.5f, 0.895f, 0f)
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


    @Test
    fun handPoseInterpolatorBlendsLandmarksBetweenCameraSamples() {
        val interpolator = SandboxHandPoseInterpolator()
        fun hand(x: Float, pinching: Boolean = false) = TrackedHand(
            points = MutableList(21) { HandPoint(x, x, x) },
            pinchStrength = if (pinching) 0.9f else 0.1f,
            isPinching = pinching,
            side = HandSide.RIGHT,
            trackingId = 7L
        )

        val first = hand(0.1f)
        val second = hand(0.3f, pinching = true)
        assertEquals(0.1f, interpolator.sample(first, 100L, 100L, 100L)!!.points[8].x, 0.0001f)

        val rendered = interpolator.sample(second, 166L, 166L, 166L)!!

        assertTrue(rendered.points[8].x > 0.1f)
        assertTrue(rendered.points[8].x < 0.3f)
        assertTrue(rendered.isPinching)
        assertEquals(0.9f, rendered.pinchStrength, 0.0001f)
        assertEquals(0.3f, second.points[8].x, 0.0001f)
    }

    @Test
    fun handPoseKeepsAdvancingAcrossRenderTicksWithoutNewCameraResult() {
        val interpolator = SandboxHandPoseInterpolator()
        fun hand(x: Float) = TrackedHand(
            points = MutableList(21) { HandPoint(x, 0f, 0f) },
            side = HandSide.RIGHT,
            trackingId = 9L
        )

        interpolator.sample(hand(0.1f), 100L, 100L, 100L)
        val cameraResult = hand(0.3f)
        val firstRender = interpolator.sample(cameraResult, 166L, 166L, 166L)!!
        val nextRender = interpolator.sample(cameraResult, 166L, 166L, 182L)!!

        assertTrue(nextRender.points[0].x > firstRender.points[0].x)
        assertTrue(nextRender.points[0].x < cameraResult.points[0].x)
    }

    @Test
    fun handPoseInterpolatorBoundsForwardPrediction() {
        val interpolator = SandboxHandPoseInterpolator()
        fun hand(x: Float) = TrackedHand(
            points = MutableList(21) { HandPoint(x, 0f, 0f) },
            side = HandSide.LEFT,
            trackingId = 4L
        )

        interpolator.sample(hand(0.1f), 100L, 100L, 100L)
        val predicted = interpolator.sample(hand(0.3f), 200L, 200L, 1_000L)!!

        assertTrue(predicted.points[0].x > 0.3f)
        assertTrue(predicted.points[0].x <= 0.38f)
    }

    @Test
    fun handPoseInterpolatorDoesNotBlendAcrossHandIdentityChanges() {
        val interpolator = SandboxHandPoseInterpolator()
        fun hand(x: Float, id: Long) = TrackedHand(
            points = MutableList(21) { HandPoint(x, 0f, 0f) },
            side = HandSide.RIGHT,
            trackingId = id
        )

        interpolator.sample(hand(0.1f, 1L), 100L, 100L, 100L)
        val replacement = hand(0.8f, 2L)
        val rendered = interpolator.sample(replacement, 140L, 140L, 140L)!!

        assertEquals(0.8f, rendered.points[0].x, 0.0001f)
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
