package com.noyclic.questphonexr.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import java.io.File
import java.util.zip.ZipFile

data class MonadoServiceStatus(
    val name: String,
    val exported: Boolean,
    val enabled: Boolean,
    val permission: String?
)

data class MonadoAndroidRuntimeReport(
    val installed: Boolean,
    val versionName: String?,
    val versionCode: Long?,
    val applicationEnabled: Boolean,
    val nativeLibraryDirectory: String?,
    val monadoLibraryExists: Boolean,
    val ipcService: MonadoServiceStatus?,
    val openXrRuntimeService: MonadoServiceStatus?,
    val runtimeSoFilename: String?,
    val runtimeMajorVersion: String?,
    val message: String
) {
    fun toDisplayText(): String = buildString {
        append("Monado APK: ").append(if (installed) "установлен" else "не найден")
        if (!installed) {
            append("\n").append(message)
            append("\nОжидаемый package: ").append(MonadoAndroidRuntimeProbe.PACKAGE_NAME)
            return@buildString
        }
        append("\nВерсия: ").append(versionName ?: "не указана")
        versionCode?.let { append(" · code ").append(it) }
        append("\nПриложение включено: ").append(if (applicationEnabled) "да" else "нет")
        append("\nNative lib dir: ").append(nativeLibraryDirectory ?: "неизвестно")
        append("\nlibopenxr_monado.so: ").append(if (monadoLibraryExists) "найдена" else "не найдена")
        append("\nMonado Binder service: ").append(serviceDescription(ipcService, "не найден"))
        append("\nOpenXR Runtime service: ").append(serviceDescription(openXrRuntimeService, "не найден"))
        runtimeSoFilename?.let { append("\nRuntime SoFilename: ").append(it) }
        runtimeMajorVersion?.let { append("\nRuntime MajorVersion: ").append(it) }
        append("\n").append(message)
        append("\nПроверены только package и manifest. Binder bind и xrCreateInstance не вызывались.")
    }

    private fun serviceDescription(service: MonadoServiceStatus?, missing: String): String =
        service?.let {
            it.name + " · " + (if (it.enabled) "enabled" else "disabled") + " · " +
                (if (it.exported) "exported" else "not exported") +
                (it.permission?.let { permission -> " · permission " + permission } ?: "")
        } ?: missing
}

/**
 * Reads the installed out-of-process APK and its manifest. It deliberately
 * does not bind MonadoService because the observed failure is in Binder setup.
 */
object MonadoAndroidRuntimeProbe {
    const val PACKAGE_NAME = "org.freedesktop.monado.openxr_runtime.out_of_process"
    private const val RUNTIME_SERVICE_ACTION = "org.khronos.openxr.OpenXRRuntimeService"
    private const val SO_FILENAME_KEY = "org.khronos.openxr.OpenXRRuntime.SoFilename"
    private const val MAJOR_VERSION_KEY = "org.khronos.openxr.OpenXRRuntime.MajorVersion"

    fun inspect(context: Context): MonadoAndroidRuntimeReport {
        val pm = context.packageManager
        val info = runCatching {
            pm.getPackageInfo(
                PACKAGE_NAME,
                PackageManager.GET_SERVICES or PackageManager.GET_META_DATA
            )
        }.getOrNull() ?: return MonadoAndroidRuntimeReport(
            false, null, null, false, null, false, null, null, null, null,
            "Monado out-of-process APK не найден среди видимых Android-пакетов."
        )

        val appInfo = info.applicationInfo
        val appEnabled = appInfo?.enabled == true
        val nativeDir = appInfo?.nativeLibraryDir
        val extractedLibraryExists = nativeDir?.let { File(it, "libopenxr_monado.so").isFile } == true
        val packagedLibraryExists = appInfo?.sourceDir?.let { apkPath ->
            runCatching {
                ZipFile(apkPath).use { apk ->
                    apk.entries().asSequence().any { entry ->
                        !entry.isDirectory &&
                            entry.name.startsWith("lib/") &&
                            entry.name.endsWith("/libopenxr_monado.so")
                    }
                }
            }.getOrDefault(false)
        } == true
        val libraryExists = extractedLibraryExists || packagedLibraryExists
        val services = info.services.orEmpty()
        val ipc = services.firstOrNull {
            it.name.endsWith(".ipc.MonadoService") ||
                it.name == "org.freedesktop.monado.ipc.MonadoService"
        }
        val runtimeService = runCatching {
            pm.queryIntentServices(Intent(RUNTIME_SERVICE_ACTION), PackageManager.MATCH_ALL)
                .mapNotNull { it.serviceInfo }
                .firstOrNull { it.packageName == PACKAGE_NAME }
        }.getOrNull()

        fun status(service: ServiceInfo?): MonadoServiceStatus? = service?.let {
            MonadoServiceStatus(
                it.name, it.exported, it.enabled && appEnabled, it.permission
            )
        }

        val ipcStatus = status(ipc)
        val runtimeStatus = status(runtimeService)
        val metadata = runtimeService?.metaData
        val message = when {
            !appEnabled -> "APK установлен, но приложение Monado отключено в Android."
            !libraryExists -> "APK найден, но libopenxr_monado.so не найдена в nativeLibraryDir."
            ipcStatus == null -> "Библиотека найдена, но MonadoService не обнаружен в манифесте."
            !ipcStatus.enabled -> "MonadoService объявлен, но отключён."
            !ipcStatus.exported -> "MonadoService не экспортирован; внешний клиент может не иметь права подключиться."
            runtimeStatus == null -> "MonadoService найден, но OpenXR Runtime service action не обнаружен."
            !runtimeStatus.enabled -> "OpenXR Runtime service найден, но отключён."
            !runtimeStatus.exported -> "OpenXR Runtime service не экспортирован."
            else -> "APK, native library и Android service declarations обнаружены. Binder handshake ещё не проверен."
        }

        return MonadoAndroidRuntimeReport(
            installed = true,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            applicationEnabled = appEnabled,
            nativeLibraryDirectory = nativeDir,
            monadoLibraryExists = libraryExists,
            ipcService = ipcStatus,
            openXrRuntimeService = runtimeStatus,
            runtimeSoFilename = metadata?.getString(SO_FILENAME_KEY),
            runtimeMajorVersion = metadata?.get(MAJOR_VERSION_KEY)?.toString(),
            message = message
        )
    }
}
