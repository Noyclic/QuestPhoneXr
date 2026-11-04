package com.noyclic.questphonexr.catalog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkRuntimeCompatibilityTest {
    private fun sample(
        packageName: String = "com.example.game",
        minSdk: Int = 29,
        nativeAbis: List<String> = listOf("arm64-v8a"),
        requiredFeatures: List<String> = emptyList(),
        vrMetadataKeys: List<String> = emptyList(),
        vrMetadataValues: Map<String, String> = emptyMap()
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
        vrMetadataValues = vrMetadataValues,
        sizeBytes = 1L,
        importedAtEpochMs = 1L
    )

    @Test
    fun capturesGearVrMarkerStoredAsValueUnderGenericMetadataKey() {
        val collected = VrManifestMetadataFilter.collect(
            mapOf(
                "mode" to "com.samsung.android.hmt.vrsvc",
                "com.oculus.vr.application.mode" to "quest",
                "analytics.enabled" to true
            )
        )

        assertEquals("com.samsung.android.hmt.vrsvc", collected["mode"])
        assertEquals("quest", collected["com.oculus.vr.application.mode"])
        assertEquals(setOf("mode", "com.oculus.vr.application.mode"), collected.keys)
    }

    @Test
    fun preservesVrMetadataKeyValuesAndIgnoresUnrelatedEntries() {
        val collected = VrManifestMetadataFilter.collect(
            mapOf(
                "com.example.vr.mode" to "immersive",
                "ordinary.setting" to "enabled",
                "com.example.xr.runtime" to "openxr"
            )
        )

        assertEquals("immersive", collected["com.example.vr.mode"])
        assertEquals("openxr", collected["com.example.xr.runtime"])
        assertTrue("ordinary.setting" !in collected)
    }

    @Test
    fun apkFingerprintIsStableAndChangesWhenBytesChange() {
        val first = File.createTempFile("endgvr-first-", ".apk")
        val second = File.createTempFile("endgvr-second-", ".apk")
        try {
            first.writeBytes("gear-vr-apk-fixture".toByteArray())
            second.writeBytes("gear-vr-apk-fixture".toByteArray())

            val firstHash = ApkArchiveFingerprint.sha256(first)
            assertEquals(firstHash, ApkArchiveFingerprint.sha256(second))
            assertTrue(firstHash.matches(Regex("[0-9a-f]{64}")))

            second.appendText("-changed")
            assertNotEquals(firstHash, ApkArchiveFingerprint.sha256(second))
        } finally {
            first.delete()
            second.delete()
        }
    }

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
    fun routesExplicitGearVrApkMarkersToEndGvrInsteadOfQuestRuntime() {
        val result = ApkRuntimeCompatibility.assess(
            info = sample(vrMetadataKeys = listOf("com.samsung.android.vr.application.mode")),
            archiveEntries = listOf("lib/armeabi-v7a/libvrapi.so", "lib/armeabi-v7a/libunity.so"),
            deviceApiLevel = 35,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            missingRequiredFeatures = emptySet()
        )

        assertEquals(ApkLaunchRoute.GEAR_VR_LEGACY_CANDIDATE, result.route)
        assertTrue(result.xrTechnologies.contains("Gear VR (legacy)"))
        assertTrue(result.details.any { it.contains("EndGVR") })
    }

    @Test
    fun metadataValuesAreIncludedInTheStaticAudit() {
        val result = ApkRuntimeCompatibility.assess(
            info = sample(
                vrMetadataKeys = listOf("com.example.vr.mode"),
                vrMetadataValues = mapOf("com.example.vr.mode" to "com.samsung.android.hmt.vrsvc")
            ),
            archiveEntries = listOf("lib/armeabi-v7a/libvrapi.so"),
            deviceApiLevel = 35,
            supportedAbis = listOf("armeabi-v7a"),
            missingRequiredFeatures = emptySet()
        )

        assertEquals(ApkLaunchRoute.GEAR_VR_LEGACY_CANDIDATE, result.route)
        assertTrue(result.audit.metadataValues.contains("com.samsung.android.hmt.vrsvc"))
    }

    @Test
    fun keepsGenericVrApiEvidenceSeparateFromDefiniteGearVr() {
        val result = ApkRuntimeCompatibility.assess(
            info = sample(nativeAbis = listOf("armeabi-v7a")),
            archiveEntries = listOf("lib/armeabi-v7a/libvrapi.so", "lib/armeabi-v7a/libunity.so"),
            deviceApiLevel = 35,
            supportedAbis = listOf("armeabi-v7a"),
            missingRequiredFeatures = emptySet()
        )

        assertEquals(ApkLaunchRoute.LEGACY_OCULUS_MOBILE_CANDIDATE, result.route)
        assertTrue(result.xrTechnologies.contains("Legacy Oculus Mobile SDK"))
        assertTrue(result.xrTechnologies.none { it == "Gear VR (legacy)" })
    }

    @Test
    fun routesApkWithGearVrManifestServiceToEndGvr() {
        val info = sample().copy(
            serviceNames = listOf("com.samsung.android.hmt.vrsvc.system.SystemService"),
            requestedPermissions = listOf("com.samsung.android.hmt.vrsvc.permission.READ")
        )
        val result = ApkRuntimeCompatibility.assess(
            info = info,
            archiveEntries = listOf("lib/armeabi-v7a/libvrapi.so"),
            deviceApiLevel = 35,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            missingRequiredFeatures = emptySet()
        )

        assertEquals(ApkLaunchRoute.GEAR_VR_LEGACY_CANDIDATE, result.route)
        assertTrue(result.details.any { it.contains("manifest component") })
    }

    @Test
    fun buildsStaticAuditForLegacyGearVrSdkAndManifestReferences() {
        val info = sample().copy(
            vrMetadataKeys = listOf("com.samsung.android.vr.application.mode"),
            requestedPermissions = listOf("com.samsung.android.hmt.vrsvc.permission.READ"),
            serviceNames = listOf("com.samsung.android.hmt.vrsvc.system.SystemService")
        )
        val result = ApkRuntimeCompatibility.assess(
            info = info,
            archiveEntries = listOf(
                "lib/armeabi-v7a/libvrapi.so",
                "lib/armeabi-v7a/libunity.so"
            ),
            deviceApiLevel = 35,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            missingRequiredFeatures = emptySet()
        )

        assertEquals(ApkLaunchRoute.GEAR_VR_LEGACY_CANDIDATE, result.route)
        assertTrue(result.audit.nativeLibraries.contains("libvrapi.so"))
        assertTrue(result.audit.nativeLibraries.contains("libunity.so"))
        assertTrue(result.audit.sdkRuntimeMarkers.contains("libvrapi.so"))
        assertTrue(result.audit.manifestReferences.any { it.contains("samsung.android.hmt") })
        assertTrue(result.audit.componentReferences.any { it.contains("SystemService") })
        assertTrue(result.audit.abiSummary.contains("armeabi-v7a"))
    }

    @Test
    fun openXrLoaderIsReportedWithoutBeingMislabelledAsLegacyOculusRuntime() {
        val result = ApkRuntimeCompatibility.assess(
            info = sample(),
            archiveEntries = listOf(
                "lib/arm64-v8a/libopenxr_loader.so",
                "lib/arm64-v8a/libunity.so"
            ),
            deviceApiLevel = 35,
            supportedAbis = listOf("arm64-v8a"),
            missingRequiredFeatures = emptySet()
        )

        assertTrue(result.audit.nativeLibraries.contains("libopenxr_loader.so"))
        assertTrue(result.audit.sdkRuntimeMarkers.contains("libopenxr_loader.so"))
        assertTrue(result.audit.sdkRuntimeMarkers.none { it == "libvrapi.so" })
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
        assertTrue(result.details.any { it.contains("Android gamepad capture") })
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
