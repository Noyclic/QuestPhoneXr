package com.noyclic.questphonexr.runtime

import com.noyclic.questphonexr.performance.NativeVulkanDevice
import com.noyclic.questphonexr.performance.NativeVulkanExternalHandle
import com.noyclic.questphonexr.performance.NativeVulkanProbeReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonadoVulkanPrerequisitesTest {
    @Test
    fun recognizesImportableAndroidHardwareBufferPathWithoutDesktopFdExtensions() {
        val result = MonadoVulkanPrerequisites.assess(report(device(
            extensions = mapOf(
                "VK_ANDROID_external_memory_android_hardware_buffer" to true
            ),
            handles = listOf(handle("ANDROID_HARDWARE_BUFFER image RGBA8", importable = true))
        )))

        assertEquals(MonadoVulkanPrerequisiteState.AHB_PATH_CANDIDATE, result.state)
        assertTrue(result.missingExtensions.isEmpty())
        assertTrue(result.toDisplayText().contains("swapchain"))
    }

    @Test
    fun doesNotAcceptAhbExtensionWithoutImportableHandleSupport() {
        val result = MonadoVulkanPrerequisites.assess(report(device(
            extensions = mapOf(
                "VK_ANDROID_external_memory_android_hardware_buffer" to true
            )
        )))

        assertEquals(MonadoVulkanPrerequisiteState.NO_KNOWN_PATH, result.state)
        assertTrue(result.missingExtensions.contains("импортируемый ANDROID_HARDWARE_BUFFER handle"))
    }

    @Test
    fun recognizesOpaqueFdSharingPathWhenImportAndExportAreAvailable() {
        val result = MonadoVulkanPrerequisites.assess(report(device(
            extensions = mapOf(
                "VK_KHR_external_memory_fd" to true,
                "VK_KHR_external_semaphore_fd" to true
            ),
            handles = listOf(
                handle("OPAQUE_FD buffer", importable = true, exportable = true),
                handle("OPAQUE_FD semaphore", importable = true, exportable = true)
            )
        )))

        assertEquals(MonadoVulkanPrerequisiteState.FD_SHARING_PATH_CANDIDATE, result.state)
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

    private fun handle(
        name: String,
        importable: Boolean = false,
        exportable: Boolean = false
    ) = NativeVulkanExternalHandle(
        name = name,
        queried = true,
        importable = importable,
        exportable = exportable
    )

    private fun report(device: NativeVulkanDevice) =
        NativeVulkanProbeReport(true, "OK", "enumerated", "1.1", listOf(device))

    private fun device(
        extensions: Map<String, Boolean> = emptyMap(),
        extensionEnumerationSucceeded: Boolean = true,
        handles: List<NativeVulkanExternalHandle> = emptyList()
    ) = NativeVulkanDevice(
        name = "Test GPU",
        vendorId = 1L,
        deviceId = 2L,
        driverVersion = 3L,
        apiVersion = "1.1.0",
        api11CoreQueries = true,
        extensionEnumerationSucceeded = extensionEnumerationSucceeded,
        extensions = extensions,
        externalHandles = handles
    )
}
