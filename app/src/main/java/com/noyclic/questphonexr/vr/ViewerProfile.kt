package com.noyclic.questphonexr.vr

import android.content.Context
import kotlin.math.abs

/**
 * Calibration and performance values for a phone-based stereo viewer such as VR Shinecon.
 *
 * These values adjust the preview only. They do not claim optical lens distortion correction
 * or a measured physical IPD for a particular headset.
 */
data class ViewerProfile(
    val ipdMillimeters: Int = DEFAULT_IPD_MM,
    val fieldOfViewDegrees: Int = DEFAULT_FOV_DEGREES,
    val swapEyes: Boolean = false,
    val targetFps: Int = DEFAULT_TARGET_FPS,
    /** Zero means use the automatic device profile. */
    val cameraWidthPx: Int = AUTO_CAMERA_SIZE,
    /** Zero means use the automatic device profile. */
    val cameraHeightPx: Int = AUTO_CAMERA_SIZE
) {
    fun normalized(): ViewerProfile = copy(
        ipdMillimeters = ipdMillimeters.coerceIn(MIN_IPD_MM, MAX_IPD_MM),
        fieldOfViewDegrees = fieldOfViewDegrees.coerceIn(MIN_FOV_DEGREES, MAX_FOV_DEGREES),
        targetFps = SUPPORTED_TARGET_FPS.minBy { abs(it - targetFps) },
        cameraWidthPx = normalizeCameraDimension(cameraWidthPx, MIN_CAMERA_WIDTH_PX, MAX_CAMERA_WIDTH_PX),
        cameraHeightPx = normalizeCameraDimension(cameraHeightPx, MIN_CAMERA_HEIGHT_PX, MAX_CAMERA_HEIGHT_PX)
    )

    companion object {
        const val MIN_IPD_MM = 50
        const val MAX_IPD_MM = 75
        const val DEFAULT_IPD_MM = 64
        const val MIN_FOV_DEGREES = 70
        const val MAX_FOV_DEGREES = 100
        const val DEFAULT_FOV_DEGREES = 86
        const val DEFAULT_TARGET_FPS = 30
        const val AUTO_CAMERA_SIZE = 0
        const val MIN_CAMERA_WIDTH_PX = 64
        const val MAX_CAMERA_WIDTH_PX = 1920
        const val MIN_CAMERA_HEIGHT_PX = 48
        const val MAX_CAMERA_HEIGHT_PX = 1440
        val SUPPORTED_TARGET_FPS = listOf(20, 30, 45, 60)

        private fun normalizeCameraDimension(value: Int, minimum: Int, maximum: Int): Int =
            if (value == AUTO_CAMERA_SIZE) AUTO_CAMERA_SIZE else value.coerceIn(minimum, maximum)
    }
}

/** Stores the last calibration locally on this phone. */
class ViewerProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): ViewerProfile = ViewerProfile(
        ipdMillimeters = preferences.getInt(KEY_IPD_MM, ViewerProfile.DEFAULT_IPD_MM),
        fieldOfViewDegrees = preferences.getInt(
            KEY_FOV,
            ViewerProfile.DEFAULT_FOV_DEGREES
        ),
        swapEyes = preferences.getBoolean(KEY_SWAP_EYES, false),
        targetFps = preferences.getInt(KEY_TARGET_FPS, ViewerProfile.DEFAULT_TARGET_FPS),
        cameraWidthPx = preferences.getInt(KEY_CAMERA_WIDTH, ViewerProfile.AUTO_CAMERA_SIZE),
        cameraHeightPx = preferences.getInt(KEY_CAMERA_HEIGHT, ViewerProfile.AUTO_CAMERA_SIZE)
    ).normalized()

    fun save(profile: ViewerProfile) {
        val safeProfile = profile.normalized()
        preferences.edit()
            .putInt(KEY_IPD_MM, safeProfile.ipdMillimeters)
            .putInt(KEY_FOV, safeProfile.fieldOfViewDegrees)
            .putBoolean(KEY_SWAP_EYES, safeProfile.swapEyes)
            .putInt(KEY_TARGET_FPS, safeProfile.targetFps)
            .putInt(KEY_CAMERA_WIDTH, safeProfile.cameraWidthPx)
            .putInt(KEY_CAMERA_HEIGHT, safeProfile.cameraHeightPx)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "shinecon_viewer_profile"
        const val KEY_IPD_MM = "ipd_mm"
        const val KEY_FOV = "fov_degrees"
        const val KEY_SWAP_EYES = "swap_eyes"
        const val KEY_TARGET_FPS = "target_fps"
        const val KEY_CAMERA_WIDTH = "camera_width_px"
        const val KEY_CAMERA_HEIGHT = "camera_height_px"
    }
}
