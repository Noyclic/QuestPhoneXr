package com.noyclic.questphonexr.performance

/** Conservative hardware assessment; this is not a benchmark or an XR-runtime guarantee. */
enum class DeviceCompatibilityLevel(val label: String) {
    SUITABLE("Подходит по базовым признакам"),
    LIMITED("Подходит с ограничениями"),
    NOT_RECOMMENDED("Не рекомендуется для тяжёлого XR"),
    XR_TRACKING_UNSUPPORTED("Текущий phone-VR трекинг недоступен")
}

enum class CapabilityCheckStatus(val label: String) {
    SUPPORTED("Есть"),
    LIMITED("Ограничение"),
    NOT_RECOMMENDED("Слабое место"),
    UNSUPPORTED("Нет"),
    UNKNOWN("Не удалось подтвердить")
}

data class DeviceCapabilityCheck(
    val title: String,
    val status: CapabilityCheckStatus,
    val detail: String
)

data class DeviceCompatibilityInput(
    val deviceName: String,
    val androidVersion: String,
    val sdkVersion: Int,
    val supportedAbis: List<String>,
    val is64BitProcess: Boolean,
    val processorCount: Int,
    val totalRamMb: Int,
    val availableRamMb: Int,
    val appMemoryClassMb: Int,
    val isLowRamDevice: Boolean,
    val openGlEsMajor: Int,
    val openGlEsMinor: Int,
    val vulkanApiMajor: Int?,
    val vulkanApiMinor: Int?,
    val vulkanApiPatch: Int?,
    val vulkanHardwareLevel: Int?,
    val hasGyroscope: Boolean,
    val hasRotationVector: Boolean,
    val hasAccelerometer: Boolean,
    val rotationVectorSensorName: String?
)

data class DeviceCompatibilityReport(
    val level: DeviceCompatibilityLevel,
    val summary: String,
    val deviceName: String,
    val androidVersion: String,
    val sdkVersion: Int,
    val supportedAbis: List<String>,
    val is64BitProcess: Boolean,
    val processorCount: Int,
    val totalRamMb: Int,
    val availableRamMb: Int,
    val appMemoryClassMb: Int,
    val isLowRamDevice: Boolean,
    val openGlEsVersion: String,
    val vulkanApiVersion: String?,
    val vulkanHardwareLevel: Int?,
    val rotationVectorSensorName: String?,
    val checks: List<DeviceCapabilityCheck>
)

