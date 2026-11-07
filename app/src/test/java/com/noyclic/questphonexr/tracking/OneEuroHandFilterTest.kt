package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneEuroHandFilterTest {
    @Test
    fun stationaryLandmarkJitterIsReduced() {
        val filter = OneEuroHandFilter()
        filter.update(listOf(HandPoint(0.5f, 0.5f, 0f)), 1000L)
        val outputs = listOf(0.51f, 0.49f, 0.51f, 0.49f, 0.5f).mapIndexed { index, x ->
            filter.update(listOf(HandPoint(x, 0.5f, 0f)), 1016L + index * 16L).single().x
        }
        val rawRange = 0.02f
        val outputRange = outputs.maxOrNull()!! - outputs.minOrNull()!!
        assertTrue("Expected less jitter, got range $outputRange", outputRange < rawRange)
    }

    @Test
    fun filterFollowsFastMovementMoreResponsively() {
        val filter = OneEuroHandFilter()
        filter.update(listOf(HandPoint(0.1f, 0.5f, 0f)), 1000L)
        val slow = filter.update(listOf(HandPoint(0.12f, 0.5f, 0f)), 1016L).single().x
        assertTrue(slow > 0.1f)

        val fastFilter = OneEuroHandFilter()
        fastFilter.update(listOf(HandPoint(0.1f, 0.5f, 0f)), 1000L)
        val fast = fastFilter.update(listOf(HandPoint(0.5f, 0.5f, 0f)), 1016L).single().x
        assertTrue("Fast motion should receive a higher adaptive cutoff than slow motion", fast > slow + 0.05f)
    }

    @Test
    fun timestampGapResetsInsteadOfDraggingOldPose() {
        val filter = OneEuroHandFilter()
        filter.update(listOf(HandPoint(0.1f, 0.2f, 0f)), 1000L)
        val afterGap = filter.update(listOf(HandPoint(0.8f, 0.7f, 0f)), 1400L).single()
        assertEquals(0.8f, afterGap.x, 0.0001f)
        assertEquals(0.7f, afterGap.y, 0.0001f)
    }

    @Test
    fun emptyInputClearsFilterState() {
        val filter = OneEuroHandFilter()
        filter.update(listOf(HandPoint(0.1f, 0.2f, 0f)), 1000L)
        assertTrue(filter.update(emptyList(), 1016L).isEmpty())
        assertEquals(0.9f, filter.update(listOf(HandPoint(0.9f, 0.8f, 0f)), 1032L).single().x, 0.0001f)
    }
}
