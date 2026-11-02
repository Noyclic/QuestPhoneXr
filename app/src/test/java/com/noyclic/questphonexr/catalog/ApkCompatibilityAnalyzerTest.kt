package com.noyclic.questphonexr.catalog

import org.junit.Assert.assertTrue
import org.junit.Test

class ApkCompatibilityAnalyzerTest {
    private fun sample(
        minSdk: Int = 29,
        nativeAbis: List<String> = listOf("arm64-v8a"),
        requiredFeatures: List<String> = emptyList(),
        packageName: String = "com.example.game",
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
    fun reportsWhenApkRequiresANewerAndroidApi() {
        val warnings = ApkCompatibilityAnalyzer.warnings(
            info = sample(minSdk = 36),
            deviceApiLevel = 35,
            supportedAbis = listOf("arm64-v8a"),
            missingRequiredFeatures = emptySet()
        )

        assertTrue(warnings.any { it.contains("Минимальная версия Android") })
    }

    @Test
    fun reportsNativeAbiMismatch() {
        val warnings = ApkCompatibilityAnalyzer.warnings(
            info = sample(nativeAbis = listOf("x86_64")),
            deviceApiLevel = 35,
            supportedAbis = listOf("arm64-v8a"),
            missingRequiredFeatures = emptySet()
        )

        assertTrue(warnings.any { it.contains("ABI") })
    }

    @Test
    fun reportsMissingHardwareFeatures() {
        val warnings = ApkCompatibilityAnalyzer.warnings(
            info = sample(requiredFeatures = listOf("android.hardware.vr.headtracking")),
            deviceApiLevel = 35,
            supportedAbis = listOf("arm64-v8a"),
            missingRequiredFeatures = setOf("android.hardware.vr.headtracking")
        )

        assertTrue(warnings.any { it.contains("обязательные функции") })
    }

    @Test
    fun flagsQuestPackagesWithoutClaimingTheyCanRun() {
        val warnings = ApkCompatibilityAnalyzer.warnings(
            info = sample(packageName = "com.oculus.game"),
            deviceApiLevel = 35,
            supportedAbis = listOf("arm64-v8a"),
            missingRequiredFeatures = emptySet()
        )

        assertTrue(warnings.any { it.contains("Quest/Meta VR-пакета") })
    }

    @Test
    fun cleanManifestIsNotPresentedAsCompatibilityProof() {
        val warnings = ApkCompatibilityAnalyzer.warnings(
            info = sample(),
            deviceApiLevel = 35,
            supportedAbis = listOf("arm64-v8a"),
            missingRequiredFeatures = emptySet()
        )

        assertTrue(warnings.single().contains("не подтверждает запуск"))
    }
}
