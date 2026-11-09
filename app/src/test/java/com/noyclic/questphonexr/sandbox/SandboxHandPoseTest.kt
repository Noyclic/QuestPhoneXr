package com.noyclic.questphonexr.sandbox

import com.noyclic.questphonexr.tracking.HandPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxHandPoseTest {
    @Test
    fun monocularDepthMovesHandCloserWhenApparentPalmGetsWider() {
        val estimator = MonocularHandDepthEstimator()
        val narrow = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        narrow[5] = HandPoint(0.40f, 0.4f, 0f)
        narrow[17] = HandPoint(0.22f, 0.4f, 0f)
        val far = estimator.update(narrow)!!

        val wide = narrow.toMutableList().also {
            it[5] = HandPoint(0.46f, 0.4f, 0f)
            it[17] = HandPoint(0.22f, 0.4f, 0f)
        }
        val near = estimator.update(wide)!!
        assertTrue(near < far)
        assertTrue(near >= 1.0f)
    }

    @Test
    fun depthEstimatorResetsAfterTrackingLoss() {
        val estimator = MonocularHandDepthEstimator()
        val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        points[5] = HandPoint(0.4f, 0.4f, 0f)
        points[17] = HandPoint(0.22f, 0.4f, 0f)
        assertTrue(estimator.update(points) != null)
        estimator.reset()
        assertEquals(null, estimator.update(emptyList()))
    }

    @Test
    fun mapsAllTwentyOneMediaPipePointsToTheReachPlane() {
        val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        points[0] = HandPoint(0.2f, 0.4f, 0f)
        points[8] = HandPoint(0.8f, 0.3f, -0.2f)

        val mapped = SandboxHandPoseMapper.map(points)

        assertEquals(21, mapped.size)
        assertEquals(-1.836f, mapped[0].x, 0.001f)
        assertEquals(0.459f, mapped[0].y, 0.001f)
        assertEquals(-2.55f, mapped[0].z, 0.001f)
        assertEquals(1.836f, mapped[8].x, 0.001f)
        assertTrue(mapped[8].z < mapped[0].z)
    }

    @Test
    fun mapsHandsToTheFullFreeSpaceInsteadOfClampingThemToThePlatform() {
        val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        points[0] = HandPoint(0f, 0f, 0f)
        points[8] = HandPoint(1f, 1f, 0f)

        val mapped = SandboxHandPoseMapper.map(points)

        assertEquals(-3.06f, mapped[0].x, 0.001f)
        assertEquals(2.295f, mapped[0].y, 0.001f)
        assertEquals(3.06f, mapped[8].x, 0.001f)
        assertEquals(-2.295f, mapped[8].y, 0.001f)
    }

    @Test
    fun cameraRotationTransformsHandFromCameraSpaceIntoWorldSpace() {
        val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        points[8] = HandPoint(0.7f, 0.5f, 0f)
        // +90 degree yaw in the same row-major convention used by PhoneVrMath.
        val cameraToWorld = floatArrayOf(
            0f, 0f, 1f,
            0f, 1f, 0f,
            -1f, 0f, 0f
        )
        val mapped = SandboxHandPoseMapper.map(
            points,
            cameraToWorldRotation = cameraToWorld
        )
        assertEquals(-2.55f, mapped[8].x, 0.002f)
        assertEquals(0f, mapped[8].y, 0.002f)
        assertEquals(-1.224f, mapped[8].z, 0.003f)
    }

    @Test
    fun fartherHandGetsAProportionallyLargerReachVolume() {
        val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        points[8] = HandPoint(1f, 0f, 0f)

        val near = SandboxHandPoseMapper.map(points, estimatedDistanceMeters = 1.5f)
        val far = SandboxHandPoseMapper.map(points, estimatedDistanceMeters = 4.0f)

        assertTrue(far[8].x > near[8].x)
        assertTrue(far[8].y > near[8].y)
        assertTrue(far[8].z < near[8].z)
        assertTrue(far[8].x > 3.0f)
    }

    @Test
    fun ignoresLandmarksBeyondTheMediaPipeHandDefinition() {
        val mapped = SandboxHandPoseMapper.map(
            List(24) { HandPoint(0.5f, 0.5f, 0f) }
        )

        assertEquals(21, mapped.size)
    }

    @Test
    fun calibrationMovesAndScalesHandWithoutDistortingLandmarkOrder() {
        val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        points[8] = HandPoint(0.7f, 0.4f, -0.2f)
        val calibration = SandboxHandCalibration(
            horizontalOffset = 0.1f,
            verticalOffset = -0.1f,
            horizontalScale = 1.5f,
            verticalScale = 0.5f,
            relativeDepthScale = 0.8f
        )

        val mapped = SandboxHandPoseMapper.map(points, calibration)

        assertEquals(0.1f, mapped[0].x, 0.001f)
        assertEquals(-0.1f, mapped[0].y, 0.001f)
        assertEquals(-2.55f, mapped[0].z, 0.001f)
        assertEquals(
            (0.7f - 0.5f) * 2.55f * 2.4f * calibration.horizontalScale + calibration.horizontalOffset,
            mapped[8].x,
            0.002f
        )
        assertEquals(
            (0.5f - 0.4f) * 2.55f * 1.8f * calibration.verticalScale + calibration.verticalOffset,
            mapped[8].y,
            0.002f
        )
        assertEquals(-2.71f, mapped[8].z, 0.001f)
    }

    @Test
    fun calibrationValuesAreClampedToSafeInteractionBounds() {
        val safe = SandboxHandCalibration(
            horizontalOffset = 5f,
            verticalOffset = -5f,
            horizontalScale = 9f,
            verticalScale = -2f,
            relativeDepthScale = 3f
        ).normalized()

        assertEquals(SandboxHandCalibration.MAX_OFFSET_METERS, safe.horizontalOffset)
        assertEquals(-SandboxHandCalibration.MAX_OFFSET_METERS, safe.verticalOffset)
        assertEquals(SandboxHandCalibration.MAX_SCALE, safe.horizontalScale)
        assertEquals(SandboxHandCalibration.MIN_SCALE, safe.verticalScale)
        assertEquals(SandboxHandCalibration.MAX_DEPTH_SCALE, safe.relativeDepthScale)
    }

    @Test
    fun zeroRelativeDepthScaleKeepsEveryHandLandmarkOnOneDepthPlane() {
        val points = List(21) { HandPoint(0.5f, 0.5f, -0.4f) }

        val mapped = SandboxHandPoseMapper.map(
            points,
            SandboxHandCalibration(relativeDepthScale = 0f)
        )

        assertTrue(mapped.all { kotlin.math.abs(it.z + 2.55f) < 0.0001f })
    }


    @Test
    fun timestampedDepthIgnoresDuplicateAndOutOfOrderSamples() {
        val estimator = MonocularHandDepthEstimator()
        val narrow = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        narrow[5] = HandPoint(0.40f, 0.4f, 0f)
        narrow[17] = HandPoint(0.22f, 0.4f, 0f)
        val initial = estimator.update(narrow, 1_000L)!!

        val wide = narrow.toMutableList().also {
            it[5] = HandPoint(0.46f, 0.4f, 0f)
            it[17] = HandPoint(0.22f, 0.4f, 0f)
        }
        val updated = estimator.update(wide, 1_033L)!!
        assertTrue(updated < initial)

        val duplicateDifferentPose = narrow.toMutableList().also {
            it[5] = HandPoint(0.35f, 0.4f, 0f)
            it[17] = HandPoint(0.22f, 0.4f, 0f)
        }
        assertEquals(updated, estimator.update(duplicateDifferentPose, 1_033L)!!, 0f)
        assertEquals(updated, estimator.update(narrow, 1_020L)!!, 0f)
    }

    @Test
    fun timestampedDepthResponseIsIndependentOfCameraSampleRate() {
        fun landmarks(palmWidth: Float): List<HandPoint> =
            MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }.also {
                it[5] = HandPoint(0.5f, 0.4f, 0f)
                it[17] = HandPoint(0.5f - palmWidth, 0.4f, 0f)
            }

        val at30Fps = MonocularHandDepthEstimator()
        val at60Fps = MonocularHandDepthEstimator()
        at30Fps.update(landmarks(0.18f), 1_000L)
        at60Fps.update(landmarks(0.18f), 1_000L)
        var depth30 = 0f
        var depth60 = 0f
        for (frame in 1..30) {
            depth30 = at30Fps.update(landmarks(0.10f), 1_000L + frame * 33L)!!
        }
        for (frame in 1..60) {
            depth60 = at60Fps.update(landmarks(0.10f), 1_000L + frame * 16L)!!
        }
        assertEquals(depth30, depth60, 0.08f)
    }

    @Test
    fun timestampedDepthResetsItsRateHistoryAfterLongTrackingGap() {
        val estimator = MonocularHandDepthEstimator()
        val narrow = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        narrow[5] = HandPoint(0.40f, 0.4f, 0f)
        narrow[17] = HandPoint(0.22f, 0.4f, 0f)
        estimator.update(narrow, 1_000L)
        val wide = narrow.toMutableList().also {
            it[5] = HandPoint(0.46f, 0.4f, 0f)
            it[17] = HandPoint(0.22f, 0.4f, 0f)
        }
        val afterGap = estimator.update(wide, 1_400L)!!
        assertEquals(1.9125f, afterGap, 0.0001f)
    }

}
