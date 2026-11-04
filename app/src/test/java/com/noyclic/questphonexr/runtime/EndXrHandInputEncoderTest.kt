package com.noyclic.questphonexr.runtime

import com.noyclic.questphonexr.tracking.HandPoint
import com.noyclic.questphonexr.tracking.HandSide
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.tracking.TrackedHand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EndXrHandInputEncoderTest {
    @Before
    fun resetState() {
        EndXrHandInputEncoder.encode(HandTrackingFrame(0L, emptyList()))
    }

    @Test
    fun encodesIndependentLeftAndRightApproximatePalmPoses() {
        val left = trackedHand(HandSide.LEFT, centerX = 0.5f)
        val right = trackedHand(HandSide.RIGHT, centerX = 0.75f)

        val encoded = EndXrHandInputEncoder.encode(
            HandTrackingFrame(1L, listOf(left, right))
        )

        assertTrue(encoded.leftPose.active)
        assertTrue(encoded.rightPose.active)
        assertEquals(0f, encoded.leftPose.x, 0.02f)
        assertTrue(encoded.rightPose.x > encoded.leftPose.x)
        assertTrue(encoded.leftPose.z < -0.25f)
        assertTrue(encoded.leftPose.z >= -6f)
        assertQuaternionNormalized(encoded.leftPose)
        assertQuaternionNormalized(encoded.rightPose)
    }

    @Test
    fun refusesPoseWhenMetricPalmBasisIsDegenerateOrWorldLandmarksAreMissing() {
        val missingWorld = trackedHand(HandSide.LEFT, centerX = 0.5f).copy(worldPoints = emptyList())
        val degenerateWorld = List(21) { HandPoint(0f, 0f, 0f) }
        val degenerate = trackedHand(HandSide.RIGHT, centerX = 0.5f).copy(worldPoints = degenerateWorld)

        val encoded = EndXrHandInputEncoder.encode(
            HandTrackingFrame(2L, listOf(missingWorld, degenerate))
        )

        assertEquals(EndXrHandPoseSample.EMPTY, encoded.leftPose)
        assertEquals(EndXrHandPoseSample.EMPTY, encoded.rightPose)
    }

    @Test
    fun noTrackedHandsClearsBothEstimatedPoses() {
        val first = EndXrHandInputEncoder.encode(
            HandTrackingFrame(3L, listOf(trackedHand(HandSide.LEFT, 0.5f)))
        )
        assertTrue(first.leftPose.active)

        val lost = EndXrHandInputEncoder.encode(HandTrackingFrame(4L, emptyList()))
        assertFalse(lost.leftPose.active)
        assertFalse(lost.rightPose.active)
    }

    private fun trackedHand(side: HandSide, centerX: Float): TrackedHand {
        val points = MutableList(21) { HandPoint(centerX, 0.5f, 0f) }
        points[0] = HandPoint(centerX, 0.62f, 0f)
        points[5] = HandPoint(centerX + 0.10f, 0.44f, 0f)
        points[9] = HandPoint(centerX, 0.40f, 0f)
        points[13] = HandPoint(centerX - 0.05f, 0.43f, 0f)
        points[17] = HandPoint(centerX - 0.10f, 0.44f, 0f)
        points[4] = HandPoint(centerX - 0.02f, 0.52f, 0f)
        points[8] = HandPoint(centerX + 0.02f, 0.48f, 0f)

        val world = MutableList(21) { HandPoint(0f, 0f, 0f) }
        world[0] = HandPoint(0f, 0f, 0f)
        world[5] = HandPoint(0.04f, 0.02f, 0f)
        world[9] = HandPoint(0f, 0.05f, 0f)
        world[17] = HandPoint(-0.04f, 0.02f, 0f)
        return TrackedHand(
            points = points,
            side = side,
            worldPoints = world
        )
    }

    private fun assertQuaternionNormalized(pose: EndXrHandPoseSample) {
        val norm = kotlin.math.sqrt(
            pose.qx * pose.qx + pose.qy * pose.qy +
                pose.qz * pose.qz + pose.qw * pose.qw
        )
        assertEquals(1f, norm, 0.001f)
    }
}
