package com.noyclic.questphonexr.tracking

import android.content.Context
import com.noyclic.questphonexr.performance.DevicePerformanceTier
import com.google.mediapipe.tasks.core.Delegate

/**
 * User-selectable MediaPipe workload. These are workload presets, not FPS guarantees.
 * Custom camera dimensions in ViewerProfile take precedence over preset dimensions.
 */
enum class HandTrackingPreset(
    val label: String,
    val description: String,
    val maxTrackedHands: Int,
    val cameraWidthPx: Int,
    val cameraHeightPx: Int,
    val minimumInferenceIntervalMs: Long,
    val modelInputWidthPx: Int = 480,
    val modelInputHeightPx: Int = 360
) {
    SUPER_ECONOMY(
        label = "Суперэкономия",
        description = "1 рука · 256×192 · около 6 кадров/с",
        maxTrackedHands = 1,
        cameraWidthPx = 256,
        cameraHeightPx = 192,
        minimumInferenceIntervalMs = 150L
    ),
    POWER_SAVER(
        label = "Экономия",
        description = "2 руки · низкая частота обработки · меньше нагрев",
        maxTrackedHands = 2,
        cameraWidthPx = 640,
        cameraHeightPx = 480,
        minimumInferenceIntervalMs = 100L
    ),
    SINGLE_HAND_FAST(
        label = "Быстро · 1 рука",
        description = "1 рука · 640×480 камера · минимальный интервал обработки",
        maxTrackedHands = 1,
        cameraWidthPx = 640,
        cameraHeightPx = 480,
        minimumInferenceIntervalMs = 0L
    ),
    SINGLE_HAND_TURBO(
        label = "Турбо · 1 рука",
        description = "640×480 камера → модель 320×240 · без искусственного интервала",
        maxTrackedHands = 1,
        cameraWidthPx = 640,
        cameraHeightPx = 480,
        minimumInferenceIntervalMs = 0L,
        modelInputWidthPx = 320,
        modelInputHeightPx = 240
    ),
    BALANCED(
        label = "Баланс",
        description = "2 руки · 480×360 · около 15 кадров/с",
        maxTrackedHands = 2,
        cameraWidthPx = 480,
        cameraHeightPx = 360,
        minimumInferenceIntervalMs = 66L
    ),
    RESPONSIVE(
        label = "Плавность",
        description = "2 руки · 640×480 · около 20 кадров/с",
        maxTrackedHands = 2,
        cameraWidthPx = 640,
        cameraHeightPx = 480,
        minimumInferenceIntervalMs = 50L
    );

    companion object {
        fun recommend(tier: DevicePerformanceTier): HandTrackingPreset = when (tier) {
            DevicePerformanceTier.ECONOMY -> SUPER_ECONOMY
            DevicePerformanceTier.BALANCED -> BALANCED
            DevicePerformanceTier.CAPABLE -> RESPONSIVE
        }

        fun fromStoredName(value: String?): HandTrackingPreset =
            entries.firstOrNull { it.name == value } ?: BALANCED
    }
}

/**
 * Explicit delegate choice for controlled on-device comparisons.
 * GPU_PREFERRED still falls back to CPU if the GPU delegate cannot initialize.
 */
enum class HandTrackingDelegatePreference(
    val label: String,
    val delegate: Delegate
) {
    GPU_PREFERRED("GPU · резервный CPU", Delegate.GPU),
    CPU_ONLY("Только CPU", Delegate.CPU);

    companion object {
        fun fromStoredName(value: String?): HandTrackingDelegatePreference =
            entries.firstOrNull { it.name == value } ?: GPU_PREFERRED
    }
}

/** Persists the selected hand-tracking workload and delegate preference. */
class HandTrackingPresetStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(fallback: HandTrackingPreset): HandTrackingPreset =
        HandTrackingPreset.fromStoredName(preferences.getString(KEY_PRESET, fallback.name))

    fun save(preset: HandTrackingPreset) {
        preferences.edit().putString(KEY_PRESET, preset.name).apply()
    }

    fun loadDelegatePreference(
        fallback: HandTrackingDelegatePreference = HandTrackingDelegatePreference.GPU_PREFERRED
    ): HandTrackingDelegatePreference = HandTrackingDelegatePreference.fromStoredName(
        preferences.getString(KEY_DELEGATE, fallback.name)
    )

    fun saveDelegatePreference(preference: HandTrackingDelegatePreference) {
        preferences.edit().putString(KEY_DELEGATE, preference.name).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "hand_tracking_preferences"
        const val KEY_PRESET = "tracking_preset"
        const val KEY_DELEGATE = "tracking_delegate"
    }
}
