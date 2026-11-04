package com.noyclic.questphonexr.catalog

import java.io.File
import java.util.zip.ZipFile
import com.noyclic.questphonexr.gvr.EndGvrClassification
import com.noyclic.questphonexr.gvr.EndGvrCompatibility
import com.noyclic.questphonexr.runtime.EndXrRuntimeReadiness

enum class ApkLaunchRoute(val label: String) {
    DEVICE_REQUIREMENTS_BLOCKED("Телефон не соответствует требованиям APK"),
    QUEST_RUNTIME_REQUIRED("Требуется Meta Quest Runtime — напрямую не запускается"),
    GEAR_VR_LEGACY_CANDIDATE("Обнаружены Gear VR признаки — нужна проверка через EndGVR"),
    LEGACY_OCULUS_MOBILE_CANDIDATE("Обнаружен старый Oculus Mobile SDK — нужна проверка legacy API"),
    OPENXR_RUNTIME_CANDIDATE("Обнаружен OpenXR — нужен активный совместимый Runtime"),
    XR_SOURCE_ADAPTATION_REQUIRED("Нужен XR-порт или совместимый адаптер"),
    ANDROID_APP_CANDIDATE("Кандидат на обычный Android-запуск")
}

data class ApkStaticAudit(
    val nativeLibraries: List<String> = emptyList(),
    val sdkRuntimeMarkers: List<String> = emptyList(),
    val manifestReferences: List<String> = emptyList(),
    val componentReferences: List<String> = emptyList(),
    val abiSummary: String = "ABI не проверен."
)

data class ApkRuntimeAssessment(
    val route: ApkLaunchRoute,
    val engine: String,
    val xrTechnologies: List<String>,
    val details: List<String>,
    val audit: ApkStaticAudit = ApkStaticAudit()
)

/**
 * Conservative APK triage for a phone-hosted XR runtime.
 *
 * This class inspects archive filenames and already parsed manifest metadata only.
 * It never executes APK code and never labels an APK as VR-compatible based on heuristics.
 */
object ApkRuntimeCompatibility {
    fun inspect(
        info: ImportedApkInfo,
        archive: File,
        deviceApiLevel: Int,
        supportedAbis: Collection<String>,
        missingRequiredFeatures: Set<String>
    ): ApkRuntimeAssessment {
        val entries = if (archive.isFile) runCatching {
            ZipFile(archive).use { zip ->
                buildList {
                    val enumeration = zip.entries()
                    while (enumeration.hasMoreElements()) add(enumeration.nextElement().name)
                }
            }
        }.getOrDefault(emptyList()) else emptyList()

        return assess(info, entries, deviceApiLevel, supportedAbis, missingRequiredFeatures)
    }

