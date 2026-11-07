package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertTrue
import org.junit.Test

class OneEuroHandResponsivenessTest {
    @Test
    fun stationaryLandmarkJitterIsDamped() {
        val filter = OneEuroHandFilter()
        filter.update(listOf(HandPoint(0.5f, 0.5f, 0f)), 1_000L)

        var output = HandPoint(0.5f, 0.5f, 0f)
        repeat(24) { index ->
            val noise = if (index % 2 == 0) 0.025f else -0.025f
            output = filter.update(
                listOf(HandPoint(0.5f + noise, 0.5f, 0f)),
                1_033L + index * 33L
            ).single()
        }

        assertTrue("Expected alternating camera jitter to be damped", kotlin.math.abs(output.x - 0.5f) < 0.012f)
    }

    @Test
    fun constantMovementDoesNotAccumulateVisibleFilterLag() {
        val filter = OneEuroHandFilter()
        var output = HandPoint(0f, 0.5f, 0f)
        filter.update(listOf(output), 1_000L)

        repeat(30) { index ->
            val timestamp = 1_033L + index * 33L
            val rawX = (index + 1) * 0.6f / 30f
            output = filter.update(listOf(HandPoint(rawX, 0.5f, 0f)), timestamp).single()
        }

        assertTrue("Expected responsive filtering", 0.6f - output.x < 0.06f)
    }
}
