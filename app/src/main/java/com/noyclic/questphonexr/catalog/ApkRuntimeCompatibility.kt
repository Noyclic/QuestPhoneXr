package com.noyclic.questphonexr.catalog

import java.io.File
import java.util.zip.ZipFile

enum class ApkLaunchRoute(val label: String) {
    DEVICE_REQUIREMENTS_BLOCKED("Телефон не соответствует требованиям APK"),
    QUEST_RUNTIME_REQUIRED("Требуется Meta Quest Runtime — напрямую не запускается"),
    OPENXR_RUNTIME_CANDIDATE("Обнаружен OpenXR — нужен активный совместимый Runtime"),
    XR_SOURCE_ADAPTATION_REQUIRED("Нужен XR-порт или совместимый адаптер"),
    ANDROID_APP_CANDIDATE("Кандидат на обычный Android-запуск")
}

data class ApkRuntimeAssessment(
    val route: ApkLaunchRoute,
    val engine: String,
    val xrTechnologies: List<String>,
    val details: List<String>
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
                        it.contains("vrapi") || it.contains("oculus")
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
            if (nativeLibraries.any { it.contains("openvr") || it.contains("steamvr") } ||
                lowerEntries.any { it.contains("steamvr") }
            ) add("OpenVR/SteamVR")
            if (xrTechnologiesEmptyFallback(info, lowerEntries)) add("Android VR feature declaration")
        }.distinct()

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
            questSignals.isNotEmpty() -> ApkLaunchRoute.QUEST_RUNTIME_REQUIRED
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
                ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE ->
                    add("Обнаружены OpenXR-маркеры. Нужен активный Android OpenXR Runtime с поддержкой расширений и графического API. Один OpenXR Loader в QuestPhoneXR не является Runtime.")
                ApkLaunchRoute.XR_SOURCE_ADAPTATION_REQUIRED ->
                    add("Обнаружены XR-компоненты. Для телефона потребуется адаптация графики, трекинга и ввода под runtime QuestPhoneXR.")
                ApkLaunchRoute.ANDROID_APP_CANDIDATE ->
                    add("По доступным признакам это кандидат на обычную Android-установку. Стерео-режим и управление VR этим анализом не подтверждаются.")
            }
            if (engine != "Не определён") add("Обнаруженный движок: $engine.")
            if (xrTechnologies.isNotEmpty()) add("XR-признаки: ${xrTechnologies.joinToString()}.")
            if (questSignals.isNotEmpty()) add("Quest-признаки: ${questSignals.joinToString()}.")
        }

        return ApkRuntimeAssessment(route, engine, xrTechnologies, details)
    }

    private fun xrTechnologiesEmptyFallback(
        info: ImportedApkInfo,
        lowerEntries: List<String>
    ): Boolean = info.requiredFeatures.any { it.contains("vr", ignoreCase = true) } ||
        lowerEntries.any { it.contains("vrmanifest") }
}
