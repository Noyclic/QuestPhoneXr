package com.noyclic.questphonexr.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkRuntimeCompatibilityTest {
    private fun sample(
        packageName: String = "com.example.game",
        minSdk: Int = 29,
        nativeAbis: List<String> = listOf("arm64-v8a"),
        requiredFeatures: List<String> = emptyList(),
        vrMetadataKeys: List<String> = emptyList()
    ) = ImportedApkInfo(
        id = "test",
        fileName = "test.apk",
        originalName = "test.apk",
        displayName = "Test",
        packageName = packageName,
        versionName = "1.0",
        versionCode = 1L,
        minSdk = minSdk,
        targetSdk = 35,
        nativeAbis = nativeAbis,
        requiredFeatures = requiredFeatures,
        vrMetadataKeys = vrMetadataKeys,
        sizeBytes = 1L,
        importedAtEpochMs = 1L
    )

    @Test
    fun identifiesQuestRuntimeRequirementFromOculusNativeLibraries() {
        val result = ApkRuntimeCompatibility.assess(
            info = sample(),
            archiveEntries = listOf("lib/arm64-v8a/libunity.so", "lib/arm64-v8a/libOVRPlugin.so"),
            deviceApiLevel = 35,
            supportedAbis = listOf("arm64-v8a"),
            missingRequiredFeatures = emptySet()
        )

        assertEquals(ApkLaunchRoute.QUEST_RUNTIME_REQUIRED, result.route)
        assertEquals("Unity", result.engine)
        assertTrue(result.xrTechnologies.contains("Meta/Oculus XR"))
    }

    @Test
    fun identifiesOpenXrProjectAsNeedingActiveRuntime() {
        val result = ApkRuntimeCompatibility.assess(
            info = sample(),
            archiveEntries = listOf("lib/arm64-v8a/libopenxr_loader.so", "lib/arm64-v8a/libunity.so"),
            deviceApiLevel = 35,
            supportedAbis = listOf("arm64-v8a"),
            missingRequiredFeatures = emptySet()
        )

        assertEquals(ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE, result.route)
        assertTrue(result.xrTechnologies.contains("OpenXR"))
        assertTrue(result.details.any { it.contains("camera foreground service") })
        assertTrue(result.details.any { it.contains("Android gamepad события не перехватываются глобально") })
        assertTrue(result.details.any { it.contains("haptics") })
    }

    @Test
    fun keepsPlainAndroidApkAsLaunchCandidateWithoutClaimingVrSupport() {
        val result = ApkRuntimeCompatibility.assess(
            info = sample(),
            archiveEntries = listOf("lib/arm64-v8a/libexample.so"),
            deviceApiLevel = 35,
            supportedAbis = listOf("arm64-v8a"),
            missingRequiredFeatures = emptySet()
        )

        assertEquals(ApkLaunchRoute.ANDROID_APP_CANDIDATE, result.route)
        assertTrue(result.details.any { it.contains("не подтверждаются") })
    }

    @Test
    fun blocksApkWhenNativeAbiDoesNotMatch() {
        val result = ApkRuntimeCompatibility.assess(
            info = sample(nativeAbis = listOf("x86_64")),
            archiveEntries = listOf("lib/x86_64/libexample.so"),
            deviceApiLevel = 35,
            supportedAbis = listOf("arm64-v8a"),
            missingRequiredFeatures = emptySet()
        )

        assertEquals(ApkLaunchRoute.DEVICE_REQUIREMENTS_BLOCKED, result.route)
    }
}
