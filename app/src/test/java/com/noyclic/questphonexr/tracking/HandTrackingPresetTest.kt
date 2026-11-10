package com.noyclic.questphonexr.tracking

import com.noyclic.questphonexr.performance.DevicePerformanceTier
import com.google.mediapipe.tasks.core.Delegate
import org.junit.Assert.assertEquals
import org.junit.Test

class HandTrackingPresetTest {
    @Test
    fun superEconomyUsesOneHandAndLowestWorkload() {
        val preset = HandTrackingPreset.SUPER_ECONOMY
        assertEquals(1, preset.maxTrackedHands)
        assertEquals(256, preset.cameraWidthPx)
        assertEquals(192, preset.cameraHeightPx)
        assertEquals(150L, preset.minimumInferenceIntervalMs)
    }

    @Test
    fun economyPresetKeepsTwoHandsAtLowWorkload() {
        val preset = HandTrackingPreset.POWER_SAVER
        assertEquals(2, preset.maxTrackedHands)
        assertEquals(640, preset.cameraWidthPx)
        assertEquals(480, preset.cameraHeightPx)
        assertEquals(100L, preset.minimumInferenceIntervalMs)
    }

    @Test
    fun turboProfileKeepsHighResolutionCameraButUsesSmallerModelInput() {
        val preset = HandTrackingPreset.SINGLE_HAND_TURBO
        assertEquals(1, preset.maxTrackedHands)
        assertEquals(640, preset.cameraWidthPx)
        assertEquals(480, preset.cameraHeightPx)
        assertEquals(0L, preset.minimumInferenceIntervalMs)
        assertEquals(320, preset.modelInputWidthPx)
        assertEquals(240, preset.modelInputHeightPx)
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
        assertEquals(HandTrackingPreset.SUPER_ECONOMY,
            HandTrackingPreset.recommend(DevicePerformanceTier.ECONOMY))
        assertEquals(HandTrackingPreset.BALANCED,
            HandTrackingPreset.recommend(DevicePerformanceTier.BALANCED))
        assertEquals(HandTrackingPreset.RESPONSIVE,
            HandTrackingPreset.recommend(DevicePerformanceTier.CAPABLE))
    }

    @Test
    fun delegatePreferenceSupportsGpuFallbackAndCpuOnlyModes() {
        assertEquals(Delegate.GPU, HandTrackingDelegatePreference.GPU_PREFERRED.delegate)
        assertEquals(Delegate.CPU, HandTrackingDelegatePreference.CPU_ONLY.delegate)
        assertEquals(
            HandTrackingDelegatePreference.GPU_PREFERRED,
            HandTrackingDelegatePreference.fromStoredName("UNKNOWN")
        )
    }

    @Test
    fun unknownStoredPresetFallsBackToBalanced() {
        assertEquals(HandTrackingPreset.BALANCED, HandTrackingPreset.fromStoredName("UNKNOWN"))
    }
}
