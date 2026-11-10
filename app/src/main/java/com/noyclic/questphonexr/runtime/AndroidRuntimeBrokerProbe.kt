package com.noyclic.questphonexr.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.Bundle
import java.io.File

internal data class BrokerRuntimeCandidate(
    val packageName: String?,
    val libraryPath: String?,
    val soFilename: String?,
    val libraryExists: Boolean,
    val hasFunctions: Boolean?,
    val functionMappingsAvailable: Boolean,
) {
    // This is a conservative filesystem/metadata prediction, not proof that dlopen succeeds.
    val canAttemptLoad: Boolean
        get() = libraryExists && (hasFunctions != true || functionMappingsAvailable)
}

internal data class BrokerQuerySnapshot(
    val providerFound: Boolean,
    val queryCompleted: Boolean,
    val hasRows: Boolean,
    val candidates: List<BrokerRuntimeCandidate>,
    val status: String,
)

internal data class LoaderBrokerSelection(
    val authority: String?,
    val candidate: BrokerRuntimeCandidate?,
)

/**
 * Mirrors Khronos Android loader precedence:
 * use the installable broker when its active-runtime query has rows; consult the
 * system broker only when the installable broker is unavailable or empty.
 */
internal fun selectLoaderBrokerRuntime(
    installable: BrokerQuerySnapshot,
    system: BrokerQuerySnapshot,
): LoaderBrokerSelection {
    val selected = if (installable.queryCompleted && installable.hasRows) {
        INSTALLABLE_AUTHORITY_FOR_SELECTION
    } else {
        SYSTEM_AUTHORITY_FOR_SELECTION
    }
    val snapshot = if (selected == INSTALLABLE_AUTHORITY_FOR_SELECTION) installable else system
    val candidate = if (snapshot.queryCompleted && snapshot.hasRows) {
        snapshot.candidates.firstOrNull { it.canAttemptLoad }
    } else {
        null
    }
    return LoaderBrokerSelection(selected, candidate)
}

private const val INSTALLABLE_AUTHORITY_FOR_SELECTION = "org.khronos.openxr.runtime_broker"
private const val SYSTEM_AUTHORITY_FOR_SELECTION = "org.khronos.openxr.system_runtime_broker"

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
    val discoveryError: String?,
    val loaderAbi: String?,
    val loaderSelectedBroker: String?,
    val loaderSelectedRuntimePackage: String?,
    val loaderSelectedRuntimeLibrary: String?,
    val loaderSelectedRuntimeLibraryExists: Boolean,
    val endXrIsLikelySelected: Boolean,
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
        appendLine("Loader ABI checked: ${loaderAbi ?: "unknown"}")
        appendLine("Loader-selected Broker: ${loaderSelectedBroker ?: "none"}")
        appendLine("Loader's first existing runtime candidate: ${loaderSelectedRuntimePackage ?: "none"}")
        loaderSelectedRuntimeLibrary?.let { appendLine("Loader candidate library: $it") }
        appendLine("Loader candidate library exists: ${if (loaderSelectedRuntimeLibraryExists) "YES" else "NO"}")
        appendLine("EndXR is the likely loader candidate: ${if (endXrIsLikelySelected) "YES" else "NO"}")
        discoveryError?.let { appendLine("Service discovery error: $it") }
        appendLine()
        appendLine("The broker choice above follows Khronos installable-first, system-fallback precedence.")
        append("A file/metadata check is not proof of dlopen, xrCreateInstance, or game rendering; use the separate client probe for that.")
    }

    companion object {
        const val ACTION_NAME = "org.khronos.openxr.OpenXRRuntimeService"
    }
}

