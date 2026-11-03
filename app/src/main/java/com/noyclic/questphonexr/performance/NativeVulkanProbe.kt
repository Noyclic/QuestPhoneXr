package com.noyclic.questphonexr.performance

import com.noyclic.questphonexr.runtime.MonadoVulkanPrerequisites
import org.json.JSONObject

data class NativeVulkanExternalHandle(
    val name: String,
    val queried: Boolean,
    val importable: Boolean,
    val exportable: Boolean
)

data class NativeVulkanDevice(
    val name: String,
    val vendorId: Long,
    val deviceId: Long,
    val driverVersion: Long,
    val apiVersion: String,
    val api11CoreQueries: Boolean,
    val extensionEnumerationSucceeded: Boolean,
    val extensions: Map<String, Boolean>,
    val externalHandles: List<NativeVulkanExternalHandle>
)

data class NativeVulkanProbeReport(
    val available: Boolean,
    val status: String,
    val message: String,
    val loaderVersion: String?,
    val devices: List<NativeVulkanDevice>
) {
    fun toDisplayText(): String {
        val report = this
        return buildString {
        append("Native Vulkan: ")
        append(if (available) "доступен" else "недоступен")
        append(" · ").append(status).append("\n")
        loaderVersion?.let { append("Версия Vulkan Loader: ").append(it).append("\n") }
        append(message).append("\n")
        if (devices.isEmpty()) {
            append("Физические GPU не перечислены.")
        }
        devices.forEachIndexed { index, device ->
            append("\nGPU ").append(index + 1).append(": ").append(device.name).append("\n")
            append("Vendor ID: ").append(device.vendorId)
                .append(" · Device ID: ").append(device.deviceId).append("\n")
            append("Driver version: ").append(device.driverVersion)
                .append(" · API GPU: ").append(device.apiVersion).append("\n")
            append("Core external-handle queries (Vulkan 1.1): ")
                .append(if (device.api11CoreQueries) "доступны" else "не подтверждены").append("\n")
            append("Перечисление расширений: ")
                .append(if (device.extensionEnumerationSucceeded) "успешно" else "ошибка").append("\n")
            append("Расширения:\n")
            device.extensions.forEach { (name, present) ->
                append("• ").append(name).append(": ")
                    .append(if (present) "есть" else "нет").append("\n")
            }
            append("Проверки внешних handle types (импорт/экспорт):\n")
            device.externalHandles.forEach { handle ->
                append("• ").append(handle.name).append(": ")
                if (!handle.queried) {
                    append("не удалось запросить")
                } else {
                    append("импорт ").append(if (handle.importable) "да" else "нет")
                    append(", экспорт ").append(if (handle.exportable) "да" else "нет")
                }
                append("\n")
            }
        }
        append("\nВажно: наличие расширений и поддержка handle type не доказывают, что ")
        append("конкретный XR compositor сможет создать swapchain, импортировать ресурсы и синхронизировать кадры.")
        append(" Это только лёгкая проверка возможностей драйвера; нагрузочный тест не выполнялся.")

        append("\n\nПроверка Vulkan-предпосылок Monado (Android):\n")
        append(MonadoVulkanPrerequisites.assess(report).toDisplayText())
        }
    }
}

/** Loads the optional JNI probe safely. The app remains usable if native loading fails. */
object NativeVulkanProbe {
    @Volatile private var loadError: String? = null
    @Volatile private var libraryLoaded = false

    private external fun nativeProbe(): String

    fun inspect(): NativeVulkanProbeReport {
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
            return NativeVulkanProbeReport(
                available = false,
                status = "NATIVE_LIBRARY_UNAVAILABLE",
                message = "Native Vulkan probe не загрузился: " + (loadError ?: "неизвестная ошибка"),
                loaderVersion = null,
                devices = emptyList()
            )
        }

        return try {
            parse(nativeProbe())
        } catch (error: Throwable) {
            NativeVulkanProbeReport(
                available = false,
                status = "NATIVE_PROBE_FAILED",
                message = error.message ?: error.javaClass.simpleName,
                loaderVersion = null,
                devices = emptyList()
            )
        }
    }

    private fun parse(raw: String): NativeVulkanProbeReport {
        val root = JSONObject(raw)
        val devices = buildList {
            val array = root.optJSONArray("devices") ?: return@buildList
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val extensionObject = item.optJSONObject("extensions")
                val extensions = buildMap {
                    if (extensionObject != null) {
                        val keys = extensionObject.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            put(key, extensionObject.optBoolean(key, false))
                        }
                    }
                }
                val handles = buildList {
                    val handleArray = item.optJSONArray("externalHandles")
                    if (handleArray != null) {
                        for (handleIndex in 0 until handleArray.length()) {
                            val handle = handleArray.optJSONObject(handleIndex) ?: continue
                            add(
                                NativeVulkanExternalHandle(
                                    name = handle.optString("name"),
                                    queried = handle.optBoolean("queried"),
                                    importable = handle.optBoolean("importable"),
                                    exportable = handle.optBoolean("exportable")
                                )
                            )
                        }
                    }
                }
                add(
                    NativeVulkanDevice(
                        name = item.optString("name", "Unknown GPU"),
                        vendorId = item.optLong("vendorId"),
                        deviceId = item.optLong("deviceId"),
                        driverVersion = item.optLong("driverVersion"),
                        apiVersion = item.optString("apiVersion", "unknown"),
                        api11CoreQueries = item.optBoolean("api11CoreQueries"),
                        extensionEnumerationSucceeded = item.optBoolean("extensionEnumerationSucceeded"),
                        extensions = extensions,
                        externalHandles = handles
                    )
                )
            }
        }
        return NativeVulkanProbeReport(
            available = root.optBoolean("available"),
            status = root.optString("status", "UNKNOWN"),
            message = root.optString("message", "Нет описания результата."),
            loaderVersion = root.optString("loaderVersion").takeIf { it.isNotBlank() },
            devices = devices
        )
    }
}
