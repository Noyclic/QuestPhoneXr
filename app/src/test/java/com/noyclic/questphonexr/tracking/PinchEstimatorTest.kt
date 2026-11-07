package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchEstimatorTest {
    @Test
    fun highConfidenceFullPinchActivatesOnFirstObservation() {
        val estimator = PinchEstimator()
        assertTrue(estimator.update(points(pinching = true), 1_000L).isPinching)
        assertTrue(estimator.update(points(pinching = true), 1_016L).isPinching)
    }

    @Test
    fun partialClosureStillNeedsConsistentSamplesInsteadOfOneFrameJitter() {
        val estimator = PinchEstimator()
        val partial = points(pinching = false).toMutableList().also {
            it[4] = HandPoint(0.465f, 0.5f, 0f)
            it[8] = HandPoint(0.535f, 0.5f, 0f)
        }

        assertFalse(estimator.update(partial, 1_000L).isPinching)
        assertFalse(estimator.update(partial, 1_016L).isPinching)
        assertTrue(estimator.update(partial, 1_032L).isPinching)
    }

    @Test
    fun pinchReleasesWithSeparateOffThreshold() {
        val estimator = PinchEstimator()
        estimator.update(points(pinching = true), 1_000L)
        assertTrue(estimator.update(points(pinching = true), 1_016L).isPinching)

        assertFalse(estimator.update(points(pinching = false), 1_032L).isPinching)
    }

    @Test
    fun invalidKeyLandmarksCannotLeavePinchStuck() {
        val estimator = PinchEstimator()
        estimator.update(points(pinching = true), 1_000L)
        assertTrue(estimator.update(points(pinching = true), 1_016L).isPinching)

        val invalid = points(pinching = true).toMutableList().also {
            it[8] = HandPoint(Float.NaN, 0.5f, 0f)
        }
        val released = estimator.update(invalid, 1_032L)
        assertFalse(released.isPinching)
        assertTrue(released.strength == 0f)
        assertTrue(released.normalizedDistanceRatio.isInfinite())
    }

    @Test
    fun slowerInferenceCadenceStillUsesElapsedTimeForSmoothing() {
        val estimator = PinchEstimator()
        estimator.update(points(pinching = true), 1_000L)
        val signal = estimator.update(points(pinching = true), 1_150L)

        assertTrue(signal.isPinching)
    }

    @Test
    fun partialClosureCanActivateWithoutRequiringPerfectTipOverlap() {
        val estimator = PinchEstimator(smoothing = 1f)
        val points = points(pinching = false).toMutableList().also {
            // Palm width is 0.20; tip distance is 0.07 (0.35 palm widths).
            it[4] = HandPoint(0.465f, 0.5f, 0f)
            it[8] = HandPoint(0.535f, 0.5f, 0f)
        }

        val signal = estimator.update(points, 1_000L)

        assertTrue("a deliberate partial closure should cross the activation threshold", signal.isPinching)
        assertTrue(kotlin.math.abs(signal.normalizedDistanceRatio - 0.35f) < 0.0001f)
    }

    @Test
    fun relaxedThresholdDoesNotActivateOnAnOpenHand() {
        val estimator = PinchEstimator(smoothing = 1f)
        val points = points(pinching = false).toMutableList().also {
            // Tip distance remains 0.55 palm widths: open, not a pinch.
            it[4] = HandPoint(0.445f, 0.5f, 0f)
            it[8] = HandPoint(0.555f, 0.5f, 0f)
        }

        val signal = estimator.update(points, 1_000L)

        assertFalse(signal.isPinching)
        assertTrue(kotlin.math.abs(signal.normalizedDistanceRatio - 0.55f) < 0.0001f)
    }

    @Test
    fun pinchStrengthIsInvariantToUniformImageScale() {
        val estimator = PinchEstimator(smoothing = 1f)
        val small = points(pinching = false).toMutableList().also {
            it[5] = HandPoint(0.6f, 0.4f, 0f)
            it[17] = HandPoint(0.4f, 0.4f, 0f)
            it[4] = HandPoint(0.465f, 0.5f, 0f)
            it[8] = HandPoint(0.535f, 0.5f, 0f)
        }
        val large = small.map { HandPoint(it.x * 2f, it.y * 2f, it.z) }

        val smallStrength = estimator.update(small, 1_000L).strength
        estimator.reset()
        val largeStrength = estimator.update(large, 1_000L).strength

        assertTrue(kotlin.math.abs(smallStrength - largeStrength) < 0.0001f)
    }

    @Test
    fun overflowingFiniteLandmarksCannotCreateFalsePinchOrPoisonNextSample() {
        val estimator = PinchEstimator(smoothing = 1f)
        val overflowing = points(pinching = true).toMutableList().also {
            it[5] = HandPoint(Float.MAX_VALUE, 0.4f, 0f)
            it[17] = HandPoint(-Float.MAX_VALUE, 0.4f, 0f)
        }

        repeat(3) { index ->
            val signal = estimator.update(overflowing, 1_000L + index * 16L)
            assertFalse(signal.isPinching)
            assertTrue(signal.strength == 0f)
            assertTrue(signal.normalizedDistanceRatio.isInfinite())
        }

        val recovered = estimator.update(points(pinching = true), 1_064L)
        assertTrue(recovered.isPinching)
        assertTrue(recovered.normalizedDistanceRatio.isFinite())
    }

    private fun points(pinching: Boolean): List<HandPoint> {
        val result = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        result[5] = HandPoint(0.6f, 0.4f, 0f)
        result[17] = HandPoint(0.4f, 0.4f, 0f)
        if (pinching) {
            result[4] = HandPoint(0.49f, 0.5f, 0f)
            result[8] = HandPoint(0.49f, 0.5f, 0f)
        } else {
            result[4] = HandPoint(0.42f, 0.52f, 0f)
            result[8] = HandPoint(0.58f, 0.48f, 0f)
        }
        return result
    }
}
