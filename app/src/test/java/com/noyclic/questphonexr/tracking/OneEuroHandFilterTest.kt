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
    fun responsiveFingertipIndicesFollowFastMotionMoreCloselyThanPalmLandmarks() {
        val defaultFilter = OneEuroHandFilter()
        val responsiveFilter = OneEuroHandFilter(
            responsiveLandmarkA = 4,
            responsiveLandmarkB = 8,
            responsiveMinCutoffHz = 3.2f,
            responsiveBeta = 0.65f
        )
        val initial = MutableList(21) { HandPoint(0.3f, 0.5f, 0f) }.also {
            it[4] = HandPoint(0.1f, 0.5f, 0f)
            it[8] = HandPoint(0.1f, 0.5f, 0f)
        }
        val moved = initial.toMutableList().also {
            it[4] = HandPoint(0.5f, 0.5f, 0f)
            it[8] = HandPoint(0.5f, 0.5f, 0f)
        }
        defaultFilter.update(initial, 1_000L)
        responsiveFilter.update(initial, 1_000L)

        val normal = defaultFilter.update(moved, 1_016L)
        val responsive = responsiveFilter.update(moved, 1_016L)

        assertTrue(
            "thumb tip should follow fast motion more closely",
            responsive[4].x > normal[4].x + 0.02f
        )
        assertTrue(
            "index tip should follow fast motion more closely",
            responsive[8].x > normal[8].x + 0.02f
        )
        assertEquals("palm landmarks retain the default filter", normal[0].x, responsive[0].x, 0.0001f)
    }

    @Test
    fun responsiveFilterStillDampsSmallStationaryTipJitter() {
        val filter = OneEuroHandFilter(
            responsiveLandmarkA = 4,
            responsiveLandmarkB = 8,
            responsiveMinCutoffHz = 3.2f,
            responsiveBeta = 0.65f
        )
        val initial = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        filter.update(initial, 1_000L)
        val outputs = listOf(0.505f, 0.495f, 0.505f, 0.495f).mapIndexed { index, x ->
            filter.update(
                initial.toMutableList().also {
                    it[4] = HandPoint(x, 0.5f, 0f)
                    it[8] = HandPoint(x, 0.5f, 0f)
                },
                1_016L + index * 16L
            )[4].x
        }

        val outputRange = outputs.maxOrNull()!! - outputs.minOrNull()!!
        assertTrue("responsive tips should still damp sub-pixel jitter", outputRange < 0.01f)
    }

    @Test
    fun imageSpaceJumpLimiterBoundsExtremeSingleSampleMovement() {
        val filter = OneEuroHandFilter(
            minCutoffHz = 100f,
            beta = 0f,
            maxLandmarkSpeedPerSecond = 2f
        )
        filter.update(listOf(HandPoint(0.1f, 0.5f, 0f)), 1_000L)

        val moved = filter.update(listOf(HandPoint(0.9f, 0.5f, 0f)), 1_050L).single()

        // 2 normalized units/s × 50 ms = 0.10 maximum raw XY movement.
        assertTrue(moved.x > 0.1f)
        assertTrue(moved.x <= 0.201f)
    }

    @Test
    fun responsiveTipsHaveAHigherJumpBudgetThanPalmLandmarks() {
        val filter = OneEuroHandFilter(
            minCutoffHz = 100f,
            beta = 0f,
            responsiveLandmarkA = 4,
            responsiveLandmarkB = 8,
            responsiveMinCutoffHz = 100f,
            responsiveBeta = 0f,
            maxLandmarkSpeedPerSecond = 2f,
            responsiveMaxLandmarkSpeedPerSecond = 6f
        )
        val initial = List(21) { HandPoint(0.1f, 0.5f, 0f) }
        val moved = List(21) { HandPoint(0.9f, 0.5f, 0f) }
        filter.update(initial, 1_000L)

        val result = filter.update(moved, 1_050L)

        assertTrue(
            "thumb/index tips should keep their larger motion budget",
            result[4].x > result[0].x + 0.12f
        )
    }

    @Test
    fun metricSpaceLimiterBoundsDisplayedWorldLandmarkJumps() {
        val worldFilter = OneEuroHandFilter(
            minCutoffHz = 100f,
            beta = 0f,
            maxLandmarkSpeedPerSecond = 1f
        )
        worldFilter.update(listOf(HandPoint(0f, 0f, 0f)), 1_000L)

        val result = worldFilter.update(listOf(HandPoint(0.5f, 0f, 0f)), 1_050L).single()

        assertTrue("world-space display jumps should be bounded to 0.05 m per 50 ms", result.x in 0f..0.051f)
    }

    @Test
    fun metricSpaceFingertipsKeepTheirLargerArticulationBudget() {
        val worldFilter = OneEuroHandFilter(
            minCutoffHz = 100f,
            beta = 0f,
            responsiveLandmarkA = 4,
            responsiveLandmarkB = 8,
            responsiveMinCutoffHz = 100f,
            responsiveBeta = 0f,
            maxLandmarkSpeedPerSecond = 1f,
            responsiveMaxLandmarkSpeedPerSecond = 3f
        )
        val initial = List(21) { HandPoint(0f, 0f, 0f) }
        val moved = List(21) { HandPoint(0.5f, 0f, 0f) }
        worldFilter.update(initial, 1_000L)

        val result = worldFilter.update(moved, 1_050L)

        assertTrue("thumb/index tips should retain a higher articulation budget",
            result[4].x > result[0].x + 0.07f)
        assertTrue(result[4].x <= 0.151f)
    }

    @Test
    fun speedLimiterRemainsDisabledForDefaultFilters() {
        val filter = OneEuroHandFilter(minCutoffHz = 100f, beta = 0f)
        filter.update(listOf(HandPoint(0.1f, 0.5f, 0f)), 1_000L)

        val moved = filter.update(listOf(HandPoint(0.9f, 0.5f, 0f)), 1_050L).single()

        assertTrue("default filters must preserve existing unbounded behavior", moved.x > 0.8f)
    }


    @Test
    fun derivativeDeadzonePreventsStationaryJitterFromBoostingAdaptiveCutoff() {
        val withoutDeadzone = OneEuroHandFilter(
            minCutoffHz = 1.6f, beta = 0.8f, derivativeCutoffHz = 1.0f
        )
        val withDeadzone = OneEuroHandFilter(
            minCutoffHz = 1.6f, beta = 0.8f, derivativeCutoffHz = 1.0f,
            derivativeDeadzone = 0.35f
        )
        withoutDeadzone.update(listOf(HandPoint(0.5f, 0.5f, 0f)), 1_000L)
        withDeadzone.update(listOf(HandPoint(0.5f, 0.5f, 0f)), 1_000L)
        val without = ArrayList<Float>()
        val with = ArrayList<Float>()
        repeat(24) { index ->
            val noise = if (index % 2 == 0) 0.008f else -0.008f
            val timestamp = 1_033L + index * 33L
            without += withoutDeadzone.update(listOf(HandPoint(0.5f + noise, 0.5f, 0f)), timestamp).single().x
            with += withDeadzone.update(listOf(HandPoint(0.5f + noise, 0.5f, 0f)), timestamp).single().x
        }
        val rawJitterRange = without.maxOrNull()!! - without.minOrNull()!!
        val dampedJitterRange = with.maxOrNull()!! - with.minOrNull()!!
        assertTrue("stationary jitter should not raise the adaptive cutoff",
            dampedJitterRange < rawJitterRange)
    }

    @Test
    fun responsiveFingerTipSetIncludesMiddleRingAndPinkyTips() {
        val ordinary = OneEuroHandFilter(minCutoffHz = 1.8f, beta = 0.25f)
        val responsive = OneEuroHandFilter(
            minCutoffHz = 1.8f, beta = 0.25f,
            responsiveLandmarkIndices = intArrayOf(12, 16, 20),
            responsiveMinCutoffHz = 3.0f, responsiveBeta = 0.72f
        )
        val initial = List(21) { HandPoint(0.1f, 0.5f, 0f) }
        val moved = initial.toMutableList().also {
            it[12] = HandPoint(0.5f, 0.5f, 0f)
            it[16] = HandPoint(0.5f, 0.5f, 0f)
            it[20] = HandPoint(0.5f, 0.5f, 0f)
        }
        ordinary.update(initial, 1_000L)
        responsive.update(initial, 1_000L)
        val normalPose = ordinary.update(moved, 1_016L)
        val responsivePose = responsive.update(moved, 1_016L)
        for (tip in intArrayOf(12, 16, 20)) {
            assertTrue("finger tip $tip should follow articulation responsively",
                responsivePose[tip].x > normalPose[tip].x + 0.02f)
        }
        assertEquals(normalPose[0].x, responsivePose[0].x, 0.0001f)
    }

    @Test
    fun derivativeDeadzoneDoesNotSuppressFastIntentionalMovement() {
        val filter = OneEuroHandFilter(
            minCutoffHz = 1.65f, beta = 0.28f, derivativeCutoffHz = 0.80f,
            derivativeDeadzone = 0.18f
        )
        filter.update(listOf(HandPoint(0.1f, 0.5f, 0f)), 1_000L)
        val moved = filter.update(listOf(HandPoint(0.3f, 0.5f, 0f)), 1_033L).single()
        assertTrue("fast hand motion must remain visible", moved.x > 0.15f)
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
