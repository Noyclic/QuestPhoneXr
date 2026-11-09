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
        val estimatedDistance = 2.55f * 0.18f / 0.20f
        val expectedLeftX = (0.49f - 0.5f) * estimatedDistance * 2.4f
        assertEquals(expectedLeftX, encoded.leftPose.x, 0.002f)
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

    @Test
    fun temporalPalmOrientationUsesShortestPathAndSmoothsRotation() {
        val first = trackedHand(HandSide.LEFT, 0.5f)
        val turned = rotateWorldAroundY(first, Math.toRadians(90.0).toFloat())

        val initial = EndXrHandInputEncoder.encode(HandTrackingFrame(1_000L, listOf(first))).leftPose
        val smoothed = EndXrHandInputEncoder.encode(HandTrackingFrame(1_016L, listOf(turned))).leftPose

        assertTrue(initial.active)
        assertTrue(smoothed.active)
        assertQuaternionNormalized(smoothed)
        val progressRadians = 2.0 * kotlin.math.acos(kotlin.math.abs(smoothed.qw).coerceIn(0f, 1f))
        assertTrue("Orientation should progress toward the new palm basis", progressRadians > 0.01)
        assertTrue("Orientation should not jump to the full measured rotation", progressRadians < Math.PI / 2.0)
    }

    @Test
    fun temporalPalmOrientationDoesNotAdvanceTwiceForTheSameFrameTimestamp() {
        val first = trackedHand(HandSide.LEFT, 0.5f)
        val turned = rotateWorldAroundY(first, Math.toRadians(90.0).toFloat())

        val initial = EndXrHandInputEncoder.encode(HandTrackingFrame(2_000L, listOf(first))).leftPose
        val duplicate = EndXrHandInputEncoder.encode(HandTrackingFrame(2_000L, listOf(turned))).leftPose

        assertEquals(initial.qx, duplicate.qx, 0.0001f)
        assertEquals(initial.qy, duplicate.qy, 0.0001f)
        assertEquals(initial.qz, duplicate.qz, 0.0001f)
        assertEquals(initial.qw, duplicate.qw, 0.0001f)
    }

    @Test
    fun leftAndRightPalmOrientationFiltersRemainIndependent() {
        val identityLeft = trackedHand(HandSide.LEFT, 0.4f)
        val identityRight = trackedHand(HandSide.RIGHT, 0.7f)
        val turnedLeft = rotateWorldAroundY(identityLeft, Math.toRadians(90.0).toFloat())

        EndXrHandInputEncoder.encode(
            HandTrackingFrame(3_000L, listOf(identityLeft, identityRight))
        )
        val encoded = EndXrHandInputEncoder.encode(
            HandTrackingFrame(3_016L, listOf(turnedLeft, identityRight))
        )

        assertTrue(kotlin.math.abs(encoded.leftPose.qy) > 0.01f)
        assertEquals(0f, encoded.rightPose.qy, 0.001f)
        assertQuaternionNormalized(encoded.leftPose)
        assertQuaternionNormalized(encoded.rightPose)
    }

    @Test
    fun trackingLossResetsPalmOrientationFilterBeforeReacquisition() {
        val initial = trackedHand(HandSide.LEFT, 0.5f)
        val turned = rotateWorldAroundY(initial, Math.toRadians(90.0).toFloat())

        EndXrHandInputEncoder.encode(HandTrackingFrame(4_000L, listOf(initial)))
        EndXrHandInputEncoder.encode(HandTrackingFrame(4_016L, emptyList()))
        val reacquired = EndXrHandInputEncoder.encode(
            HandTrackingFrame(4_032L, listOf(turned))
        ).leftPose

        assertTrue(reacquired.active)
        assertEquals(kotlin.math.sin(Math.PI / 4.0).toFloat(), reacquired.qy, 0.002f)
        assertEquals(kotlin.math.cos(Math.PI / 4.0).toFloat(), reacquired.qw, 0.002f)
    }

    private fun rotateWorldAroundY(hand: TrackedHand, angleRadians: Float): TrackedHand {
        val cosine = kotlin.math.cos(angleRadians)
        val sine = kotlin.math.sin(angleRadians)
        val rotated = hand.worldPoints.map { point ->
            HandPoint(
                x = cosine * point.x + sine * point.z,
                y = point.y,
                z = -sine * point.x + cosine * point.z
            )
        }
        return hand.copy(worldPoints = rotated)
    }

    @Test
    fun temporalOrientationSmoothingIsConsistentAtThirtyAndSixtySamplesPerSecond() {
        fun simulate(frameRate: Int): EndXrHandPoseSample {
            EndXrHandInputEncoder.encode(HandTrackingFrame(0L, emptyList()))
            val base = trackedHand(HandSide.LEFT, 0.5f)
            var latest = EndXrHandInputEncoder.encode(
                HandTrackingFrame(10_000L, listOf(base))
            ).leftPose
            for (frame in 1..frameRate) {
                val elapsedMs = frame * 1_000L / frameRate
                val angle = Math.toRadians(90.0 * frame / frameRate).toFloat()
                val moved = rotateWorldAroundY(base, angle)
                latest = EndXrHandInputEncoder.encode(
                    HandTrackingFrame(10_000L + elapsedMs, listOf(moved))
                ).leftPose
            }
            return latest
        }

        val at30Fps = simulate(30)
        val at60Fps = simulate(60)

        assertEquals(at30Fps.qy, at60Fps.qy, 0.035f)
        assertEquals(at30Fps.qw, at60Fps.qw, 0.035f)
    }

    @Test
    fun longOrientationSampleGapReinitializesInsteadOfBlendingAcrossLostTime() {
        val initial = trackedHand(HandSide.LEFT, 0.5f)
        val turned = rotateWorldAroundY(initial, Math.toRadians(90.0).toFloat())
        EndXrHandInputEncoder.encode(HandTrackingFrame(5_000L, listOf(initial)))

        val afterGap = EndXrHandInputEncoder.encode(
            HandTrackingFrame(5_300L, listOf(turned))
        ).leftPose

        assertEquals(kotlin.math.sin(Math.PI / 4.0).toFloat(), afterGap.qy, 0.002f)
        assertEquals(kotlin.math.cos(Math.PI / 4.0).toFloat(), afterGap.qw, 0.002f)
    }
}
