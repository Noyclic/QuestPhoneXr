package com.noyclic.xrclientprobe

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
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
    val inputBridgeStatus: String,
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
        appendLine("EndXR input IPC: $inputBridgeStatus")
        queryError?.let { appendLine("PackageManager query error: $it") }
        appendLine()
        appendLine("This is a separate Android package, not QuestPhoneXR itself.")
        append("It verifies service discoverability, the read-only EndXR input IPC endpoint and broker responses; it does not prove a live background input producer.")
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
    private const val INPUT_AUTHORITY = "com.noyclic.questphonexr.endxrinput"

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
            inputBridgeStatus = queryEndXrInputBridge(context),
            queryError = query.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        )
    }

    private fun queryEndXrInputBridge(context: Context): String {
        val provider = runCatching {
            context.packageManager.resolveContentProvider(INPUT_AUTHORITY, 0)
        }.getOrNull() ?: return "provider not visible"

        val uri = Uri.parse(
            "content://$INPUT_AUTHORITY/controller_state"
        )
        return runCatching {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
                ?: return@runCatching "provider found (${provider.packageName}); query returned null"
            cursor.use {
                if (!it.moveToFirst()) {
                    return@use "provider_query=EMPTY; provider=${provider.packageName}"
                }
                val expected = listOf(
                    "sequence", "updated_at_elapsed_realtime_nanos", "stale",
                    "left_active", "left_trigger", "left_grip", "left_stick_x",
                    "left_stick_y", "left_buttons", "right_active", "right_trigger",
                    "right_grip", "right_stick_x", "right_stick_y", "right_buttons",
                    "head_orientation_active", "head_orientation_stale",
                    "head_orientation_updated_at_elapsed_realtime_nanos",
                    "head_orientation_x", "head_orientation_y",
                    "head_orientation_z", "head_orientation_w",
                    "hand_pose_stale", "hand_pose_updated_at_elapsed_realtime_nanos",
                    "left_pose_active", "left_pose_x", "left_pose_y", "left_pose_z",
                    "left_pose_qx", "left_pose_qy", "left_pose_qz", "left_pose_qw",
                    "right_pose_active", "right_pose_x", "right_pose_y", "right_pose_z",
                    "right_pose_qx", "right_pose_qy", "right_pose_qz", "right_pose_qw"
                )
                val missing = expected.filter { name -> it.getColumnIndex(name) < 0 }
                if (missing.isNotEmpty()) {
                    return@use "provider_query=FAIL; missing_columns=${missing.joinToString()}"
                }
                val stale = it.getInt(it.getColumnIndexOrThrow("stale")) != 0
                val headActive = it.getInt(it.getColumnIndexOrThrow("head_orientation_active")) != 0
                val headStale = it.getInt(it.getColumnIndexOrThrow("head_orientation_stale")) != 0
                val handPoseStale = it.getInt(it.getColumnIndexOrThrow("hand_pose_stale")) != 0
                val leftPoseActive = it.getInt(it.getColumnIndexOrThrow("left_pose_active")) != 0
                val rightPoseActive = it.getInt(it.getColumnIndexOrThrow("right_pose_active")) != 0
                val sequence = it.getLong(it.getColumnIndexOrThrow("sequence"))
                "provider_query=PASS; provider=${provider.packageName}; " +
                    "sequence=$sequence; stale=$stale; head_active=$headActive; " +
                    "head_stale=$headStale; hand_pose_stale=$handPoseStale; " +
                    "left_pose=$leftPoseActive; right_pose=$rightPoseActive; columns=${expected.size}"
            }
        }.getOrElse {
            "provider_query=FAIL; ${it.message ?: it.javaClass.simpleName}"
        }
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

        val processAbis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        val abi = processAbis.firstOrNull() ?: Build.SUPPORTED_ABIS.firstOrNull()
            ?: return "provider found (${provider.packageName}); process ABI unknown"
        val uri = Uri.parse("content://$authority/openxr/1/abi/$abi/runtimes/active")

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
                val hasFunctions = it.getInt(it.getColumnIndexOrThrow("has_functions")) != 0
                val functionStatus = if (hasFunctions) {
                    queryFunctionMappings(context, authority, abi, packageName)
                } else {
                    "not required"
                }
                "active package=$packageName; library=$library; exists=${library.isFile}; " +
                    "has_functions=$hasFunctions; function_mappings=$functionStatus"
            }
        }.getOrElse {
            "provider found (${provider.packageName}); query failed: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun queryFunctionMappings(
        context: Context,
        authority: String,
        abi: String,
        packageName: String
    ): String {
        val uri = Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath("openxr")
            .appendPath("1")
            .appendPath("abi")
            .appendPath(abi)
            .appendPath("runtimes")
            .appendPath(packageName)
            .appendPath("functions")
            .build()
        val cursor = context.contentResolver.query(
            uri,
            arrayOf("function_name", "symbol_name"),
            null,
            null,
            null
        ) ?: return "query returned null"
        return cursor.use {
            if (!it.moveToFirst()) return@use "no mappings returned"
            var count = 0
            do { count += 1 } while (it.moveToNext())
            "$count mapping(s)"
        }
    }
}
