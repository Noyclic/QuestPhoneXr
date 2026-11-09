package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandPinchTrackerTest {
    @Test
    fun pinchHistoryFollowsHandednessWhenMediaPipeChangesResultOrder() {
        val tracker = HandPinchTracker()
        val openLeft = HandPinchSample(HandSide.LEFT, points(pinch = false))
        val closedRight = HandPinchSample(HandSide.RIGHT, points(pinch = true))

        tracker.updateFrame(listOf(openLeft, closedRight), 1_000L)
        val swapped = tracker.updateFrame(listOf(closedRight, openLeft), 1_033L)

        assertTrue("Right hand should retain its own pinch history", swapped[0].isPinching)
        assertFalse("Left hand must not inherit the right hand's pinch", swapped[1].isPinching)
        assertTrue(swapped[0].strength > swapped[1].strength)
    }

    @Test
    fun aMissingHandIsResetBeforeItIsReacquired() {
        val tracker = HandPinchTracker()
        val openLeft = HandPinchSample(HandSide.LEFT, points(pinch = false))
        val closedRight = HandPinchSample(HandSide.RIGHT, points(pinch = true))
        val openRight = HandPinchSample(HandSide.RIGHT, points(pinch = false))

        tracker.updateFrame(listOf(closedRight), 2_000L)
        assertTrue(tracker.updateFrame(listOf(closedRight), 2_033L).single().isPinching)

        tracker.updateFrame(listOf(openLeft), 2_066L)
        val reacquired = tracker.updateFrame(listOf(openRight), 2_099L).single()

        assertFalse(reacquired.isPinching)
        assertEquals(0f, reacquired.strength, 0.0001f)
    }

    @Test
    fun malformedLandmarksReleasePinchInsteadOfLeavingItLatched() {
        val tracker = HandPinchTracker()
        val closedRight = HandPinchSample(HandSide.RIGHT, points(pinch = true))
        val invalidRight = HandPinchSample(
            HandSide.RIGHT,
            points(pinch = true).toMutableList().also {
                it[4] = HandPoint(Float.NaN, Float.NaN, 0f)
            }
        )

        tracker.updateFrame(listOf(closedRight), 3_000L)
        assertTrue(tracker.updateFrame(listOf(closedRight), 3_033L).single().isPinching)

        val invalid = tracker.updateFrame(listOf(invalidRight), 3_066L).single()
        assertFalse(invalid.isPinching)
        assertEquals(0f, invalid.strength, 0.0001f)
    }

    @Test
    fun pinchSmoothingHasComparableResponseAtThirtyAndSixtyHertz() {
        fun simulate(frameRate: Int): PinchSignal {
            val estimator = PinchEstimator()
            val stepMs = 1_000L / frameRate
            var signal = estimator.update(points(pinch = true), 10_000L)
            var elapsed = stepMs
            while (elapsed <= 1_000L) {
                signal = estimator.update(points(pinch = true), 10_000L + elapsed)
                elapsed += stepMs
            }
            return signal
        }

        val at30Hz = simulate(30)
        val at60Hz = simulate(60)

        assertEquals(at30Hz.strength, at60Hz.strength, 0.025f)
        assertEquals(at30Hz.isPinching, at60Hz.isPinching)
    }

    @Test
    fun duplicateOrOutOfOrderFramesDoNotAdvancePinchState() {
        val estimator = PinchEstimator()
        val initial = estimator.update(points(pinch = false), 20_000L)
        val changed = points(pinch = true)

        val duplicate = estimator.update(changed, 20_000L)
        val outOfOrder = estimator.update(changed, 19_990L)

        assertEquals(initial.strength, duplicate.strength, 0.0001f)
        assertEquals(initial.strength, outOfOrder.strength, 0.0001f)
        assertFalse(duplicate.isPinching)
        assertFalse(outOfOrder.isPinching)
    }

    private fun points(pinch: Boolean): List<HandPoint> =
        MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }.also {
            it[5] = HandPoint(0.60f, 0.45f, 0f)
            it[17] = HandPoint(0.40f, 0.45f, 0f)
            it[4] = HandPoint(if (pinch) 0.49f else 0.20f, 0.50f, 0f)
            it[8] = HandPoint(if (pinch) 0.51f else 0.80f, 0.50f, 0f)
        }
}
