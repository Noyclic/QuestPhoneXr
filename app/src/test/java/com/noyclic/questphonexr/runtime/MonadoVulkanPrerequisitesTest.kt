package com.noyclic.questphonexr.runtime

import com.noyclic.questphonexr.performance.NativeVulkanDevice
import com.noyclic.questphonexr.performance.NativeVulkanProbeReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonadoVulkanPrerequisitesTest {
    @Test
    fun requiresAndroidHardwareBufferAndFdSynchronizationExtensions() {
        val result = MonadoVulkanPrerequisites.assess(report(device(
            extensions = mapOf(
                "VK_ANDROID_external_memory_android_hardware_buffer" to true
            )
        )))
        assertEquals(MonadoVulkanPrerequisiteState.REQUIRED_EXTENSIONS_MISSING, result.state)
        assertEquals(
            listOf("VK_KHR_external_semaphore_fd", "VK_KHR_external_fence_fd"),
            result.missingExtensions
        )
    }

    @Test
    fun recognizesDriverThatAdvertisesAndroidPrerequisiteExtensions() {
        val result = MonadoVulkanPrerequisites.assess(report(device(
            extensions = mapOf(
                "VK_ANDROID_external_memory_android_hardware_buffer" to true,
                "VK_KHR_external_semaphore_fd" to true,
                "VK_KHR_external_fence_fd" to true
            )
        )))
        assertEquals(MonadoVulkanPrerequisiteState.DRIVER_EXTENSION_CANDIDATE, result.state)
        assertTrue(result.missingExtensions.isEmpty())
        assertTrue(result.toDisplayText().contains("не подтверждает создание swapchain"))
    }

    @Test
    fun doesNotTreatFailedProbeAsUnsupportedGpu() {
        val result = MonadoVulkanPrerequisites.assess(
            NativeVulkanProbeReport(false, "NATIVE_PROBE_FAILED", "failed", null, emptyList())
        )
        assertEquals(MonadoVulkanPrerequisiteState.PROBE_UNAVAILABLE, result.state)
    }

    @Test
    fun reportsWhenExtensionEnumerationFailed() {
        val result = MonadoVulkanPrerequisites.assess(report(device(extensionEnumerationSucceeded = false)))
        assertEquals(MonadoVulkanPrerequisiteState.EXTENSION_ENUMERATION_UNAVAILABLE, result.state)
    }

    @Test
    fun reportsWhenNoVulkanPhysicalDeviceWasEnumerated() {
        val result = MonadoVulkanPrerequisites.assess(
            NativeVulkanProbeReport(false, "NO_PHYSICAL_DEVICE", "empty", "1.1", emptyList())
        )
        assertEquals(MonadoVulkanPrerequisiteState.NO_VULKAN_DEVICE, result.state)
    }

    private fun report(device: NativeVulkanDevice) =
        NativeVulkanProbeReport(true, "OK", "enumerated", "1.1", listOf(device))

    private fun device(
        extensions: Map<String, Boolean> = emptyMap(),
        extensionEnumerationSucceeded: Boolean = true
    ) = NativeVulkanDevice(
        name = "Test GPU",
        vendorId = 1L,
        deviceId = 2L,
        driverVersion = 3L,
        apiVersion = "1.1.0",
        api11CoreQueries = true,
        extensionEnumerationSucceeded = extensionEnumerationSucceeded,
        extensions = extensions,
        externalHandles = emptyList()
    )
}
