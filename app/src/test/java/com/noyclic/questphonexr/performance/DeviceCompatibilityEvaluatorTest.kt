package com.noyclic.questphonexr.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCompatibilityEvaluatorTest {
    private fun input(
        hasGyroscope: Boolean = true,
        hasRotationVector: Boolean = true,
        hasAccelerometer: Boolean = true,
        totalRamMb: Int = 8_192,
        processorCount: Int = 8,
        is64BitProcess: Boolean = true,
        openGlEsMajor: Int = 3,
        openGlEsMinor: Int = 2,
        vulkanApiMajor: Int? = 1,
        vulkanApiMinor: Int? = 1
    ) = DeviceCompatibilityInput(
        deviceName = "Test phone",
        androidVersion = "15",
        sdkVersion = 35,
        supportedAbis = listOf("arm64-v8a"),
        is64BitProcess = is64BitProcess,
        processorCount = processorCount,
        totalRamMb = totalRamMb,
        availableRamMb = totalRamMb / 2,
        appMemoryClassMb = 256,
        isLowRamDevice = false,
        openGlEsMajor = openGlEsMajor,
        openGlEsMinor = openGlEsMinor,
        vulkanApiMajor = vulkanApiMajor,
        vulkanApiMinor = vulkanApiMinor,
        vulkanApiPatch = 0,
        vulkanHardwareLevel = 1,
        hasGyroscope = hasGyroscope,
        hasRotationVector = hasRotationVector,
        hasAccelerometer = hasAccelerometer,
        rotationVectorSensorName = if (hasRotationVector) "Test Rotation Vector" else null
    )

    @Test
    fun reportsSuitableWhenCoreSensorsAndResourcesArePresent() {
        val report = DeviceCompatibilityEvaluator.evaluate(input())
        assertEquals(DeviceCompatibilityLevel.SUITABLE, report.level)
        assertTrue(report.checks.all { it.status == CapabilityCheckStatus.SUPPORTED })
    }

    @Test
    fun missingGyroscopeIsLimitedWhenRotationVectorStillExists() {
        val report = DeviceCompatibilityEvaluator.evaluate(input(hasGyroscope = false))
        assertEquals(DeviceCompatibilityLevel.LIMITED, report.level)
        assertTrue(report.checks.any {
            it.title == "Гироскоп" && it.status == CapabilityCheckStatus.LIMITED
        })
        assertTrue(report.checks.any {
            it.title == "Отслеживание поворота головы" &&
                it.status == CapabilityCheckStatus.LIMITED
        })
    }

    @Test
    fun missingRotationVectorMarksPhoneVrTrackingUnsupportedButKeepsOtherCapabilities() {
        val report = DeviceCompatibilityEvaluator.evaluate(input(hasRotationVector = false))
        assertEquals(DeviceCompatibilityLevel.XR_TRACKING_UNSUPPORTED, report.level)
        assertTrue(report.summary.contains("остаются доступны"))
        assertTrue(report.checks.any {
            it.title == "Отслеживание поворота головы" &&
                it.status == CapabilityCheckStatus.UNSUPPORTED
        })
    }

    @Test
    fun lowMemoryIsNotRecommendedRatherThanBlocked() {
        val report = DeviceCompatibilityEvaluator.evaluate(input(totalRamMb = 3_000))
        assertEquals(DeviceCompatibilityLevel.NOT_RECOMMENDED, report.level)
        assertTrue(report.summary.contains("не блокируется"))
    }

    @Test
    fun unknownVulkanIsExplicitlyReportedAsUnconfirmed() {
        val report = DeviceCompatibilityEvaluator.evaluate(
            input(vulkanApiMajor = null, vulkanApiMinor = null)
        )
        assertEquals(DeviceCompatibilityLevel.LIMITED, report.level)
        assertTrue(report.checks.any {
            it.title == "Vulkan" && it.status == CapabilityCheckStatus.UNKNOWN
        })
        assertEquals(null, report.vulkanApiVersion)
    }

    @Test
    fun olderGraphicsAnd32BitProcessAreReportedAsLimitations() {
        val report = DeviceCompatibilityEvaluator.evaluate(
            input(openGlEsMajor = 2, openGlEsMinor = 0, is64BitProcess = false)
        )
        assertEquals(DeviceCompatibilityLevel.NOT_RECOMMENDED, report.level)
        assertTrue(report.checks.any {
            it.title == "OpenGL ES" && it.status == CapabilityCheckStatus.LIMITED
        })
    }
}
