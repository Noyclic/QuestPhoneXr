package com.noyclic.questphonexr.performance

import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePerformancePolicyTest {
    @Test
    fun lowRamDeviceUsesEconomyPreset() {
        val preset = DevicePerformancePolicy.recommend(
            capabilities(isLowRamDevice = true)
        )

        assertEquals(DevicePerformanceTier.ECONOMY, preset.tier)
        assertEquals(320, preset.cameraWidthPx)
        assertEquals(20, preset.maxRenderFps)
        assertEquals(2, preset.maxTrackedHands)
    }

    @Test
    fun limitedAppMemoryUsesEconomyPreset() {
        val preset = DevicePerformancePolicy.recommend(
            capabilities(appMemoryClassMb = 128)
        )

        assertEquals(DevicePerformanceTier.ECONOMY, preset.tier)
    }

    @Test
    fun ordinaryPhoneUsesBalancedPreset() {
        val preset = DevicePerformancePolicy.recommend(
            capabilities(totalRamMb = 6_144, appMemoryClassMb = 256)
        )

        assertEquals(DevicePerformanceTier.BALANCED, preset.tier)
        assertEquals(480, preset.cameraWidthPx)
        assertEquals(360, preset.cameraHeightPx)
        assertEquals(2, preset.maxTrackedHands)
    }

    @Test
    fun resourceRichPhoneUsesCapablePreset() {
        val preset = DevicePerformancePolicy.recommend(
            capabilities(
                totalRamMb = 8_192,
                appMemoryClassMb = 256,
                processorCount = 8,
                openGlEsMajor = 3,
                openGlEsMinor = 2
            )
        )

        assertEquals(DevicePerformanceTier.CAPABLE, preset.tier)
        assertEquals(640, preset.cameraWidthPx)
        assertEquals(45, preset.maxRenderFps)
    }

    @Test
    fun automaticPresetNeverRaisesUserFpsChoice() {
        val economy = DevicePerformancePolicy.recommend(
            capabilities(isLowRamDevice = true)
        )
        val capable = DevicePerformancePolicy.recommend(
            capabilities(
                totalRamMb = 8_192,
                appMemoryClassMb = 256,
                processorCount = 8,
                openGlEsMajor = 3,
                openGlEsMinor = 2
            )
        )

        assertEquals(20, DevicePerformancePolicy.effectiveRenderFps(60, economy))
        assertEquals(20, DevicePerformancePolicy.effectiveRenderFps(20, capable))
        assertEquals(45, DevicePerformancePolicy.effectiveRenderFps(60, capable))
    }

    @Test
    fun missingOptionalGlInformationDoesNotForceEconomyTier() {
        val preset = DevicePerformancePolicy.recommend(
            capabilities(openGlEsMajor = 0, openGlEsMinor = 0)
        )

        assertEquals(DevicePerformanceTier.BALANCED, preset.tier)
    }

    private fun capabilities(
        totalRamMb: Int = 6_144,
        appMemoryClassMb: Int = 256,
        processorCount: Int = 8,
        openGlEsMajor: Int = 3,
        openGlEsMinor: Int = 0,
        isLowRamDevice: Boolean = false
    ) = DeviceCapabilities(
        totalRamMb = totalRamMb,
        appMemoryClassMb = appMemoryClassMb,
        processorCount = processorCount,
        openGlEsMajor = openGlEsMajor,
        openGlEsMinor = openGlEsMinor,
        isLowRamDevice = isLowRamDevice
    )
}
