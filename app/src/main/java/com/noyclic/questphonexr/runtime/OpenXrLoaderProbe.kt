package com.noyclic.questphonexr.runtime

import android.content.Context
import org.json.JSONObject

data class OpenXrLoaderProbeReport(
    val status: String,
    val loaderInitResult: Int?,
    val extensionEnumerationResult: Int?,
    val instanceCreateResult: Int?,
    val getSystemResult: Int?,
    val systemName: String,
    val message: String,
    val extensions: List<String>
) {
    fun toDisplayText(): String = buildString {
        append(when (status) {
            "INSTANCE_CREATED_SYSTEM_FOUND" -> "OpenXR instance создан, HMD system найден"
            "INSTANCE_CREATED_NO_HMD_SYSTEM" -> "OpenXR instance создан, HMD system не найден"
            "INSTANCE_CREATION_FAILED" -> "Runtime найден, но xrCreateInstance завершился ошибкой"
            "ANDROID_INSTANCE_EXTENSION_UNAVAILABLE" -> "Runtime не поддерживает Android instance"
            "RUNTIME_FOUND" -> "OpenXR Loader и активный Runtime отвечают"
            "RUNTIME_ENUMERATION_FAILED" -> "Loader запустился, Runtime не ответил"
            "LOADER_INITIALIZATION_FAILED" -> "Не удалось инициализировать OpenXR Loader"
            "ANDROID_CONTEXT_UNAVAILABLE" -> "Android Context недоступен"
            else -> "OpenXR smoke test: " + status
        })
        loaderInitResult?.let { append("\nxrInitializeLoaderKHR: ").append(it) }
        extensionEnumerationResult?.let { append("\nxrEnumerateInstanceExtensionProperties: ").append(it) }
        instanceCreateResult?.let { append("\nxrCreateInstance: ").append(it) }
        getSystemResult?.let { append("\nxrGetSystem: ").append(it) }
        if (systemName.isNotBlank()) append("\nHMD system: ").append(systemName)
        append("\n").append(message)
        if (extensions.isNotEmpty()) {
            append("\nРасширения Runtime (").append(extensions.size).append("):\\n")
            extensions.forEach { append("• ").append(it).append("\n") }
        }
        append("\nНе проверены: xrCreateSession, graphics binding, swapchain, compositor, frame loop и ввод.")
    }
}

/**
 * Uses the Khronos Android OpenXR Loader AAR. The native smoke test verifies loader
 * initialization, active-runtime extension enumeration, xrCreateInstance and xrGetSystem.
 * It does not create an XR session or exercise a graphics compositor.
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
                "NATIVE_LIBRARY_UNAVAILABLE", null, null, null, null, "",
                "Native XR probe library unavailable: " + (loadError ?: "unknown error"),
                emptyList()
            )
        }
        return runCatching {
            val root = JSONObject(nativeProbe(context))
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
                instanceCreateResult = root.optInt("instanceCreateResult")
                    .takeIf { root.has("instanceCreateResult") },
                getSystemResult = root.optInt("getSystemResult")
                    .takeIf { root.has("getSystemResult") },
                systemName = root.optString("systemName", ""),
                message = root.optString("message", "No diagnostic message."),
                extensions = extensions
            )
        }.getOrElse { error ->
            OpenXrLoaderProbeReport(
                "NATIVE_PROBE_FAILED", null, null, null, null, "",
                error.message ?: error.javaClass.simpleName,
                emptyList()
            )
        }
    }
}
