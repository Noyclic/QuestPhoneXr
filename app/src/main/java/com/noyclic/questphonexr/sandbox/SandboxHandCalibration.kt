package com.noyclic.questphonexr.sandbox

import android.content.Context

/** User-adjustable mapping from camera hand landmarks into the sandbox interaction space. */
data class SandboxHandCalibration(
    val horizontalOffset: Float = 0f,
    val verticalOffset: Float = 0f,
    val horizontalScale: Float = 1f,
    val verticalScale: Float = 1f,
    val relativeDepthScale: Float = DEFAULT_DEPTH_SCALE
) {
    fun normalized(): SandboxHandCalibration = copy(
        horizontalOffset = horizontalOffset.coerceIn(-MAX_OFFSET_METERS, MAX_OFFSET_METERS),
        verticalOffset = verticalOffset.coerceIn(-MAX_OFFSET_METERS, MAX_OFFSET_METERS),
        horizontalScale = horizontalScale.coerceIn(MIN_SCALE, MAX_SCALE),
        verticalScale = verticalScale.coerceIn(MIN_SCALE, MAX_SCALE),
        relativeDepthScale = relativeDepthScale.coerceIn(0f, MAX_DEPTH_SCALE)
    )

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 1.5f
        const val MAX_OFFSET_METERS = 0.75f
        const val MAX_DEPTH_SCALE = 1f
        const val DEFAULT_DEPTH_SCALE = 0.55f
        val DEFAULT = SandboxHandCalibration()
    }
}

/** Persists hand reach calibration independently from the VR lens/refresh profile. */
class SandboxHandCalibrationStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): SandboxHandCalibration = SandboxHandCalibration(
        horizontalOffset = preferences.getFloat(KEY_OFFSET_X, 0f),
        verticalOffset = preferences.getFloat(KEY_OFFSET_Y, 0f),
        horizontalScale = preferences.getFloat(KEY_SCALE_X, 1f),
        verticalScale = preferences.getFloat(KEY_SCALE_Y, 1f),
        relativeDepthScale = preferences.getFloat(
            KEY_DEPTH_SCALE,
            SandboxHandCalibration.DEFAULT_DEPTH_SCALE
        )
    ).normalized()

    fun save(calibration: SandboxHandCalibration) {
        val safe = calibration.normalized()
        preferences.edit()
            .putFloat(KEY_OFFSET_X, safe.horizontalOffset)
            .putFloat(KEY_OFFSET_Y, safe.verticalOffset)
            .putFloat(KEY_SCALE_X, safe.horizontalScale)
            .putFloat(KEY_SCALE_Y, safe.verticalScale)
            .putFloat(KEY_DEPTH_SCALE, safe.relativeDepthScale)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "sandbox_hand_calibration"
        const val KEY_OFFSET_X = "offset_x"
        const val KEY_OFFSET_Y = "offset_y"
        const val KEY_SCALE_X = "scale_x"
        const val KEY_SCALE_Y = "scale_y"
        const val KEY_DEPTH_SCALE = "depth_scale"
    }
}
