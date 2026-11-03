package com.noyclic.questphonexr.runtime

import android.content.Context
import org.json.JSONObject

/**
 * Runs only on the GLSurfaceView GL thread, where EGL is current.
 * Creates and immediately destroys a diagnostic OpenXR GLES session.
 */
object OpenXrGlesSessionProbe {
    @Volatile private var libraryLoaded = false
    @Volatile private var loadError: String? = null

    private external fun nativeProbeSession(activity: Context): String

    fun inspect(activity: Context): String = inspectReport(activity).toDisplayText()

    fun inspectReport(activity: Context): OpenXrProbeReport {
        if (!libraryLoaded) {
            synchronized(this) {
                if (!libraryLoaded) {
                    try {
                        System.loadLibrary("questphonexr_vulkan_probe")
                        libraryLoaded = true
                    } catch (error: Throwable) {
                        loadError = error.message ?: error.javaClass.simpleName
                    }
                }
            }
        }
        if (!libraryLoaded) {
            return OpenXrProbeReport(
                status = "NATIVE_LIBRARY_UNAVAILABLE",
                xrResult = null,
                message = "Native probe unavailable: " + (loadError ?: "unknown error")
            )
        }
        return runCatching {
            OpenXrProbeReport.parse(nativeProbeSession(activity))
        }.getOrElse { error ->
            OpenXrProbeReport(
                status = "NATIVE_PROBE_FAILED",
                xrResult = null,
                message = error.message ?: error.javaClass.simpleName
            )
        }
    }
}
