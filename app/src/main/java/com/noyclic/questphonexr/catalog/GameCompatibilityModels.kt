package com.noyclic.questphonexr.catalog

import android.os.Build
import java.util.Locale

enum class GamePortingPath(val label: String) {
    ANDROID_BUILD_LISTED("Есть отдельная Android-сборка/порт, нужна проверка"),
    QUEST_BUILD_NEEDS_ADAPTATION("Quest-сборка: требуется адаптация под телефон"),
    SOURCE_PORT_REQUIRED("Нужен отдельный порт исходного проекта"),
    UNKNOWN("Путь запуска пока не установлен")
}

enum class ExpectedLoad(val label: String) {
    LOW("Низкая — предварительная оценка"),
    MEDIUM("Средняя — предварительная оценка"),
    HIGH("Высокая — предварительная оценка"),
    UNKNOWN("Пока неизвестна")
}

enum class TestOutcome(val label: String) {
    LAUNCHED("Запустилась и удалось поиграть"),
    PLAYABLE_WITH_ISSUES("Запустилась, но есть проблемы"),
    FAILED_TO_LAUNCH("Не удалось запустить"),
    CRASHED("Вылетает или зависает")
}

enum class CompatibilityState(val label: String) {
    NOT_TESTED("Не проверено на этом телефоне"),
    LAUNCHES("Запуск и игра подтверждены"),
    LIMITED("Запустилась с проблемами"),
    FAILED("Не запускается или вылетает")
}

data class DeviceFingerprint(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val apiLevel: Int
) {
    fun isSamePhysicalDevice(other: DeviceFingerprint): Boolean {
        return normalize(manufacturer) == normalize(other.manufacturer) &&
            normalize(model) == normalize(other.model)
    }

    val displayName: String
        get() {
            val normalizedModel = normalize(model)
            return if (normalizedModel.contains("23124ra7eo")) {
                "Redmi Note 13 4G"
            } else {
                listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").trim()
            }
        }

    companion object {
        fun current(): DeviceFingerprint = DeviceFingerprint(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            apiLevel = Build.VERSION.SDK_INT
        )

        private fun normalize(value: String): String =
            value.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")
    }
}

data class GameTestReport(
    val device: DeviceFingerprint,
    val outcome: TestOutcome,
    val testedAtEpochMs: Long,
    val averageFps: Int?,
    val minimumFps: Int?,
    val appVersion: String,
    val notes: String,
    val isManualEntry: Boolean = true
)

data class GameCatalogEntry(
    val id: String,
    val title: String,
    val summary: String,
    val portingPath: GamePortingPath,
    val expectedLoad: ExpectedLoad,
    val projectUrl: String?,
    val secondaryUrl: String? = null,
    val secondaryUrlLabel: String? = null,
    val compatibilityNotes: String
)

data class CompatibilityAssessment(
    val state: CompatibilityState,
    val latestReport: GameTestReport?,
    val reportsForThisDevice: Int,
    val reportsForOtherDevices: Int,
    val androidVersionMatches: Boolean?
)
