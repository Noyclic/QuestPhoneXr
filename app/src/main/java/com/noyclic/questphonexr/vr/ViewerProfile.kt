package com.noyclic.questphonexr.vr

import android.content.Context

/**
 * Calibration values for a phone-based stereo viewer such as VR Shinecon.
 *
 * These values adjust the preview geometry only. They do not claim optical lens
 * distortion correction or a measured physical IPD for a particular headset.
 */
data class ViewerProfile(
    val ipdMillimeters: Int = DEFAULT_IPD_MM,
    val fieldOfViewDegrees: Int = DEFAULT_FOV_DEGREES,
    val swapEyes: Boolean = false
) {
    fun normalized(): ViewerProfile = copy(
        ipdMillimeters = ipdMillimeters.coerceIn(MIN_IPD_MM, MAX_IPD_MM),
        fieldOfViewDegrees = fieldOfViewDegrees.coerceIn(MIN_FOV_DEGREES, MAX_FOV_DEGREES)
    )

    companion object {
        const val MIN_IPD_MM = 50
        const val MAX_IPD_MM = 75
        const val DEFAULT_IPD_MM = 64
        const val MIN_FOV_DEGREES = 70
        const val MAX_FOV_DEGREES = 100
        const val DEFAULT_FOV_DEGREES = 86
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
        swapEyes = preferences.getBoolean(KEY_SWAP_EYES, false)
    ).normalized()

    fun save(profile: ViewerProfile) {
        val safeProfile = profile.normalized()
        preferences.edit()
            .putInt(KEY_IPD_MM, safeProfile.ipdMillimeters)
            .putInt(KEY_FOV, safeProfile.fieldOfViewDegrees)
            .putBoolean(KEY_SWAP_EYES, safeProfile.swapEyes)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "shinecon_viewer_profile"
        const val KEY_IPD_MM = "ipd_mm"
        const val KEY_FOV = "fov_degrees"
        const val KEY_SWAP_EYES = "swap_eyes"
    }
}
