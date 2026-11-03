package com.noyclic.questphonexr.runtime

import android.content.Context
import org.json.JSONObject

data class OpenXrLoaderProbeReport(
    val status: String,
    val loaderInitResult: Int?,
    val extensionEnumerationResult: Int?,
    val message: String,
    val extensions: List<String>
) {
    fun toDisplayText(): String = buildString {
        append(when (status) {
            "RUNTIME_FOUND" -> "OpenXR Loader и активный Runtime отвечают"
            "RUNTIME_ENUMERATION_FAILED" -> "Loader запустился, Runtime не ответил"
            "LOADER_INITIALIZATION_FAILED" -> "Не удалось инициализировать OpenXR Loader"
            "ANDROID_CONTEXT_UNAVAILABLE" -> "Android Context недоступен"
            else -> "OpenXR smoke test: " + status
        })
        loaderInitResult?.let { append("\nxrInitializeLoaderKHR: ").append(it) }
        extensionEnumerationResult?.let { append("\nxrEnumerateInstanceExtensionProperties: ").append(it) }
        append("\n").append(message)
        if (extensions.isNotEmpty()) {
            append("\nРасширения Runtime (").append(extensions.size).append("):\n")
            extensions.forEach { append("• ").append(it).append("\n") }
        }
        append("\nПроверка создаёт и уничтожает OpenXR instance и запрашивает HMD system; session/swapchain/compositor пока не проверены.")
    }
}

/**
 * Uses the Khronos Android OpenXR Loader AAR. A successful result verifies
 * loader initialization and active-runtime extension enumeration only.
 */
object OpenXrLoaderProbe {
    @Volatile private var libraryLoaded = false
    @Volatile private var loadError: String? = null

    private external fun nativeProbe(context: Context): String

    fun inspect(context: Context): OpenXrLoaderProbeReport {
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
            return OpenXrLoaderProbeReport(
                "NATIVE_LIBRARY_UNAVAILABLE", null, null,
                "Native XR probe library unavailable: " + (loadError ?: "unknown error"),
                emptyList()
            )
        }
        return runCatching {
            val root = JSONObject(nativeProbe(context.applicationContext))
            val extensionsJson = root.optJSONArray("extensions")
            val extensions = buildList {
                if (extensionsJson != null) {
                    for (index in 0 until extensionsJson.length()) {
                        add(extensionsJson.optString(index))
                    }
                }
            }
            OpenXrLoaderProbeReport(
                status = root.optString("status", "UNKNOWN"),
                loaderInitResult = root.optInt("loaderInitResult").takeIf { root.has("loaderInitResult") },
                extensionEnumerationResult = root.optInt("extensionEnumerationResult")
                    .takeIf { root.has("extensionEnumerationResult") },
                message = root.optString("message", "No diagnostic message."),
                extensions = extensions
            )
        }.getOrElse { error ->
            OpenXrLoaderProbeReport(
                "NATIVE_PROBE_FAILED", null, null,
                error.message ?: error.javaClass.simpleName,
                emptyList()
            )
        }
    }
}