    internal fun assess(
        info: ImportedApkInfo,
        archiveEntries: Collection<String>,
        deviceApiLevel: Int,
        supportedAbis: Collection<String>,
        missingRequiredFeatures: Set<String>
    ): ApkRuntimeAssessment {
        val lowerEntries = archiveEntries.map { it.lowercase() }
        val nativeLibraries = lowerEntries.filter { it.startsWith("lib/") && it.endsWith(".so") }
        val endGvrAssessment = EndGvrCompatibility.inspect(
            packageName = info.packageName,
            metadataKeys = info.vrMetadataKeys,
            requiredFeatures = info.requiredFeatures,
            archiveEntries = lowerEntries,
            requestedPermissions = info.requestedPermissions,
            activityNames = info.activityNames,
            serviceNames = info.serviceNames,
            providerNames = info.providerNames
        )
        val engine = when {
            nativeLibraries.any { it.endsWith("/libunity.so") } ||
                lowerEntries.any { it.startsWith("assets/bin/data/") } -> "Unity"
            nativeLibraries.any { it.endsWith("/libue4.so") || it.endsWith("/libunreal.so") } ||
                lowerEntries.any { it.contains("ue4commandline") } -> "Unreal Engine"
            nativeLibraries.any { it.contains("godot") } ||
                lowerEntries.any { it.endsWith(".pck") } -> "Godot"
            nativeLibraries.isNotEmpty() -> "Собственный / неизвестный native engine"
            else -> "Не определён"
        }

        val metadata = info.vrMetadataKeys.map { it.lowercase() }
        val questSignals = buildList {
            if (
                info.packageName.contains("oculus", ignoreCase = true) ||
                info.packageName.contains("quest", ignoreCase = true) ||
                info.packageName.contains("meta", ignoreCase = true)
            ) add("Quest/Meta package id")
            if (metadata.any { it.contains("oculus") || it.contains("quest") || it.contains("meta") }) {
                add("Quest/Meta manifest metadata")
            }
            if (nativeLibraries.any {
                    it.contains("ovrplugin") || it.contains("ovrplatformloader") ||
                        it.contains("oculus")
                }
            ) add("Oculus/Meta native library")
            if (info.requiredFeatures.any {
                    it.contains("oculus", ignoreCase = true) ||
                        it.contains("quest", ignoreCase = true)
                }
            ) add("Quest-specific required feature")
        }.distinct()

        val xrTechnologies = buildList {
            if (nativeLibraries.any { it.contains("openxr") } ||
                lowerEntries.any { it.contains("openxr") }
            ) add("OpenXR")
            if (questSignals.isNotEmpty()) add("Meta/Oculus XR")
            when (endGvrAssessment.classification) {
                EndGvrClassification.GEAR_VR_MARKED -> add("Gear VR (legacy)")
                EndGvrClassification.LEGACY_OCULUS_MOBILE_SDK -> add("Legacy Oculus Mobile SDK")
                EndGvrClassification.NO_GEAR_VR_EVIDENCE -> Unit
            }
            if (nativeLibraries.any { it.contains("openvr") || it.contains("steamvr") } ||
                lowerEntries.any { it.contains("steamvr") }
            ) add("OpenVR/SteamVR")
            if (xrTechnologiesEmptyFallback(info, lowerEntries)) add("Android VR feature declaration")
        }.distinct()

        val apkAbis = info.nativeAbis.distinct().sorted()
        val deviceAbis = supportedAbis.distinct().toList()
        val matchingAbis = apkAbis.filter { it in deviceAbis }
        val abiSummary = when {
            apkAbis.isEmpty() ->
                "Нативные ABI в APK не найдены; возможно, это Java/Kotlin APK или неполный split-набор."
            matchingAbis.isNotEmpty() ->
                "APK: ${apkAbis.joinToString()}. Телефон: ${deviceAbis.joinToString()}. Совпадение: ${matchingAbis.joinToString()}."
            else ->
                "APK: ${apkAbis.joinToString()}. Телефон: ${deviceAbis.joinToString()}. Совпадений нет."
        }

        val manifestReferences = (
            info.packageName.let(::listOf) +
                info.vrMetadataKeys +
                info.requiredFeatures +
                info.requestedPermissions
            ).filter(::looksLikeXrReference).distinct().sorted()
        val componentReferences = (
            info.activityNames + info.serviceNames + info.providerNames
            ).filter(::looksLikeXrReference).distinct().sorted()
        val packagedLibraries = nativeLibraries
            .map { it.substringAfterLast('/') }
            .distinct()
            .sorted()
        val sdkRuntimeMarkers = (
            packagedLibraries.filter(::looksLikeSdkRuntimeLibrary) +
                manifestReferences.filter(::looksLikeSdkRuntimeReference) +
                componentReferences.filter(::looksLikeSdkRuntimeReference)
            ).distinct().sorted()
        val audit = ApkStaticAudit(
            nativeLibraries = packagedLibraries,
            sdkRuntimeMarkers = sdkRuntimeMarkers,
            manifestReferences = manifestReferences,
            componentReferences = componentReferences,
            abiSummary = abiSummary
        )

        val blockers = buildList {
            if (info.minSdk > deviceApiLevel) {
                add("Требуется Android API ${info.minSdk}, на телефоне API $deviceApiLevel.")
            }
            if (info.nativeAbis.isNotEmpty() && info.nativeAbis.none { it in supportedAbis }) {
                add("ABI APK (${info.nativeAbis.joinToString()}) не совпадает с ABI телефона (${supportedAbis.joinToString()}).")
            }
            if (missingRequiredFeatures.isNotEmpty()) {
                add("Отсутствуют обязательные функции: ${missingRequiredFeatures.sorted().joinToString()}.")
            }
        }

        val route = when {
            blockers.isNotEmpty() -> ApkLaunchRoute.DEVICE_REQUIREMENTS_BLOCKED
            endGvrAssessment.classification == EndGvrClassification.GEAR_VR_MARKED ->
                ApkLaunchRoute.GEAR_VR_LEGACY_CANDIDATE
            questSignals.isNotEmpty() -> ApkLaunchRoute.QUEST_RUNTIME_REQUIRED
            endGvrAssessment.classification == EndGvrClassification.LEGACY_OCULUS_MOBILE_SDK ->
                ApkLaunchRoute.LEGACY_OCULUS_MOBILE_CANDIDATE
            "OpenXR" in xrTechnologies -> ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE
            xrTechnologies.isNotEmpty() -> ApkLaunchRoute.XR_SOURCE_ADAPTATION_REQUIRED
            else -> ApkLaunchRoute.ANDROID_APP_CANDIDATE
        }

        val details = buildList {
            addAll(blockers)
            when (route) {
                ApkLaunchRoute.DEVICE_REQUIREMENTS_BLOCKED ->
                    add("Сначала нужно устранить ограничения устройства; установка или запуск могут завершиться ошибкой.")
                ApkLaunchRoute.QUEST_RUNTIME_REQUIRED ->
                    add("APK использует признаки Quest/Meta. На обычном Android нет системного Quest Runtime и Meta XR-сервисов; нужен отдельный порт или полноценная совместимая среда.")
                ApkLaunchRoute.GEAR_VR_LEGACY_CANDIDATE ->
                    add("Обнаружены явные маркеры Samsung Gear VR. EndGVR пока выполняет только статическую классификацию: legacy runtime, сервисы и игровой запуск ещё не реализованы и не подтверждены.")
                ApkLaunchRoute.LEGACY_OCULUS_MOBILE_CANDIDATE ->
                    add("Найдена libvrapi.so — признак legacy Oculus Mobile SDK. Это само по себе не доказывает, что приложение предназначено именно для Gear VR; нужно проверить манифест, сервисы и требования устройства.")
                ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE ->
                    add("Обнаружены OpenXR-маркеры. Нужен активный Android OpenXR Runtime с поддержкой расширений и графического API. Один OpenXR Loader в QuestPhoneXR не является Runtime.")
                ApkLaunchRoute.XR_SOURCE_ADAPTATION_REQUIRED ->
                    add("Обнаружены XR-компоненты. Для телефона потребуется адаптация графики, трекинга и ввода под runtime QuestPhoneXR.")
                ApkLaunchRoute.ANDROID_APP_CANDIDATE ->
                    add("По доступным признакам это кандидат на обычную Android-установку. Стерео-режим и управление VR этим анализом не подтверждаются.")
            }
            if (engine != "Не определён") add("Обнаруженный движок: $engine.")
            if (xrTechnologies.isNotEmpty()) add("XR-признаки: ${xrTechnologies.joinToString()}.")
            addAll(EndXrRuntimeReadiness.warningsFor(xrTechnologies))
            if (questSignals.isNotEmpty()) add("Quest-признаки: ${questSignals.joinToString()}.")
            if (endGvrAssessment.gearVrMarkers.isNotEmpty()) {
                add("EndGVR Gear VR маркеры: ${endGvrAssessment.gearVrMarkers.joinToString()}.")
            }
            if (endGvrAssessment.legacyOculusMarkers.isNotEmpty()) {
                add("EndGVR legacy-маркеры: ${endGvrAssessment.legacyOculusMarkers.joinToString()}.")
            }
        }

        return ApkRuntimeAssessment(route, engine, xrTechnologies, details, audit)
    }

