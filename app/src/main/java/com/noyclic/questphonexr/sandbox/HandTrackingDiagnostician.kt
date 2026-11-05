package com.noyclic.questphonexr.sandbox

import com.noyclic.questphonexr.tracking.HandTrackingMetricsSnapshot
import java.util.Locale

internal enum class DiagnosisLevel(val marker: String) {
    OK("✅"), INFO("ℹ️"), WARN("⚠️"), FAIL("❌")
}

internal data class DiagnosisFinding(
    val level: DiagnosisLevel,
    val title: String,
    val hint: String = ""
)

internal enum class HandTrackingOwner(val label: String) {
    SERVICE("фоновая служба"), SANDBOX("Sandbox"), NONE("никто")
}

internal data class HandDiagnosisInput(
    val cameraPermissionGranted: Boolean,
    val modelAssetBytes: Long,
    val hasAnyCamera: Boolean,
    val hasFrontCamera: Boolean,
    val owner: HandTrackingOwner,
    val windowMs: Long,
    val before: HandTrackingMetricsSnapshot?,
    val after: HandTrackingMetricsSnapshot?,
    val maxHandsSeen: Int,
    val lastPipelineLatencyMs: Long,
    val inferenceIntervalMs: Long,
    val lastError: String = ""
)

internal data class HandDiagnosisReport(
    val findings: List<DiagnosisFinding>,
    val verdict: String
) {
    fun render(): String = buildString {
        append("ИТОГ: ").append(verdict).append("\n\n")
        findings.forEach { finding ->
            append(finding.level.marker).append(' ').append(finding.title).append('\n')
            if (finding.hint.isNotBlank()) append("    → ").append(finding.hint).append('\n')
        }
    }
}

internal object HandTrackingDiagnostician {
    private const val MIN_MODEL_BYTES = 1_000_000L
    private const val SLOW_RESULT_FPS = 8f
    private const val HIGH_LATENCY_MS = 150L
    private const val ECONOMY_INTERVAL_MS = 100L
    private const val BLACK_FRAME_LUMA = 8f
    private const val FLAT_FRAME_STDDEV = 4f
    private const val DARK_FRAME_LUMA = 40f

