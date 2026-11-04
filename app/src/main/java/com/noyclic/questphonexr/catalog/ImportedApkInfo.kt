package com.noyclic.questphonexr.catalog

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

data class ImportedApkInfo(
    val id: String,
    val fileName: String,
    val originalName: String,
    val displayName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val nativeAbis: List<String>,
    val requiredFeatures: List<String>,
    val vrMetadataKeys: List<String>,
    val sizeBytes: Long,
    val importedAtEpochMs: Long,
    val requestedPermissions: List<String> = emptyList(),
    val activityNames: List<String> = emptyList(),
    val serviceNames: List<String> = emptyList(),
    val providerNames: List<String> = emptyList(),
    val sha256: String = "",
    val vrMetadataValues: Map<String, String> = emptyMap()
)

/** Streaming APK fingerprint. The file is never loaded fully into memory. */
internal object ApkArchiveFingerprint {
    fun sha256(file: File): String {
        require(file.isFile) { "APK-файл недоступен для вычисления SHA-256." }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}

/** Reads package metadata from an APK without installing or executing it. */
internal object ApkArchiveInspector {
    fun inspect(context: Context, file: File, id: String, originalName: String): ImportedApkInfo {
        require(file.isFile && file.length() > 0L) { "APK-файл пуст или недоступен." }
        val packageInfo = readPackageInfo(context.packageManager, file)
            ?: error("Android не смог прочитать манифест этого APK.")
        val appInfo = packageInfo.applicationInfo
            ?: error("В APK не найдено описание приложения.")

        appInfo.sourceDir = file.absolutePath
        appInfo.publicSourceDir = file.absolutePath
        val packageName = packageInfo.packageName
        val label = runCatching {
            context.packageManager.getApplicationLabel(appInfo).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName

        val requiredFeatures = packageInfo.reqFeatures
            ?.mapNotNull { it.name?.takeIf(String::isNotBlank) }
            ?.distinct()
            ?.sorted()
            .orEmpty()
        val vrMetadataKeys = appInfo.metaData?.keySet()
            ?.filter { key ->
                val normalized = key.lowercase()
                normalized.contains("oculus") ||
                    normalized.contains("quest") ||
                    normalized.contains("meta") ||
                    normalized.contains("samsung.android.vr") ||
                    normalized.contains("samsung.android.hmt") ||
                    normalized.contains("gearvr") ||
                    normalized.contains("gear_vr")
            }
            ?.sorted()
            .orEmpty()
        val vrMetadataValues = vrMetadataKeys.associateWith { key ->
            appInfo.metaData?.get(key)?.toString()?.take(512).orEmpty()
        }
        val requestedPermissions = packageInfo.requestedPermissions
            ?.filter(String::isNotBlank)?.distinct()?.sorted().orEmpty()
        val activityNames = packageInfo.activities
            ?.mapNotNull { it.name?.takeIf(String::isNotBlank) }?.distinct()?.sorted().orEmpty()
        val serviceNames = packageInfo.services
            ?.mapNotNull { it.name?.takeIf(String::isNotBlank) }?.distinct()?.sorted().orEmpty()
        val providerNames = packageInfo.providers
            ?.mapNotNull { it.name?.takeIf(String::isNotBlank) }?.distinct()?.sorted().orEmpty()

        return ImportedApkInfo(
            id = id,
            fileName = file.name,
            originalName = originalName,
            displayName = label,
            packageName = packageName,
            versionName = packageInfo.versionName.orEmpty(),
            versionCode = packageInfo.longVersionCode,
            minSdk = appInfo.minSdkVersion,
            targetSdk = appInfo.targetSdkVersion,
            nativeAbis = readNativeAbis(file),
            requiredFeatures = requiredFeatures,
            vrMetadataKeys = vrMetadataKeys,
            sizeBytes = file.length(),
            requestedPermissions = requestedPermissions,
            activityNames = activityNames,
            serviceNames = serviceNames,
            providerNames = providerNames,
            sha256 = ApkArchiveFingerprint.sha256(file),
            vrMetadataValues = vrMetadataValues,
            importedAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun readPackageInfo(packageManager: PackageManager, file: File): PackageInfo? {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_PERMISSIONS or
            PackageManager.GET_META_DATA or
            PackageManager.GET_CONFIGURATIONS

        return if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }
    }

    private fun readNativeAbis(file: File): List<String> {
        return runCatching {
            ZipFile(file).use { archive ->
                val abis = linkedSetOf<String>()
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (name.startsWith("lib/")) {
                        val parts = name.split('/')
                        if (parts.size >= 3 && parts[1].isNotBlank() && parts.last().endsWith(".so")) {
                            abis += parts[1]
                        }
                    }
                }
                abis.sorted()
            }
        }.getOrDefault(emptyList())
    }
}

/** Small local index; APK bytes stay in the app's private files directory. */
class ImportedApkStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val importedDirectory = File(appContext.filesDir, DIRECTORY_NAME)

