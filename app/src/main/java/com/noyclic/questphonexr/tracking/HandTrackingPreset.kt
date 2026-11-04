package com.noyclic.questphonexr.tracking

import android.content.Context
import com.noyclic.questphonexr.performance.DevicePerformanceTier

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
    val minimumInferenceIntervalMs: Long
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
        description = "2 руки · 320×240 · около 10 кадров/с",
        maxTrackedHands = 2,
        cameraWidthPx = 320,
        cameraHeightPx = 240,
        minimumInferenceIntervalMs = 100L
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

/** Persists only the user's selected workload preset; automatic dimensions remain automatic. */
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

    private companion object {
        const val PREFERENCES_NAME = "hand_tracking_preferences"
        const val KEY_PRESET = "tracking_preset"
    }
}
