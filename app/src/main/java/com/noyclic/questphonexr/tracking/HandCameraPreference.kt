package com.noyclic.questphonexr.tracking

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider

object HandCameraPreference {
    private const val PREFERENCES_NAME = "hand_camera_preferences"
    private const val KEY_FRONT_CAMERA_ENABLED = "front_camera_enabled"

    data class Selection(val selector: CameraSelector, val isFrontCamera: Boolean)

    fun isFrontCameraEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FRONT_CAMERA_ENABLED, false)

    fun setFrontCameraEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FRONT_CAMERA_ENABLED, enabled).apply()
    }

    internal enum class Lens { FRONT, BACK }

    internal fun chooseLens(
        frontEnabled: Boolean,
        frontAvailable: Boolean,
        backAvailable: Boolean
    ): Lens {
        if (frontEnabled && frontAvailable) return Lens.FRONT
        check(backAvailable) {
            "Задняя камера недоступна. Фронтальная камера не будет включена автоматически."
        }
        return Lens.BACK
    }

    fun resolve(context: Context, provider: ProcessCameraProvider): Selection {
        val lens = chooseLens(
            isFrontCameraEnabled(context),
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA),
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        )
        return if (lens == Lens.FRONT) {
            Selection(CameraSelector.DEFAULT_FRONT_CAMERA, true)
        } else {
            Selection(CameraSelector.DEFAULT_BACK_CAMERA, false)
        }
    }
}
