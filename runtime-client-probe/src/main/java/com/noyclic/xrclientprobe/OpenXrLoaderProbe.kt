package com.noyclic.xrclientprobe

import android.app.Activity

/**
 * Runs the official Khronos Android OpenXR loader from this separate APK.
 * The native call receives the real Activity required by XR_KHR_android_create_instance.
 */
internal object OpenXrLoaderProbe {
    fun inspect(activity: Activity): String = runCatching {
        System.loadLibrary("openxr_loader_probe")
        nativeRun(activity)
    }.getOrElse { error ->
        buildString {
            appendLine("Khronos OpenXR Android Loader probe")
            appendLine("native_probe_start=FAILED")
            appendLine("error=${error.javaClass.simpleName}: ${error.message ?: "no message"}")
        }
    }

    private external fun nativeRun(activity: Activity): String
}
