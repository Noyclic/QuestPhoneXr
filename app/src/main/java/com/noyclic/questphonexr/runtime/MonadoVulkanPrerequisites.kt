package com.noyclic.questphonexr.runtime

import com.noyclic.questphonexr.performance.NativeVulkanProbeReport

enum class MonadoVulkanPrerequisiteState {
    PROBE_UNAVAILABLE,
    NO_VULKAN_DEVICE,
    EXTENSION_ENUMERATION_UNAVAILABLE,
    AHB_PATH_CANDIDATE,
    FD_SHARING_PATH_CANDIDATE,
    NO_KNOWN_PATH
}

data class MonadoVulkanPrerequisiteAssessment(
    val state: MonadoVulkanPrerequisiteState,
    val deviceName: String?,
    val missingExtensions: List<String>,
    val message: String
) {
    fun toDisplayText(): String = buildString {
        append(when (state) {
            MonadoVulkanPrerequisiteState.PROBE_UNAVAILABLE -> "Проверка Vulkan недоступна"
            MonadoVulkanPrerequisiteState.NO_VULKAN_DEVICE -> "Vulkan GPU не найден"
            MonadoVulkanPrerequisiteState.EXTENSION_ENUMERATION_UNAVAILABLE -> "Расширения GPU не удалось проверить"
            MonadoVulkanPrerequisiteState.AHB_PATH_CANDIDATE -> "Найден кандидат Android Hardware Buffer"
            MonadoVulkanPrerequisiteState.FD_SHARING_PATH_CANDIDATE -> "Найден кандидат FD-sharing"
            MonadoVulkanPrerequisiteState.NO_KNOWN_PATH -> "Недостаточно признаков Vulkan sharing"
        })
        deviceName?.let { append(" · ").append(it) }
        append("\n").append(message)
        if (missingExtensions.isNotEmpty()) {
            append("\nНе обнаружены признаки: ").append(missingExtensions.joinToString())
        }
        append("\nЭто предварительная оценка путей обмена ресурсами, не вердикт о совместимости Monado.")
        append(" Реальный импорт изображений, swapchain и compositor не проверялись.")
    }
}

/**
 * Android can use an AHardwareBuffer path, so desktop FD extensions are not
 * treated as universally mandatory. This only identifies candidate paths.
 */
object MonadoVulkanPrerequisites {
    private const val AHB = "VK_ANDROID_external_memory_android_hardware_buffer"
    private const val MEMORY_FD = "VK_KHR_external_memory_fd"
    private const val SEMAPHORE_FD = "VK_KHR_external_semaphore_fd"

    fun assess(report: NativeVulkanProbeReport): MonadoVulkanPrerequisiteAssessment {
        if (!report.available || report.status != "OK") {
            val state = if (report.status == "NO_PHYSICAL_DEVICE") {
                MonadoVulkanPrerequisiteState.NO_VULKAN_DEVICE
            } else MonadoVulkanPrerequisiteState.PROBE_UNAVAILABLE
            return MonadoVulkanPrerequisiteAssessment(
                state, null, emptyList(),
                "Native Vulkan probe не завершился успешно (" + report.status + ")."
            )
        }
        if (report.devices.isEmpty()) {
            return MonadoVulkanPrerequisiteAssessment(
                MonadoVulkanPrerequisiteState.NO_VULKAN_DEVICE, null,
                listOf(AHB, MEMORY_FD, SEMAPHORE_FD),
                "Драйвер не перечислил физический Vulkan GPU."
            )
        }
        val devices = report.devices.filter { it.extensionEnumerationSucceeded }
        if (devices.isEmpty()) {
            return MonadoVulkanPrerequisiteAssessment(
                MonadoVulkanPrerequisiteState.EXTENSION_ENUMERATION_UNAVAILABLE,
                report.devices.first().name, emptyList(),
                "Нельзя определить, какие внешние ресурсы может передавать драйвер."
            )
        }

        val ahb = devices.firstOrNull { device ->
            val handle = device.externalHandles.firstOrNull { it.name == "ANDROID_HARDWARE_BUFFER buffer" }
            device.extensions[AHB] == true && handle?.queried == true && handle.importable
        }
        if (ahb != null) {
            return MonadoVulkanPrerequisiteAssessment(
                MonadoVulkanPrerequisiteState.AHB_PATH_CANDIDATE, ahb.name, emptyList(),
                "Драйвер объявляет AHB external-memory и сообщает импортируемый Android Hardware Buffer handle. Нужно отдельно проверить AHardwareBuffer image import и синхронизацию."
            )
        }

        val fd = devices.firstOrNull { device ->
            val memory = device.externalHandles.firstOrNull { it.name == "OPAQUE_FD buffer" }
            val semaphore = device.externalHandles.firstOrNull { it.name == "OPAQUE_FD semaphore" }
            device.extensions[MEMORY_FD] == true &&
                device.extensions[SEMAPHORE_FD] == true &&
                memory?.queried == true && memory.importable && memory.exportable &&
                semaphore?.queried == true && semaphore.importable && semaphore.exportable
        }
        if (fd != null) {
            return MonadoVulkanPrerequisiteAssessment(
                MonadoVulkanPrerequisiteState.FD_SHARING_PATH_CANDIDATE, fd.name, emptyList(),
                "Драйвер объявляет external memory/semaphore FD и поддерживает импорт/экспорт OPAQUE_FD handles."
            )
        }

        val best = devices.maxByOrNull { device ->
            listOf(AHB, MEMORY_FD, SEMAPHORE_FD).count { device.extensions[it] == true }
        }!!
        val missing = when {
            best.extensions[AHB] == true -> listOf("импортируемый ANDROID_HARDWARE_BUFFER handle")
            best.extensions[MEMORY_FD] == true && best.extensions[SEMAPHORE_FD] == true ->
                listOf("импорт/экспорт OPAQUE_FD memory и semaphore handles")
            else -> listOf(AHB, MEMORY_FD, SEMAPHORE_FD).filter { best.extensions[it] != true }
        }
        return MonadoVulkanPrerequisiteAssessment(
            MonadoVulkanPrerequisiteState.NO_KNOWN_PATH, best.name, missing,
            "В preflight не найден завершённый AHB или FD-sharing кандидат. Это не доказывает несовместимость: пока не проверены image-format properties, реальный AHardwareBuffer image import, queue-family sharing и работа compositor."
        )
    }
}
