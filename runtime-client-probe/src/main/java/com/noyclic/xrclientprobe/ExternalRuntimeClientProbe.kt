package com.noyclic.xrclientprobe

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.io.File

data class ExternalRuntimeProbeReport(
    val clientPackage: String,
    val serviceCount: Int,
    val endXrServiceFound: Boolean,
    val endXrServiceExported: Boolean,
    val endXrServicePackage: String?,
    val openXrMajorVersion: Int?,
    val soFilename: String?,
    val nativeLibraryPath: String?,
    val nativeLibraryExists: Boolean,
    val installableBroker: String,
    val systemBroker: String,
    val queryError: String?
) {
    fun toDisplayText(): String = buildString {
        appendLine("QuestPhoneXR external-process discovery probe")
        appendLine("Client package: $clientPackage")
        appendLine("Runtime service matches: $serviceCount")
        appendLine("EndXR service visible: ${if (endXrServiceFound) "YES" else "NO"}")
        appendLine("EndXR service exported: ${if (endXrServiceExported) "YES" else "NO"}")
        endXrServicePackage?.let { appendLine("EndXR package: $it") }
        appendLine("OpenXR major version: ${openXrMajorVersion ?: "missing"}")
        appendLine("Runtime library filename: ${soFilename ?: "missing"}")
        nativeLibraryPath?.let { appendLine("Runtime library path: $it") }
        appendLine("Runtime library exists: ${if (nativeLibraryExists) "YES" else "NO"}")
        appendLine("Installable RuntimeBroker: $installableBroker")
        appendLine("System RuntimeBroker: $systemBroker")
        queryError?.let { appendLine("PackageManager query error: $it") }
        appendLine()
        appendLine("This is a separate Android package, not QuestPhoneXR itself.")
        append("It verifies service discoverability and broker responses, not OpenXR loader dlopen or xrCreateInstance.")
    }
}

/**
 * Runs from package com.noyclic.xrclientprobe, independently of the QuestPhoneXR process.
 * Mirrors the metadata and broker discovery portion of the Android OpenXR loader.
 */
object ExternalRuntimeClientProbe {
    private const val ACTION_RUNTIME_SERVICE = "org.khronos.openxr.OpenXRRuntimeService"
    private const val ENDXR_PACKAGE = "com.noyclic.questphonexr"
    private const val SO_FILENAME_KEY = "org.khronos.openxr.OpenXRRuntime.SoFilename"
    private const val MAJOR_VERSION_KEY = "org.khronos.openxr.OpenXRRuntime.MajorVersion"
    private const val INSTALLABLE_AUTHORITY = "org.khronos.openxr.runtime_broker"
    private const val SYSTEM_AUTHORITY = "org.khronos.openxr.system_runtime_broker"

    fun inspect(context: Context): ExternalRuntimeProbeReport {
        val query = runCatching {
            context.packageManager.queryIntentServices(
                Intent(ACTION_RUNTIME_SERVICE),
                PackageManager.GET_META_DATA
            )
        }
        val services = query.getOrNull().orEmpty()
        val endXr = services.firstOrNull { it.serviceInfo?.packageName == ENDXR_PACKAGE }?.serviceInfo
        val metadata = endXr?.metaData
        val soFilename = safeString(metadata, SO_FILENAME_KEY)
        val majorVersion = safeInt(metadata, MAJOR_VERSION_KEY)
        val nativeLibraryPath = if (endXr != null && !soFilename.isNullOrBlank()) {
            File(endXr.applicationInfo.nativeLibraryDir, soFilename).absolutePath
        } else null

        return ExternalRuntimeProbeReport(
            clientPackage = context.packageName,
            serviceCount = services.size,
            endXrServiceFound = endXr != null,
            endXrServiceExported = endXr?.exported == true,
            endXrServicePackage = endXr?.packageName,
            openXrMajorVersion = majorVersion,
            soFilename = soFilename,
            nativeLibraryPath = nativeLibraryPath,
            nativeLibraryExists = nativeLibraryPath?.let { File(it).isFile } == true,
            installableBroker = queryActiveRuntime(context, INSTALLABLE_AUTHORITY),
            systemBroker = queryActiveRuntime(context, SYSTEM_AUTHORITY),
            queryError = query.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        )
    }

    private fun safeString(bundle: Bundle?, key: String): String? =
        runCatching { bundle?.getString(key) }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun safeInt(bundle: Bundle?, key: String): Int? {
        val value = runCatching { bundle?.getInt(key, -1) }.getOrNull()
        if (value != null && value > 0) return value
        return runCatching { bundle?.getString(key)?.toIntOrNull() }.getOrNull()
    }

    private fun queryActiveRuntime(context: Context, authority: String): String {
        val provider = runCatching {
            context.packageManager.resolveContentProvider(authority, 0)
        }.getOrNull() ?: return "provider not installed or not visible"

        val abi = Build.SUPPORTED_ABIS.firstOrNull()
            ?: return "provider found (${provider.packageName}); device ABI unknown"
        val uri = Uri.parse("content://$authority/openxr/1/abi/$abi/runtimes/active/0")

        return runCatching {
            val projection = arrayOf("package_name", "native_lib_dir", "so_filename", "has_functions")
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
                ?: return@runCatching "provider found; query returned null"
            cursor.use {
                if (!it.moveToFirst()) {
                    return@use "provider found; no active runtime selected for $abi"
                }
                val packageName = it.getString(it.getColumnIndexOrThrow("package_name"))
                val libraryDirectory = it.getString(it.getColumnIndexOrThrow("native_lib_dir"))
                val libraryName = it.getString(it.getColumnIndexOrThrow("so_filename"))
                val library = File(libraryDirectory, libraryName)
                "active package=$packageName; library=$library; exists=${library.isFile}"
            }
        }.getOrElse {
            "provider found (${provider.packageName}); query failed: ${it.message ?: it.javaClass.simpleName}"
        }
    }
}
