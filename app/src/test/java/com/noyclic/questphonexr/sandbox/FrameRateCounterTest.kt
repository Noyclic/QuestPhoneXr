package com.noyclic.questphonexr.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateCounterTest {
    @Test
    fun measuresThirtyRenderedFramesPerSecond() {
        val counter = FrameRateCounter()
        var result: Float? = null
        val interval = (1_000_000_000L + 29L) / 30L
        for (frame in 0L..30L) {
            result = counter.onFrame(frame * interval)
        }

        assertEquals(30f, result!!, 0.01f)
    }

    @Test
    fun doesNotPublishPartialWindowMeasurements() {
        val counter = FrameRateCounter()

        assertNull(counter.onFrame(1_000L))
        assertNull(counter.onFrame(500_000_000L))
        assertNull(counter.latestFramesPerSecond)
    }

    @Test
    fun resetClearsThePreviousMeasurement() {
        val counter = FrameRateCounter()
        for (frame in 0L..30L) {
            counter.onFrame(frame * (1_000_000_000L / 30L))
        }

        counter.reset()

        assertNull(counter.latestFramesPerSecond)
        assertNull(counter.onFrame(7_000_000_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnEmptyMeasurementWindow() {
        FrameRateCounter(0L)
    }
}
