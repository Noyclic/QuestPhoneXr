package com.noyclic.questphonexr.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.io.File

data class RuntimeBrokerProbeReport(
    val serviceCount: Int,
    val ownRuntimeServiceFound: Boolean,
    val ownRuntimeServiceExported: Boolean,
    val servicePackage: String?,
    val serviceName: String?,
    val majorVersion: Int?,
    val soFilename: String?,
    val nativeLibraryPath: String?,
    val nativeLibraryExists: Boolean,
    val installableBrokerStatus: String,
    val systemBrokerStatus: String,
    val discoveryError: String?
) {
    fun toDisplayText(): String = buildString {
        appendLine("OpenXR runtime service action: $ACTION_NAME")
        appendLine("Services visible to PackageManager: $serviceCount")
        appendLine("EndXR service found: ${if (ownRuntimeServiceFound) "YES" else "NO"}")
        appendLine("EndXR service exported: ${if (ownRuntimeServiceExported) "YES" else "NO"}")
        servicePackage?.let { appendLine("Service package: $it") }
        serviceName?.let { appendLine("Service class: $it") }
        appendLine("OpenXR major version metadata: ${majorVersion ?: "missing"}")
        appendLine("SoFilename metadata: ${soFilename ?: "missing"}")
        nativeLibraryPath?.let { appendLine("Runtime library path: $it") }
        appendLine("Runtime library exists: ${if (nativeLibraryExists) "YES" else "NO"}")
        appendLine("Installable RuntimeBroker: $installableBrokerStatus")
        appendLine("System RuntimeBroker: $systemBrokerStatus")
        discoveryError?.let { appendLine("Service discovery error: $it") }
        appendLine()
        appendLine("IMPORTANT: this checks PackageManager service discovery and broker provider responses.")
        append("It does not prove that a separate game process can load the runtime or render a frame.")
    }

    companion object {
        const val ACTION_NAME = "org.khronos.openxr.OpenXRRuntimeService"
    }
}

/**
 * Checks Android's public OpenXR runtime service and the Khronos RuntimeBroker
 * content-provider endpoints. This is diagnostic only: it never changes the
 * selected runtime and never dlopens a third-party library.
 */
object AndroidRuntimeBrokerProbe {
    private const val KEY_SO_FILENAME = "org.khronos.openxr.OpenXRRuntime.SoFilename"
    private const val KEY_MAJOR_VERSION = "org.khronos.openxr.OpenXRRuntime.MajorVersion"
    private const val INSTALLABLE_AUTHORITY = "org.khronos.openxr.runtime_broker"
    private const val SYSTEM_AUTHORITY = "org.khronos.openxr.system_runtime_broker"

    fun inspect(context: Context): RuntimeBrokerProbeReport {
        val packageManager = context.packageManager
        val query = runCatching {
            packageManager.queryIntentServices(
                Intent(RuntimeBrokerProbeReport.ACTION_NAME),
                PackageManager.GET_META_DATA
            )
        }

        val matches = query.getOrNull().orEmpty()
        val ownResolveInfo = matches.firstOrNull { it.serviceInfo?.packageName == context.packageName }
        val ownService = ownResolveInfo?.serviceInfo
        val metadata = ownService?.metaData
        val soFilename = readString(metadata, KEY_SO_FILENAME)
        val majorVersion = readInt(metadata, KEY_MAJOR_VERSION)
        val nativeLibraryPath = if (ownService != null && !soFilename.isNullOrBlank()) {
            File(ownService.applicationInfo.nativeLibraryDir, soFilename).absolutePath
        } else {
            null
        }
        val libraryExists = nativeLibraryPath?.let { File(it).isFile } == true

        return RuntimeBrokerProbeReport(
            serviceCount = matches.size,
            ownRuntimeServiceFound = ownService != null,
            ownRuntimeServiceExported = ownService?.exported == true,
            servicePackage = ownService?.packageName,
            serviceName = ownService?.name,
            majorVersion = majorVersion,
            soFilename = soFilename,
            nativeLibraryPath = nativeLibraryPath,
            nativeLibraryExists = libraryExists,
            installableBrokerStatus = queryBroker(
                context,
                INSTALLABLE_AUTHORITY,
                "installable RuntimeBroker"
            ),
            systemBrokerStatus = queryBroker(
                context,
                SYSTEM_AUTHORITY,
                "system RuntimeBroker"
            ),
            discoveryError = query.exceptionOrNull()?.let {
                it.message ?: it.javaClass.simpleName
            }
        )
    }

    private fun readString(bundle: Bundle?, key: String): String? =
        runCatching { bundle?.getString(key) }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun readInt(bundle: Bundle?, key: String): Int? {
        val integer = runCatching { bundle?.getInt(key, -1) }.getOrNull()
        if (integer != null && integer > 0) return integer
        return runCatching { bundle?.getString(key)?.toIntOrNull() }.getOrNull()
    }

    private fun queryBroker(context: Context, authority: String, label: String): String {
        val provider = runCatching {
            context.packageManager.resolveContentProvider(authority, 0)
        }.getOrNull() ?: return "provider not found or not visible"

        val abi = Build.SUPPORTED_ABIS.firstOrNull()
            ?: return "provider found (${provider.packageName}), but device ABI is unknown"
        val uri = Uri.parse(
            "content://$authority/openxr/1/abi/$abi/runtimes/active/0"
        )

        return runCatching {
            val columns = arrayOf(
                "package_name",
                "native_lib_dir",
                "so_filename",
                "has_functions"
            )
            val cursor = context.contentResolver.query(uri, columns, null, null, null)
                ?: return@runCatching "provider found (${provider.packageName}); query returned null"
            cursor.use {
                if (!it.moveToFirst()) {
                    return@use "provider found (${provider.packageName}); no active runtime selected for $abi"
                }
                val packageColumn = it.getColumnIndexOrThrow("package_name")
                val libraryDirectoryColumn = it.getColumnIndexOrThrow("native_lib_dir")
                val soFilenameColumn = it.getColumnIndexOrThrow("so_filename")
                val runtimePackage = it.getString(packageColumn)
                val libraryDirectory = it.getString(libraryDirectoryColumn)
                val libraryName = it.getString(soFilenameColumn)
                val selected = runtimePackage == context.packageName
                "$label selected ${if (selected) "EndXR" else runtimePackage}; " +
                    "library=${File(libraryDirectory, libraryName).absolutePath}; " +
                    "exists=${File(libraryDirectory, libraryName).isFile}"
            }
        }.getOrElse {
            "provider found (${provider.packageName}); query failed: ${it.message ?: it.javaClass.simpleName}"
        }
    }
}
