package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertTrue
import org.junit.Test

class OneEuroHandResponsivenessTest {
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
