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

    fun inspect(activity: Context): String {
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
            return "native probe unavailable: " + (loadError ?: "unknown error")
        }
        return runCatching {
            val report = JSONObject(nativeProbeSession(activity))
            val status = report.optString("status", "UNKNOWN")
            val message = report.optString("message", "No diagnostic message.")
            val result = if (report.has("xrResult")) {
                " · XrResult " + report.optInt("xrResult")
            } else {
                ""
            }
            status + result + "\n" + message
        }.getOrElse { error ->
            "OpenXR GLES session probe failed: " +
                (error.message ?: error.javaClass.simpleName)
        }
    }
}
