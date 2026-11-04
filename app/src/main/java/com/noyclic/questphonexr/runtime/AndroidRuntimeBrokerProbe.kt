package com.noyclic.questphonexr.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.Bundle
import java.io.File

data class RuntimeBrokerProbeReport(
    val serviceCount: Int,
    val ownRuntimeServiceFound: Boolean,
    val ownRuntimeServiceExported: Boolean,
    val servicePackage: String?,
    val serviceName: String?,
    val serviceLabel: String?,
    val serviceIconDeclared: Boolean,
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
        serviceLabel?.let { appendLine("Service display name: $it") }
        appendLine("Service icon declared: ${if (serviceIconDeclared) "YES" else "NO"}")
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
    private const val OPENXR_MAJOR_VERSION = 1

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
            serviceLabel = ownService?.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() },
            serviceIconDeclared = ownService?.icon?.let { it != 0 } == true,
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

    private fun runtimeProcessAbi(): String? {
        val processAbis = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS
        } else {
            Build.SUPPORTED_32_BIT_ABIS
        }
        return processAbis.firstOrNull() ?: Build.SUPPORTED_ABIS.firstOrNull()
    }

    private fun queryBroker(context: Context, authority: String, label: String): String {
        val provider = runCatching {
            context.packageManager.resolveContentProvider(authority, 0)
        }.getOrNull() ?: return "provider not found or not visible"

        val abi = runtimeProcessAbi()
            ?: return "provider found (${provider.packageName}), but process ABI is unknown"
        val activeUri = Uri.parse(
            AndroidRuntimeBrokerContract.activeRuntimeUri(
                authority = authority,
                majorVersion = OPENXR_MAJOR_VERSION,
                abi = abi
            )
        )

        return runCatching {
            val columns = arrayOf(
                "package_name",
                "native_lib_dir",
                "so_filename",
                "has_functions"
            )
            val cursor = context.contentResolver.query(activeUri, columns, null, null, null)
                ?: return@runCatching "provider found (${provider.packageName}); active-runtime query returned null"
            cursor.use {
                if (!it.moveToFirst()) {
                    return@use "provider found (${provider.packageName}); no active runtime selected for $abi"
                }

                val candidates = mutableListOf<String>()
                do {
                    val runtimePackage = it.stringOrNull("package_name")
                    val libraryDirectory = it.stringOrNull("native_lib_dir")
                    val libraryName = it.stringOrNull("so_filename")
                    val hasFunctionsColumn = it.getColumnIndex("has_functions")
                    val hasFunctions = hasFunctionsColumn >= 0 &&
                        !it.isNull(hasFunctionsColumn) && it.getInt(hasFunctionsColumn) != 0

                    val libraryFile = if (!libraryDirectory.isNullOrBlank() && !libraryName.isNullOrBlank()) {
                        File(libraryDirectory, libraryName)
                    } else {
                        null
                    }
                    val functionStatus = when {
                        hasFunctionsColumn < 0 -> "has_functions column missing"
                        !hasFunctions -> "function remapping not required"
                        runtimePackage.isNullOrBlank() -> "cannot query mappings: package_name missing"
                        else -> queryFunctionMappings(context, authority, abi, runtimePackage)
                    }

                    candidates += "package=${runtimePackage ?: "missing"}; " +
                        "library=${libraryFile?.absolutePath ?: "missing"}; " +
                        "exists=${libraryFile?.isFile == true}; " +
                        "has_functions=${if (hasFunctionsColumn < 0) "unknown" else hasFunctions}; " +
                        "function_mappings=$functionStatus"
                } while (it.moveToNext())

                "$label active runtime query=$activeUri; ABI=$abi; " +
                    candidates.joinToString(separator = " || ")
            }
        }.getOrElse {
            "provider found (${provider.packageName}); query failed: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun queryFunctionMappings(
        context: Context,
        authority: String,
        abi: String,
        runtimePackage: String
    ): String {
        val uri = Uri.parse(
            AndroidRuntimeBrokerContract.functionMappingsUri(
                authority = authority,
                majorVersion = OPENXR_MAJOR_VERSION,
                abi = abi,
                packageName = runtimePackage
            )
        )
        val cursor = context.contentResolver.query(
            uri,
            arrayOf("function_name", "symbol_name"),
            null,
            null,
            null
        ) ?: return "query returned null"

        return cursor.use {
            if (!it.moveToFirst()) return@use "query succeeded but returned no mappings"
            var count = 0
            val examples = mutableListOf<String>()
            do {
                count += 1
                if (examples.size < 3) {
                    val functionName = it.stringOrNull("function_name")
                    val symbolName = it.stringOrNull("symbol_name")
                    if (!functionName.isNullOrBlank()) {
                        examples += if (symbolName.isNullOrBlank()) functionName
                        else "$functionName->$symbolName"
                    }
                }
            } while (it.moveToNext())
            "$count mapping(s)" + examples.takeIf { names -> names.isNotEmpty() }
                ?.joinToString(prefix = " [", postfix = "]").orEmpty()
        }
    }

    private fun android.database.Cursor.stringOrNull(columnName: String): String? {
        val index = getColumnIndex(columnName)
        return if (index < 0 || isNull(index)) null else getString(index)
    }
}
