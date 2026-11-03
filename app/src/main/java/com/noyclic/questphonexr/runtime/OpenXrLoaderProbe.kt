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
            "INSTANCE_CREATION_FAILED" -> "Перечисление расширений прошло; xrCreateInstance завершился ошибкой"
            "ANDROID_INSTANCE_EXTENSION_UNAVAILABLE" -> "Runtime не поддерживает Android instance"
            "RUNTIME_FOUND" -> "OpenXR Loader и активный Runtime отвечают"
            "RUNTIME_ENUMERATION_FAILED" -> "Не удалось перечислить расширения OpenXR"
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
            append("\nРасширения, перечисленные Khronos Loader (").append(extensions.size).append("):\\n")
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
    private external fun nativeProbeRuntimeLibrary(path: String): String
    private external fun nativeProbeRuntimeInstance(path: String, activity: Context): String

    private fun ensureNativeLibraryLoaded(): Boolean {
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
        return libraryLoaded
    }

    /** Mirrors the Khronos loader's dlopen step without creating an OpenXR instance. */
    fun inspectRuntimeLibrary(path: String): String {
        if (!ensureNativeLibraryLoaded()) {
            return "Native diagnostic library unavailable: " + (loadError ?: "unknown error")
        }
        return runCatching {
            val root = JSONObject(nativeProbeRuntimeLibrary(path))
            buildString {
                append("Результат: ").append(root.optString("status", "UNKNOWN"))
                root.optString("path").takeIf { it.isNotBlank() }?.let {
                    append("\nПуть: ").append(it)
                }
                if (root.has("negotiationExportFound")) {
                    append("\nЭкспорт xrNegotiateLoaderRuntimeInterface: ")
                        .append(if (root.optBoolean("negotiationExportFound")) "найден" else "не найден")
                }
                if (root.has("negotiationResult")) {
                    append("\nРезультат negotiation: ")
                        .append(root.optString("negotiationResultName", root.optInt("negotiationResult").toString()))
                }
                if (root.has("runtimeInterfaceVersion")) {
                    append("\nRuntime interface version: ").append(root.optInt("runtimeInterfaceVersion"))
                }
                root.optString("runtimeApiVersionName").takeIf { it.isNotBlank() }?.let {
                    append("\nRuntime OpenXR API: ").append(it)
                }
                if (root.has("getInstanceProcAddrAvailable")) {
                    append("\nxrGetInstanceProcAddr: ")
                        .append(if (root.optBoolean("getInstanceProcAddrAvailable")) "получен" else "отсутствует")
                }
                if (root.has("interfaceVersionValid")) {
                    append("\nВерсия интерфейса совместима: ")
                        .append(if (root.optBoolean("interfaceVersionValid")) "да" else "нет")
                }
                if (root.has("apiVersionValid")) {
                    append("\nВерсия OpenXR API совместима: ")
                        .append(if (root.optBoolean("apiVersionValid")) "да" else "нет")
                }
                if (root.has("xrCreateInstanceProcResult")) {
                    append("\nПолучение xrCreateInstance: ")
                        .append(root.optInt("xrCreateInstanceProcResult"))
                        .append(if (root.optBoolean("xrCreateInstanceProcAvailable")) " · функция получена" else " · функция недоступна")
                }
                root.optString("message").takeIf { it.isNotBlank() }?.let {
                    append("\n").append(it)
                }
            }
        }.getOrElse { error ->
            "Native runtime negotiation probe failed: " +
                (error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Calls the negotiated Monado runtime directly after the Khronos Loader smoke
     * test has initialized it in this same process. This bypasses API layers.
     */
    fun inspectRuntimeInstance(context: Context, path: String): String {
        if (!ensureNativeLibraryLoaded()) {
            return "Direct runtime probe unavailable: " + (loadError ?: "unknown error")
        }
        return runCatching {
            val root = JSONObject(nativeProbeRuntimeInstance(path, context))
            buildString {
                append("Результат прямого вызова Monado: ")
                    .append(root.optString("status", "UNKNOWN"))
                root.optString("path").takeIf { it.isNotBlank() }?.let {
                    append("\nПуть: ").append(it)
                }
                if (root.has("negotiationResult")) {
                    append("\nDirect negotiation: ")
                        .append(root.optString("negotiationResultName", root.optInt("negotiationResult").toString()))
                }
                if (root.has("directExtensionProcResult")) {
                    append("\nПрямое получение xrEnumerateInstanceExtensionProperties: ")
                        .append(root.optInt("directExtensionProcResult"))
                }
                if (root.has("directExtensionEnumerationResult")) {
                    append("\nПрямое перечисление расширений Monado: ")
                        .append(root.optInt("directExtensionEnumerationResult"))
                        .append(" (").append(root.optInt("directExtensionCount")).append(")")
                }
                if (root.has("directCreateProcResult")) {
                    append("\nПрямое получение xrCreateInstance: ")
                        .append(root.optInt("directCreateProcResult"))
                }
                if (root.has("directInstanceCreateResult")) {
                    append("\nПрямой xrCreateInstance Monado: ")
                        .append(root.optString(
                            "directInstanceCreateResultName",
                            root.optInt("directInstanceCreateResult").toString()
                        ))
                        .append(" (").append(root.optInt("directInstanceCreateResult")).append(")")
                }
                if (root.has("directInstanceHandleValid")) {
                    append("\nДескриптор Instance: ")
                        .append(if (root.optBoolean("directInstanceHandleValid")) "получен" else "NULL")
                }
                if (root.has("directInstanceDestroyResult")) {
                    append("\nПрямой xrDestroyInstance: ")
                        .append(root.optInt("directInstanceDestroyResult"))
                }
                root.optString("message").takeIf { it.isNotBlank() }?.let {
                    append("\n").append(it)
                }
                val extensions = root.optJSONArray("runtimeExtensions")
                if (extensions != null && extensions.length() > 0) {
                    append("\nРасширения, полученные напрямую от Monado (")
                        .append(extensions.length()).append("):\n")
                    for (index in 0 until extensions.length()) {
                        append("• ").append(extensions.optString(index)).append("\n")
                    }
                }
            }
        }.getOrElse { error ->
            "Direct Monado xrCreateInstance probe failed: " +
                (error.message ?: error.javaClass.simpleName)
        }
    }

    fun inspect(context: Context): OpenXrLoaderProbeReport {
        if (!ensureNativeLibraryLoaded()) {
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