/**
 * Checks the exported runtime marker and simulates the official loader's Broker
 * precedence for the current process ABI. It never changes selection or dlopens
 * a runtime; the separate Khronos-loader client is the authoritative load test.
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
        val abi = runtimeProcessAbi()

        val installable = queryBroker(
            context = context,
            authority = INSTALLABLE_AUTHORITY,
            label = "installable RuntimeBroker",
            expectedPackage = context.packageName,
            expectedSoFilename = soFilename,
            abi = abi
        )
        val system = queryBroker(
            context = context,
            authority = SYSTEM_AUTHORITY,
            label = "system RuntimeBroker",
            expectedPackage = context.packageName,
            expectedSoFilename = soFilename,
            abi = abi
        )
        val loaderSelection = selectLoaderBrokerRuntime(installable, system)
        val selectedCandidate = loaderSelection.candidate
        val endXrLikelySelected = ownService != null &&
            majorVersion == OPENXR_MAJOR_VERSION &&
            !soFilename.isNullOrBlank() &&
            selectedCandidate?.packageName == context.packageName &&
            selectedCandidate.soFilename == soFilename &&
            selectedCandidate.libraryExists

        return RuntimeBrokerProbeReport(
            serviceCount = matches.size,
            ownRuntimeServiceFound = ownService != null,
            ownRuntimeServiceExported = ownService?.exported == true,
            servicePackage = ownService?.packageName,
            serviceName = ownService?.name,
            serviceLabel = runCatching {
                ownService?.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() }
            }.getOrNull(),
            serviceIconDeclared = ownService?.icon?.let { it != 0 } == true,
            majorVersion = majorVersion,
            soFilename = soFilename,
            nativeLibraryPath = nativeLibraryPath,
            nativeLibraryExists = libraryExists,
            installableBrokerStatus = installable.status,
            systemBrokerStatus = system.status,
            discoveryError = query.exceptionOrNull()?.let {
                it.message ?: it.javaClass.simpleName
            },
            loaderAbi = abi,
            loaderSelectedBroker = loaderSelection.authority,
            loaderSelectedRuntimePackage = selectedCandidate?.packageName,
            loaderSelectedRuntimeLibrary = selectedCandidate?.libraryPath,
            loaderSelectedRuntimeLibraryExists = selectedCandidate?.libraryExists == true,
            endXrIsLikelySelected = endXrLikelySelected
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

    private fun queryBroker(
        context: Context,
        authority: String,
        label: String,
        expectedPackage: String,
        expectedSoFilename: String?,
        abi: String?
    ): BrokerQuerySnapshot {
        val provider = runCatching {
            context.packageManager.resolveContentProvider(authority, 0)
        }.getOrNull() ?: return BrokerQuerySnapshot(
            providerFound = false,
            queryCompleted = false,
            hasRows = false,
            candidates = emptyList(),
            status = "provider not found or not visible"
        )

        if (abi.isNullOrBlank()) return BrokerQuerySnapshot(
            providerFound = true,
            queryCompleted = false,
            hasRows = false,
            candidates = emptyList(),
            status = "provider found (${provider.packageName}), but process ABI is unknown"
        )

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
                ?: return@runCatching BrokerQuerySnapshot(
                    true, false, false, emptyList(),
                    "provider found (${provider.packageName}); active-runtime query returned null"
                )

            cursor.use {
                if (!it.moveToFirst()) return@use BrokerQuerySnapshot(
                    true, true, false, emptyList(),
                    "provider found (${provider.packageName}); no active runtime selected for $abi"
                )

                val candidates = mutableListOf<BrokerRuntimeCandidate>()
                do {
                    val runtimePackage = it.stringOrNull("package_name")
                    val libraryDirectory = it.stringOrNull("native_lib_dir")
                    val libraryName = it.stringOrNull("so_filename")
                    val hasFunctionsColumn = it.getColumnIndex("has_functions")
                    val hasFunctions = if (hasFunctionsColumn < 0 || it.isNull(hasFunctionsColumn)) {
                        null
                    } else {
                        it.getInt(hasFunctionsColumn) != 0
                    }
                    val libraryFile = if (!libraryDirectory.isNullOrBlank() && !libraryName.isNullOrBlank()) {
                        File(libraryDirectory, libraryName)
                    } else {
                        null
                    }
                    val functionResult = when {
                        hasFunctions == null -> "has_functions column missing" to false
                        !hasFunctions -> "function remapping not required" to true
                        runtimePackage.isNullOrBlank() ->
                            "cannot query mappings: package_name missing" to false
                        else -> queryFunctionMappings(context, authority, abi, runtimePackage)
                    }
                    candidates += BrokerRuntimeCandidate(
                        packageName = runtimePackage,
                        libraryPath = libraryFile?.absolutePath,
                        soFilename = libraryName,
                        libraryExists = libraryFile?.isFile == true,
                        hasFunctions = hasFunctions,
                        functionMappingsAvailable = functionResult.second
                    )
                } while (it.moveToNext())

                val candidateText = candidates.map { candidate ->
                    "package=${candidate.packageName ?: "missing"}; " +
                        "library=${candidate.libraryPath ?: "missing"}; " +
                        "exists=${candidate.libraryExists}; " +
                        "has_functions=${candidate.hasFunctions ?: "unknown"}; " +
                        "function_mappings=${when {
                            candidate.hasFunctions == null -> "unknown"
                            !candidate.hasFunctions -> "not required"
                            candidate.functionMappingsAvailable -> "available"
                            else -> "missing or unavailable"
                        }}"
                }
                BrokerQuerySnapshot(
                    providerFound = true,
                    queryCompleted = true,
                    hasRows = candidates.isNotEmpty(),
                    candidates = candidates,
                    status = "$label active runtime query=$activeUri; ABI=$abi; " +
                        candidateText.joinToString(separator = " || ")
                )
            }
        }.getOrElse {
            BrokerQuerySnapshot(
                providerFound = true,
                queryCompleted = false,
                hasRows = false,
                candidates = emptyList(),
                status = "provider found (${provider.packageName}); query failed: ${it.message ?: it.javaClass.simpleName}"
            )
        }
    }

    private fun queryFunctionMappings(
        context: Context,
        authority: String,
        abi: String,
        runtimePackage: String
    ): Pair<String, Boolean> {
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
        ) ?: return "query returned null" to false

        return cursor.use {
            if (!it.moveToFirst()) return@use "query succeeded but returned no mappings" to false
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
            ("$count mapping(s)" + examples.takeIf { names -> names.isNotEmpty() }
                ?.joinToString(prefix = " [", postfix = "]").orEmpty()) to true
        }
    }

    private fun android.database.Cursor.stringOrNull(columnName: String): String? {
        val index = getColumnIndex(columnName)
        return if (index < 0 || isNull(index)) null else getString(index)
    }
}
