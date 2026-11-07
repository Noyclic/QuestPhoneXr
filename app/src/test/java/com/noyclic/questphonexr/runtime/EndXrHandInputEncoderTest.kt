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
        assertTrue(encoded.leftJoints.active)
        assertTrue(encoded.rightJoints.active)
        assertEquals(EndXrHandJointSample.FLOAT_COUNT, encoded.leftJoints.values.size)
        assertEquals(EndXrHandJointSample.BLOB_SIZE, encoded.leftJoints.toBlob().size)
        val restored = EndXrHandJointSample.fromBlob(encoded.leftJoints.toBlob())
        assertTrue(restored.active)
        assertEquals(encoded.leftJoints.values.toList(), restored.values.toList())
        val estimatedDistance = 2.55f * 0.18f / 0.20f
        val expectedLeftX = (0.4875f - 0.5f) * estimatedDistance * 2.4f
        assertEquals(expectedLeftX, encoded.leftPose.x, 0.002f)
        assertTrue(encoded.rightPose.x > encoded.leftPose.x)
        assertTrue(encoded.leftPose.z < -0.25f)
        assertTrue(encoded.leftPose.z >= -6f)
        assertQuaternionNormalized(encoded.leftPose)
        assertQuaternionNormalized(encoded.rightPose)
    }

    @Test
    fun endXrSkeletonUsesRawMediaPipeWorldLandmarksNotRenderSmoothedPoints() {
        val baseline = trackedHand(HandSide.LEFT, centerX = 0.5f)
        val shiftedRawWorld = baseline.worldPoints.toMutableList().also {
            it[8] = HandPoint(it[8].x + 0.018f, it[8].y, it[8].z)
        }
        val rawObservation = baseline.copy(
            rawWorldPoints = shiftedRawWorld,
            rawTimestampMs = 100L
        )

        val encoded = EndXrHandInputEncoder.encode(
            HandTrackingFrame(100L, listOf(rawObservation))
        )

        // OpenXR index fingertip is joint 10; X is the first local position value.
        val fingertipX = encoded.leftJoints.values[10 * EndXrHandJointSample.FLOATS_PER_JOINT]
        assertTrue("fresh raw tip observation must reach the kinematic filter", kotlin.math.abs(fingertipX) > 0.0001f)
    }

    @Test
    fun refusesPoseWhenMetricPalmBasisIsDegenerateOrWorldLandmarksAreMissing() {
        val missingWorld = trackedHand(HandSide.LEFT, centerX = 0.5f).copy(
            worldPoints = emptyList(),
            rawWorldPoints = emptyList()
        )
        val degenerateWorld = List(21) { HandPoint(0f, 0f, 0f) }
        val degenerate = trackedHand(HandSide.RIGHT, centerX = 0.5f).copy(
            worldPoints = degenerateWorld,
            rawWorldPoints = degenerateWorld
        )

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
        assertFalse(lost.leftJoints.active)
        assertFalse(lost.rightJoints.active)
    }

    @Test
    fun mapsCameraPinchAndPowerGripIntoControllerActionRows() {
        val pinching = trackedHand(HandSide.LEFT, centerX = 0.5f).copy(
            pinchStrength = 0.83f,
            isPinching = true
        )
        val gripping = trackedHand(HandSide.RIGHT, centerX = 0.75f).copy(
            worldPoints = curledWorldPoints(3)
        )
        val encoded = EndXrHandInputEncoder.encode(HandTrackingFrame(5L, listOf(pinching, gripping)))

        assertEquals(6, encoded.leftRow.size)
        assertEquals(6, encoded.rightRow.size)
        assertEquals(1f, encoded.leftRow[0], 0f)
        assertEquals(0.83f, encoded.leftRow[1], 0.0001f)
        assertEquals(0f, encoded.leftRow[2], 0f)
        assertEquals(1f, encoded.rightRow[0], 0f)
        assertEquals(0f, encoded.rightRow[1], 0f)
        assertEquals(1f, encoded.rightRow[2], 0f)
        assertEquals(0f, encoded.leftRow[3], 0f)
        assertEquals(0f, encoded.leftRow[4], 0f)
        assertEquals(0f, encoded.leftRow[5], 0f)
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

    private fun curledWorldPoints(curledCount: Int): List<HandPoint> {
        val wrist = HandPoint(0f, 0f, 0f)
        val points = MutableList(21) { wrist }
        val bases = listOf(
            HandPoint(0.035f, 0.030f, 0f), HandPoint(0f, 0.055f, 0f),
            HandPoint(-0.025f, 0.045f, 0f), HandPoint(-0.040f, 0.025f, 0f)
        )
        val mcps = intArrayOf(5, 9, 13, 17)
        for (finger in mcps.indices) {
            val mcp = bases[finger]
            points[mcps[finger]] = mcp
            points[mcps[finger] + 1] = HandPoint(mcp.x, mcp.y + 0.018f, 0f)
            points[mcps[finger] + 2] = if (finger < curledCount) HandPoint(mcp.x + 0.012f, mcp.y + 0.016f, 0f)
                else HandPoint(mcp.x, mcp.y + 0.036f, 0f)
            points[mcps[finger] + 3] = if (finger < curledCount) HandPoint(mcp.x + 0.006f, mcp.y + 0.006f, 0f)
                else HandPoint(mcp.x, mcp.y + 0.060f, 0f)
        }
        return points
    }

    private fun assertQuaternionNormalized(pose: EndXrHandPoseSample) {
        val norm = kotlin.math.sqrt(
            pose.qx * pose.qx + pose.qy * pose.qy +
                pose.qz * pose.qz + pose.qw * pose.qw
        )
        assertEquals(1f, norm, 0.001f)
    }
}
