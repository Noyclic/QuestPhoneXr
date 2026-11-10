package com.noyclic.questphonexr.performance

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptivePerformanceControllerTest {
    @Test
    fun oneBadWindowDoesNotImmediatelyChangeQuality() {
        val controller = controller()

        val first = controller.observe(
            nowMs = 0L,
            renderFps = 10f,
            handTrackingFps = 8f,
            pipelineLatencyMs = 260L
        )

        assertEquals(0, first.level)
        assertEquals(30, first.renderFpsCap)
    }

    @Test
    fun sustainedOverloadGraduallyLowersRenderAndInferenceWork() {
        val controller = controller()

        controller.observe(0L, 10f, 8f, 260L)
        val reduced = controller.observe(3_000L, 10f, 8f, 260L)

        assertEquals(1, reduced.level)
        assertEquals(25, reduced.renderFpsCap)
        assertEquals(96L, reduced.minimumInferenceIntervalMs)

        val stillReduced = controller.observe(6_000L, 10f, 8f, 260L)
        assertEquals(1, stillReduced.level)

        val furtherReduced = controller.observe(9_000L, 10f, 8f, 260L)
        assertEquals(2, furtherReduced.level)
        assertEquals(20, furtherReduced.renderFpsCap)
        assertEquals(126L, furtherReduced.minimumInferenceIntervalMs)
    }

    @Test
    fun frequentSamplesCannotCauseRapidRepeatedChanges() {
        val controller = controller()

        controller.observe(0L, 10f, 8f, 260L)
        val tooSoon = controller.observe(1_000L, 10f, 8f, 260L)

        assertEquals(0, tooSoon.level)
    }

    @Test
    fun stablePerformanceRestoresOneLevelAtATime() {
        val controller = controller()
        controller.observe(0L, 10f, 8f, 260L)
        controller.observe(3_000L, 10f, 8f, 260L)
        controller.observe(6_000L, 10f, 8f, 260L)
        controller.observe(9_000L, 10f, 8f, 260L)
        assertEquals(2, controller.currentState().level)

        var state = controller.currentState()
        for (window in 1..5) {
            state = controller.observe(
                nowMs = 9_000L + window * 3_000L,
                renderFps = 30f,
                handTrackingFps = 20f,
                pipelineLatencyMs = 90L
            )
        }

        assertEquals(1, state.level)
        assertEquals(25, state.renderFpsCap)
    }

    @Test
    fun latencyAloneCanTriggerEconomyMode() {
        val controller = controller()

        controller.observe(0L, 30f, 20f, 240L)
        val state = controller.observe(3_000L, 30f, 20f, 240L)

        assertEquals(1, state.level)
    }

    @Test
    fun adaptiveCapNeverExceedsTheUserSelectedFps() {
        val preset = DevicePerformancePolicy.recommend(
            DeviceCapabilities(6_144, 256, 8, 3, 0, false)
        )
        val controller = AdaptivePerformanceController(preset, userRequestedFps = 20)

        assertEquals(20, controller.currentState().renderFpsCap)
    }


    @Test
    fun zeroInferenceIntervalDoesNotTurnEverySampleIntoAnOverload() {
        val controller = AdaptivePerformanceController(
            preset = DevicePerformancePreset(
                tier = DevicePerformanceTier.BALANCED,
                maxRenderFps = 30,
                cameraWidthPx = 640,
                cameraHeightPx = 480,
                maxTrackedHands = 1,
                minimumInferenceIntervalMs = 0L
            ),
            userRequestedFps = 30
        )

        controller.observe(
            nowMs = 0L,
            renderFps = 30f,
            handTrackingFps = 5f,
            pipelineLatencyMs = 120L
        )
        val state = controller.observe(
            nowMs = 3_000L,
            renderFps = 30f,
            handTrackingFps = 5f,
            pipelineLatencyMs = 120L
        )

        assertEquals(0, state.level)
        assertEquals(0L, state.minimumInferenceIntervalMs)
    }

    private fun controller() = AdaptivePerformanceController(
        preset = DevicePerformancePreset(
            tier = DevicePerformanceTier.BALANCED,
            maxRenderFps = 30,
            cameraWidthPx = 480,
            cameraHeightPx = 360,
            maxTrackedHands = 2,
            minimumInferenceIntervalMs = 66L
        ),
        userRequestedFps = 30
    )
}