/** Pure decision logic so hardware labels can be tested on CI without an Android device. */
object DeviceCompatibilityEvaluator {
    fun evaluate(input: DeviceCompatibilityInput): DeviceCompatibilityReport {
        val checks = mutableListOf<DeviceCapabilityCheck>()

        if (input.hasRotationVector) {
            val trackingStatus = if (input.hasGyroscope) {
                CapabilityCheckStatus.SUPPORTED
            } else {
                CapabilityCheckStatus.LIMITED
            }
            val trackingDetail = if (input.hasGyroscope) {
                "Системный датчик ориентации найден; гироскоп присутствует."
            } else {
                "Датчик ориентации найден, но отдельный гироскоп не обнаружен. Качество и задержка зависят от реализации датчика."
            }
            checks += DeviceCapabilityCheck("Отслеживание поворота головы", trackingStatus, trackingDetail)
        } else {
            checks += DeviceCapabilityCheck(
                "Отслеживание поворота головы",
                CapabilityCheckStatus.UNSUPPORTED,
                "GAME_ROTATION_VECTOR и ROTATION_VECTOR не найдены. Текущий VR-просмотр не сможет получать ориентацию головы."
            )
        }

        checks += DeviceCapabilityCheck(
            "Гироскоп",
            if (input.hasGyroscope) CapabilityCheckStatus.SUPPORTED else CapabilityCheckStatus.LIMITED,
            if (input.hasGyroscope) "Аппаратный или системно зарегистрированный гироскоп обнаружен."
            else "Гироскоп не обнаружен. Это не отключает приложение, но может ухудшить телефонный VR-трекинг."
        )

        checks += DeviceCapabilityCheck(
            "Акселерометр",
            if (input.hasAccelerometer) CapabilityCheckStatus.SUPPORTED else CapabilityCheckStatus.LIMITED,
            if (input.hasAccelerometer) "Датчик ускорения обнаружен."
            else "Акселерометр не обнаружен; часть схем ориентации и стабилизации может быть недоступна."
        )

        val glVersion = if (input.openGlEsMajor > 0) {
            input.openGlEsMajor.toString() + "." + input.openGlEsMinor
        } else {
            "не определена"
        }
        val glStatus = when {
            input.openGlEsMajor >= 3 -> CapabilityCheckStatus.SUPPORTED
            input.openGlEsMajor > 0 -> CapabilityCheckStatus.LIMITED
            else -> CapabilityCheckStatus.UNKNOWN
        }
        checks += DeviceCapabilityCheck(
            "OpenGL ES",
            glStatus,
            if (input.openGlEsMajor >= 3) {
                "Android сообщает OpenGL ES " + glVersion + ". Это версия API, а не оценка скорости GPU."
            } else {
                "Обнаружена версия OpenGL ES " + glVersion + ". Возможности графического пути XR могут быть ограничены."
            }
        )

        val vulkanVersion = if (input.vulkanApiMajor != null) {
            input.vulkanApiMajor.toString() + "." + (input.vulkanApiMinor ?: 0) +
                "." + (input.vulkanApiPatch ?: 0)
        } else {
            null
        }
        val vulkanStatus = when {
            input.vulkanApiMajor == null -> CapabilityCheckStatus.UNKNOWN
            input.vulkanApiMajor > 1 -> CapabilityCheckStatus.SUPPORTED
            input.vulkanApiMajor == 1 && (input.vulkanApiMinor ?: 0) >= 1 ->
                CapabilityCheckStatus.SUPPORTED
            else -> CapabilityCheckStatus.LIMITED
        }
        val vulkanDetail = if (vulkanVersion == null) {
            "Android не сообщил Vulkan hardware feature. Это не доказывает отсутствие всех графических путей."
        } else {
            val hasAndroidSyncFdGuarantee = input.vulkanApiMajor!! > 1 ||
                (input.vulkanApiMajor == 1 && (input.vulkanApiMinor ?: 0) >= 1)
            "Android сообщает Vulkan " + vulkanVersion + "." +
                (if (hasAndroidSyncFdGuarantee) {
                    " Для Android Vulkan 1.1+ системная декларация также гарантирует AHB external-memory и SYNC_FD semaphore/fence handles."
                } else {
                    " Гарантии Android Vulkan 1.1+ для AHB / SYNC_FD здесь отсутствуют."
                }) +
                " Но это не подтверждает VK_KHR_external_memory_fd / VK_KHR_external_semaphore_fd и другие расширения, необходимые конкретному compositor; их нужно перечислить через native Vulkan probe."
        }
        checks += DeviceCapabilityCheck("Vulkan", vulkanStatus, vulkanDetail)

        val ramStatus = when {
            input.totalRamMb <= 0 -> CapabilityCheckStatus.UNKNOWN
            input.totalRamMb < 3_500 || input.isLowRamDevice -> CapabilityCheckStatus.NOT_RECOMMENDED
            input.totalRamMb < 6_000 -> CapabilityCheckStatus.LIMITED
            else -> CapabilityCheckStatus.SUPPORTED
        }
        val ramDetail = if (input.totalRamMb > 0) {
            "Всего " + input.totalRamMb + " МБ, свободно сейчас " + input.availableRamMb +
                " МБ; класс памяти приложения " + input.appMemoryClassMb + " МБ." +
                (if (input.isLowRamDevice) " Android помечает устройство как low-RAM." else "")
        } else {
            "Android не сообщил объём оперативной памяти."
        }
        checks += DeviceCapabilityCheck("Оперативная память", ramStatus, ramDetail)

        val cpuStatus = when {
            input.processorCount <= 0 -> CapabilityCheckStatus.UNKNOWN
            input.processorCount <= 4 || !input.is64BitProcess ->
                CapabilityCheckStatus.NOT_RECOMMENDED
            input.processorCount < 8 -> CapabilityCheckStatus.LIMITED
            else -> CapabilityCheckStatus.SUPPORTED
        }
        val processBitness = if (input.is64BitProcess) "64-битный" else "32-битный"
        val abiText = input.supportedAbis.joinToString().ifBlank { "неизвестно" }
        val cpuDetail = input.processorCount.toString() + " доступных логических ядер; процесс " +
            processBitness + ". ABI устройства: " + abiText + "."
        checks += DeviceCapabilityCheck("CPU и ABI", cpuStatus, cpuDetail)

        val level = when {
            !input.hasRotationVector -> DeviceCompatibilityLevel.XR_TRACKING_UNSUPPORTED
            ramStatus == CapabilityCheckStatus.NOT_RECOMMENDED ||
                cpuStatus == CapabilityCheckStatus.NOT_RECOMMENDED ->
                DeviceCompatibilityLevel.NOT_RECOMMENDED
            checks.any {
                it.status == CapabilityCheckStatus.LIMITED ||
                    it.status == CapabilityCheckStatus.UNKNOWN
            } -> DeviceCompatibilityLevel.LIMITED
            else -> DeviceCompatibilityLevel.SUITABLE
        }

        val summary = when (level) {
            DeviceCompatibilityLevel.SUITABLE ->
                "Базовые датчики и ресурсы выглядят подходящими. Это ещё не подтверждает совместимость конкретного XR Runtime или игры."
            DeviceCompatibilityLevel.LIMITED ->
                "Основные режимы приложения могут работать, но один или несколько XR-путей имеют ограничения или требуют дополнительной проверки."
            DeviceCompatibilityLevel.NOT_RECOMMENDED ->
                "Приложение не блокируется. Для тяжёлой XR-графики и hand tracking вероятны низкая частота кадров, нагрев или нехватка памяти."
            DeviceCompatibilityLevel.XR_TRACKING_UNSUPPORTED ->
                "Текущий phone-VR путь не нашёл системный датчик ориентации. Библиотека, импорт APK и доступные 2D-функции остаются доступны."
        }

        return DeviceCompatibilityReport(
            level = level,
            summary = summary,
            deviceName = input.deviceName,
            androidVersion = input.androidVersion,
            sdkVersion = input.sdkVersion,
            supportedAbis = input.supportedAbis,
            is64BitProcess = input.is64BitProcess,
            processorCount = input.processorCount,
            totalRamMb = input.totalRamMb,
            availableRamMb = input.availableRamMb,
            appMemoryClassMb = input.appMemoryClassMb,
            isLowRamDevice = input.isLowRamDevice,
            openGlEsVersion = glVersion,
            vulkanApiVersion = vulkanVersion,
            vulkanHardwareLevel = input.vulkanHardwareLevel,
            rotationVectorSensorName = input.rotationVectorSensorName,
            checks = checks
        )
    }
}