    fun diagnose(input: HandDiagnosisInput): HandDiagnosisReport {
        val findings = mutableListOf<DiagnosisFinding>()

        findings += if (input.cameraPermissionGranted) {
            DiagnosisFinding(DiagnosisLevel.OK, "Разрешение на камеру выдано")
        } else {
            DiagnosisFinding(
                DiagnosisLevel.FAIL,
                "Нет разрешения на камеру",
                "Открой настройки Android → Приложения → QuestPhoneXR → Разрешения → Камера."
            )
        }

        findings += when {
            input.modelAssetBytes >= MIN_MODEL_BYTES -> DiagnosisFinding(
                DiagnosisLevel.OK,
                "Модель hand_landmarker.task загружена (" + input.modelAssetBytes / 1024 + " КБ)"
            )
            input.modelAssetBytes < 0L -> DiagnosisFinding(
                DiagnosisLevel.FAIL,
                "Модель hand_landmarker.task отсутствует в APK",
                "Пересобери приложение с доступом к интернету: задача downloadHandLandmarkerModel."
            )
            else -> DiagnosisFinding(
                DiagnosisLevel.FAIL,
                "Модель hand_landmarker.task слишком мала (" + input.modelAssetBytes + " Б)",
                "Файл модели мог загрузиться не полностью; пересобери APK."
            )
        }

        findings += when {
            !input.hasAnyCamera -> DiagnosisFinding(
                DiagnosisLevel.FAIL,
                "Android не сообщает о наличии камеры"
            )
            input.hasFrontCamera -> DiagnosisFinding(
                DiagnosisLevel.INFO,
                "Фронтальная камера физически есть, но по умолчанию выключена; задняя остаётся основной"
            )
            else -> DiagnosisFinding(
                DiagnosisLevel.INFO,
                "Фронтальная камера не обнаружена; используется задняя"
            )
        }

        findings += DiagnosisFinding(DiagnosisLevel.INFO, "Камерой владеет: " + input.owner.label)
        if (input.lastError.isNotBlank()) {
            findings += DiagnosisFinding(DiagnosisLevel.FAIL, "Ошибка трекера: " + input.lastError)
        }

        val before = input.before
        val after = input.after
        if (input.owner == HandTrackingOwner.NONE || before == null || after == null) {
            if (input.cameraPermissionGranted && input.lastError.isBlank()) {
                findings += DiagnosisFinding(
                    DiagnosisLevel.FAIL,
                    "Трекер рук не запущен",
                    "Запусти отслеживание в Sandbox и повтори диагностику."
                )
            }
            return finish(findings)
        }

        val seconds = (input.windowMs / 1000f).coerceAtLeast(0.1f)
        val received = (after.receivedFrames - before.receivedFrames).coerceAtLeast(0L)
        val submitted = (after.submittedFrames - before.submittedFrames).coerceAtLeast(0L)
        val completed = (after.completedFrames - before.completedFrames).coerceAtLeast(0L)
        val droppedBusy = (after.droppedBusyFrames - before.droppedBusyFrames).coerceAtLeast(0L)

        if (received == 0L) {
            findings += DiagnosisFinding(
                DiagnosisLevel.FAIL,
                "Камера не передала ни одного кадра за " + String.format(Locale.US, "%.1f", seconds) + " с",
                "Проверь разрешение камеры, системный переключатель приватности и не занята ли камера другим приложением."
            )
            return finish(findings)
        }

        findings += DiagnosisFinding(
            DiagnosisLevel.OK,
            "Камера передаёт " + String.format(Locale.US, "%.1f", received / seconds) +
                " кадр/с (" + after.lastFrameWidthPx + "×" + after.lastFrameHeightPx +
                ", поворот " + after.lastFrameRotationDegrees + "°)"
        )

        val luma = after.lastFrameLumaMean
        val contrast = after.lastFrameLumaStdDev
        findings += when {
            luma < BLACK_FRAME_LUMA -> DiagnosisFinding(
                DiagnosisLevel.FAIL,
                "Кадр почти чёрный (яркость " + String.format(Locale.US, "%.1f", luma) + ")",
                "Проверь, не закрыт ли объектив пальцем, держателем или очками."
            )
            contrast < FLAT_FRAME_STDDEV -> DiagnosisFinding(
                DiagnosisLevel.WARN,
                "Кадр почти однотонный (контраст " + String.format(Locale.US, "%.1f", contrast) + ")",
                "Камера могла смотреть в стену или внутрь VR-держателя."
            )
            luma < DARK_FRAME_LUMA -> DiagnosisFinding(
                DiagnosisLevel.WARN,
                "Кадр слишком тёмный (яркость " + String.format(Locale.US, "%.1f", luma) + ")",
                "Добавь освещение: при слабом свете MediaPipe чаще теряет руки."
            )
            else -> DiagnosisFinding(
                DiagnosisLevel.OK,
                "Яркость и контраст изображения выглядят нормально"
            )
        }

        if (submitted > 0L && completed == 0L) {
            findings += DiagnosisFinding(
                DiagnosisLevel.FAIL,
                "MediaPipe получил кадры, но не вернул ни одного результата",
                "Проверь профиль MediaPipe; при повторении перезапусти отслеживание."
            )
        } else if (submitted == 0L) {
            findings += DiagnosisFinding(
                DiagnosisLevel.WARN,
                "Ни один кадр не отправлен в MediaPipe",
                "Проверь интервал обработки и не занят ли трекер."
            )
        } else {
            val resultFps = completed / seconds
            findings += if (resultFps < SLOW_RESULT_FPS) {
                DiagnosisFinding(
                    DiagnosisLevel.WARN,
                    "MediaPipe обрабатывает медленно: " + String.format(Locale.US, "%.1f", resultFps) + " кадр/с",
                    "Попробуй быстрый профиль и небольшое разрешение камеры."
                )
            } else {
                DiagnosisFinding(
                    DiagnosisLevel.OK,
                    "MediaPipe возвращает " + String.format(Locale.US, "%.1f", resultFps) + " результата/с"
                )
            }
            if (droppedBusy > submitted) {
                findings += DiagnosisFinding(
                    DiagnosisLevel.INFO,
                    "Много кадров пропущено, пока модель была занята (" + droppedBusy + ")"
                )
            }
        }

        if (input.inferenceIntervalMs >= ECONOMY_INTERVAL_MS) {
            findings += DiagnosisFinding(
                DiagnosisLevel.WARN,
                "Интервал обработки увеличен до " + input.inferenceIntervalMs + " мс",
                "Автоэкономия снижает частоту трекинга, чтобы разгрузить телефон."
            )
        }
        if (input.lastPipelineLatencyMs > HIGH_LATENCY_MS) {
            findings += DiagnosisFinding(
                DiagnosisLevel.WARN,
                "Высокая задержка от камеры до результата: " + input.lastPipelineLatencyMs + " мс"
            )
        }

        if (completed > 0L) {
            findings += if (input.maxHandsSeen > 0) {
                DiagnosisFinding(DiagnosisLevel.OK, "Найдено рук за проверку: " + input.maxHandsSeen)
            } else {
                DiagnosisFinding(
                    DiagnosisLevel.WARN,
                    "MediaPipe работает, но руки не найдены",
                    "Покажи ладонь целиком на расстоянии 30–50 см перед задней камерой при хорошем свете. Фронталка используется только после ручного включения тумблера."
                )
            }
        }

        return finish(findings)
    }

    private fun finish(findings: List<DiagnosisFinding>): HandDiagnosisReport {
        val verdict = findings.firstOrNull { it.level == DiagnosisLevel.FAIL }?.title
            ?: findings.firstOrNull { it.level == DiagnosisLevel.WARN }?.title
            ?: "всё работает"
        return HandDiagnosisReport(findings, verdict)
    }
}
