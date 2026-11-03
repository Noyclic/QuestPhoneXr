package com.noyclic.questphonexr.runtime

import android.app.Activity

/**
 * Owns a persistent OpenXR GLES session on the calling GLSurfaceView render thread.
 *
 * Every method must be called with the Activity's GLES context current. The native
 * implementation retains the Activity while the OpenXR instance is alive and keeps
 * its session/swapchain between frame callbacks.
 */
object OpenXrPersistentGlesSession {
    @Volatile private var libraryLoaded = false
    @Volatile private var loadError: String? = null

    private external fun nativeCreateSession(activity: Activity): String
    private external fun nativeRenderFrame(): String
    private external fun nativePauseSession(): String
    private external fun nativeResumeSession(): String
    private external fun nativeDestroySession(): String

    fun create(activity: Activity): OpenXrProbeReport = invokeNative {
        nativeCreateSession(activity)
    }

    fun renderFrame(): OpenXrProbeReport = invokeNative {
        nativeRenderFrame()
    }

    fun pause(): OpenXrProbeReport = invokeNative {
        nativePauseSession()
    }

    fun resume(): OpenXrProbeReport = invokeNative {
        nativeResumeSession()
    }

    fun destroy(): OpenXrProbeReport = invokeNative {
        nativeDestroySession()
    }

    private inline fun invokeNative(block: () -> String): OpenXrProbeReport {
        if (!ensureLibraryLoaded()) {
            return OpenXrProbeReport(
                status = "NATIVE_LIBRARY_UNAVAILABLE",
                xrResult = null,
                message = "Native persistent OpenXR session is unavailable: " +
                    (loadError ?: "unknown error")
            )
        }
        return runCatching {
            OpenXrProbeReport.parse(block())
        }.getOrElse { error ->
            OpenXrProbeReport(
                status = "NATIVE_SESSION_CALL_FAILED",
                xrResult = null,
                message = error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun ensureLibraryLoaded(): Boolean {
        if (libraryLoaded) return true
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
        return libraryLoaded
    }
}
