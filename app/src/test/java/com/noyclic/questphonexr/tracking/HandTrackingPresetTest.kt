package com.noyclic.questphonexr.tracking

import com.noyclic.questphonexr.performance.DevicePerformanceTier
import org.junit.Assert.assertEquals
import org.junit.Test

class HandTrackingPresetTest {
    @Test
    fun economyPresetReducesHandsResolutionAndInferenceRate() {
        val preset = HandTrackingPreset.POWER_SAVER
        assertEquals(1, preset.maxTrackedHands)
        assertEquals(320, preset.cameraWidthPx)
        assertEquals(240, preset.cameraHeightPx)
        assertEquals(100L, preset.minimumInferenceIntervalMs)
    }

    @Test
    fun balancedAndResponsivePreserveTwoHandTracking() {
        assertEquals(2, HandTrackingPreset.BALANCED.maxTrackedHands)
        assertEquals(2, HandTrackingPreset.RESPONSIVE.maxTrackedHands)
        assertEquals(66L, HandTrackingPreset.BALANCED.minimumInferenceIntervalMs)
        assertEquals(50L, HandTrackingPreset.RESPONSIVE.minimumInferenceIntervalMs)
    }

    @Test
    fun hardwareTierSelectsConservativeDefault() {
        assertEquals(HandTrackingPreset.POWER_SAVER,
            HandTrackingPreset.recommend(DevicePerformanceTier.ECONOMY))
        assertEquals(HandTrackingPreset.BALANCED,
            HandTrackingPreset.recommend(DevicePerformanceTier.BALANCED))
        assertEquals(HandTrackingPreset.RESPONSIVE,
            HandTrackingPreset.recommend(DevicePerformanceTier.CAPABLE))
    }

    @Test
    fun unknownStoredPresetFallsBackToBalanced() {
        assertEquals(HandTrackingPreset.BALANCED, HandTrackingPreset.fromStoredName("UNKNOWN"))
    }
}