    private fun looksLikeXrReference(value: String): Boolean {
        val normalized = value.lowercase()
        return listOf(
            "samsung.android.vr", "samsung.android.hmt", "gearvr", "gear_vr",
            "gear-vr", "oculus", "openxr", "openvr", "steamvr", "quest", "meta"
        ).any { marker -> normalized.contains(marker) }
    }

    private fun looksLikeSdkRuntimeLibrary(name: String): Boolean {
        val normalized = name.lowercase()
        return listOf(
            "vrapi", "ovr", "oculus", "openxr", "openvr", "steamvr"
        ).any { marker -> normalized.contains(marker) }
    }

    private fun looksLikeSdkRuntimeReference(value: String): Boolean {
        val normalized = value.lowercase()
        return listOf(
            "vrapi", "ovr", "oculus", "samsung.android.hmt",
            "samsung.android.vr", "gearvr", "gear_vr", "gear-vr",
            "openxr", "openvr", "steamvr"
        ).any { marker -> normalized.contains(marker) }
    }

    private fun xrTechnologiesEmptyFallback(
        info: ImportedApkInfo,
        lowerEntries: List<String>
    ): Boolean = info.requiredFeatures.any { it.contains("vr", ignoreCase = true) } ||
        lowerEntries.any { it.contains("vrmanifest") }
}
