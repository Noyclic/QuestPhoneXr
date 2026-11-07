package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchEstimatorTest {
    @Test
    fun pinchActivatesAfterTimeAwareStrengthPassesOnThreshold() {
        val estimator = PinchEstimator()
        assertFalse(estimator.update(points(pinching = true), 1_000L).isPinching)
        assertTrue(estimator.update(points(pinching = true), 1_016L).isPinching)
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
    }

    @Test
    fun slowerInferenceCadenceStillUsesElapsedTimeForSmoothing() {
        val estimator = PinchEstimator()
        estimator.update(points(pinching = true), 1_000L)
        val signal = estimator.update(points(pinching = true), 1_150L)

        assertTrue(signal.isPinching)
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