    fun getAll(): List<ImportedApkInfo> {
        val raw = preferences.getString(KEY_ENTRIES, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val entry = array.optJSONObject(index) ?: continue
                    add(entry.toInfo())
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(info: ImportedApkInfo) {
        val existing = getAll()
        existing.filter {
            it.id == info.id || it.packageName == info.packageName
        }.forEach { old ->
            File(importedDirectory, old.fileName).delete()
        }
        persist(existing.filterNot {
            it.id == info.id || it.packageName == info.packageName
        } + info)
    }

    fun remove(id: String): ImportedApkInfo? {
        val existing = getAll()
        val removed = existing.firstOrNull { it.id == id } ?: return null
        persist(existing.filterNot { it.id == id })
        File(importedDirectory, removed.fileName).delete()
        return removed
    }

    private fun persist(entries: List<ImportedApkInfo>) {
        val json = JSONArray()
        entries.forEach { info ->
            json.put(JSONObject().apply {
                put("id", info.id)
                put("fileName", info.fileName)
                put("originalName", info.originalName)
                put("displayName", info.displayName)
                put("packageName", info.packageName)
                put("versionName", info.versionName)
                put("versionCode", info.versionCode)
                put("minSdk", info.minSdk)
                put("targetSdk", info.targetSdk)
                put("nativeAbis", JSONArray(info.nativeAbis))
                put("requiredFeatures", JSONArray(info.requiredFeatures))
                put("vrMetadataKeys", JSONArray(info.vrMetadataKeys))
                put("requestedPermissions", JSONArray(info.requestedPermissions))
                put("activityNames", JSONArray(info.activityNames))
                put("serviceNames", JSONArray(info.serviceNames))
                put("providerNames", JSONArray(info.providerNames))
                put("sha256", info.sha256)
                put("vrMetadataValues", JSONObject().apply {
                    info.vrMetadataValues.forEach { (key, value) -> put(key, value) }
                })
                put("sizeBytes", info.sizeBytes)
                put("importedAtEpochMs", info.importedAtEpochMs)
            })
        }
        preferences.edit().putString(KEY_ENTRIES, json.toString()).apply()
    }

    private fun JSONObject.toInfo() = ImportedApkInfo(
        id = optString("id"),
        fileName = optString("fileName"),
        originalName = optString("originalName"),
        displayName = optString("displayName"),
        packageName = optString("packageName"),
        versionName = optString("versionName"),
        versionCode = optLong("versionCode"),
        minSdk = optInt("minSdk"),
        targetSdk = optInt("targetSdk"),
        nativeAbis = optStringList("nativeAbis"),
        requiredFeatures = optStringList("requiredFeatures"),
        vrMetadataKeys = optStringList("vrMetadataKeys"),
        sizeBytes = optLong("sizeBytes"),
        requestedPermissions = optStringList("requestedPermissions"),
        activityNames = optStringList("activityNames"),
        serviceNames = optStringList("serviceNames"),
        providerNames = optStringList("providerNames"),
        sha256 = optString("sha256"),
        vrMetadataValues = optJSONObject("vrMetadataValues")?.let { values ->
            buildMap {
                val keys = values.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, values.optString(key).take(512))
                }
            }
        }.orEmpty(),
        importedAtEpochMs = optLong("importedAtEpochMs")
    )

    private fun JSONObject.optStringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "questphone_imported_apks"
        const val KEY_ENTRIES = "entries"
        const val DIRECTORY_NAME = "imported-apks"
    }
}

/** Manifest-only checks. A clean result is not a runtime compatibility certificate. */
object ApkCompatibilityAnalyzer {
    fun warnings(context: Context, info: ImportedApkInfo): List<String> {
        val missingFeatures = info.requiredFeatures.filterNot {
            context.packageManager.hasSystemFeature(it)
        }.toSet()
        return warnings(
            info = info,
            deviceApiLevel = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            missingRequiredFeatures = missingFeatures
        )
    }

    internal fun warnings(
        info: ImportedApkInfo,
        deviceApiLevel: Int,
        supportedAbis: Collection<String>,
        missingRequiredFeatures: Set<String>
    ): List<String> {
        val result = mutableListOf<String>()
        if (info.minSdk > deviceApiLevel) {
            result += "Минимальная версия Android у APK — API ${info.minSdk}, у телефона — API $deviceApiLevel."
        }
        if (info.nativeAbis.isNotEmpty() && info.nativeAbis.none { it in supportedAbis }) {
            result += "Нет совпадающей ABI: APK содержит ${info.nativeAbis.joinToString()}, телефон поддерживает ${supportedAbis.joinToString()}."
        }
        if (missingRequiredFeatures.isNotEmpty()) {
            result += "Не найдены обязательные функции устройства: ${missingRequiredFeatures.sorted().joinToString()}."
        }
        val likelyQuestPackage = info.packageName.contains("oculus", ignoreCase = true) ||
            info.packageName.contains("quest", ignoreCase = true) ||
            info.vrMetadataKeys.isNotEmpty() ||
            info.requiredFeatures.any { it.contains("vr", ignoreCase = true) }
        if (likelyQuestPackage) {
            result += "Есть признаки Quest/Meta VR-пакета. Обычный Android и этот импортёр не предоставляют Quest runtime."
        }
        if (result.isEmpty()) {
            result += "Явных блокирующих признаков в манифесте не найдено. Это не подтверждает запуск или VR-совместимость."
        }
        return result
    }
}
