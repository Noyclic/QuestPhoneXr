package com.noyclic.questphonexr.runtime

import com.noyclic.questphonexr.performance.NativeVulkanProbeReport

enum class MonadoVulkanPrerequisiteState {
    PROBE_UNAVAILABLE,
    NO_VULKAN_DEVICE,
    EXTENSION_ENUMERATION_UNAVAILABLE,
    REQUIRED_EXTENSIONS_MISSING,
    DRIVER_EXTENSION_CANDIDATE
}

data class MonadoVulkanPrerequisiteAssessment(
    val state: MonadoVulkanPrerequisiteState,
    val deviceName: String?,
    val missingExtensions: List<String>,
    val message: String
) {
    fun toDisplayText(): String = buildString {
        append(when (state) {
            MonadoVulkanPrerequisiteState.PROBE_UNAVAILABLE -> "Проверка недоступна"
            MonadoVulkanPrerequisiteState.NO_VULKAN_DEVICE -> "Vulkan GPU не найден"
            MonadoVulkanPrerequisiteState.EXTENSION_ENUMERATION_UNAVAILABLE -> "Расширения GPU не удалось проверить"
            MonadoVulkanPrerequisiteState.REQUIRED_EXTENSIONS_MISSING -> "Не хватает расширений драйвера"
            MonadoVulkanPrerequisiteState.DRIVER_EXTENSION_CANDIDATE -> "Драйвер-кандидат найден"
        })
        deviceName?.let { append(" · ").append(it) }
        append("\n").append(message)
        if (missingExtensions.isNotEmpty()) {
            append("\nОтсутствуют: ").append(missingExtensions.joinToString())
        }
        append("\nЭто не подтверждает создание swapchain, импорт изображений, запуск compositor или OpenXR-сессии.")
    }
}

/**
 * Monado's documented Vulkan compositor path needs external-memory-FD and
 * external-semaphore-FD support for basic application texture submission.
 * A positive result means only that the driver advertises these extensions.
 */
object MonadoVulkanPrerequisites {
    private val requiredExtensions = listOf(
        "VK_KHR_external_memory_fd",
        "VK_KHR_external_semaphore_fd"
    )

    fun assess(report: NativeVulkanProbeReport): MonadoVulkanPrerequisiteAssessment {
        if (!report.available || report.status != "OK") {
            return MonadoVulkanPrerequisiteAssessment(
                MonadoVulkanPrerequisiteState.PROBE_UNAVAILABLE,
                null,
                emptyList(),
                "Native Vulkan probe не завершился успешно (\${report.status})."
            )
        }
        if (report.devices.isEmpty()) {
            return MonadoVulkanPrerequisiteAssessment(
                MonadoVulkanPrerequisiteState.NO_VULKAN_DEVICE,
                null,
                requiredExtensions,
                "Драйвер не перечислил физический Vulkan GPU."
            )
        }

        val enumerated = report.devices.filter { it.extensionEnumerationSucceeded }
        if (enumerated.isEmpty()) {
            return MonadoVulkanPrerequisiteAssessment(
                MonadoVulkanPrerequisiteState.EXTENSION_ENUMERATION_UNAVAILABLE,
                report.devices.first().name,
                requiredExtensions,
                "Нельзя определить, предоставляет ли драйвер нужные расширения."
            )
        }

        val candidate = enumerated.firstOrNull { device ->
            requiredExtensions.all { device.extensions[it] == true }
        }
        if (candidate != null) {
            return MonadoVulkanPrerequisiteAssessment(
                MonadoVulkanPrerequisiteState.DRIVER_EXTENSION_CANDIDATE,
                candidate.name,
                emptyList(),
                "Драйвер объявляет оба расширения, необходимые Monado для базовой Vulkan-передачи текстур."
            )
        }

        val best = enumerated.maxByOrNull { device ->
            requiredExtensions.count { device.extensions[it] == true }
        }!!
        val missing = requiredExtensions.filter { best.extensions[it] != true }
        return MonadoVulkanPrerequisiteAssessment(
            MonadoVulkanPrerequisiteState.REQUIRED_EXTENSIONS_MISSING,
            best.name,
            missing,
            "По объявленным расширениям этот GPU пока не подходит для базового Vulkan-пути Monado."
        )
    }
}
