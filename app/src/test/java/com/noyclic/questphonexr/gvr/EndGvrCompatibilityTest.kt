package com.noyclic.questphonexr.gvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndGvrCompatibilityTest {
    @Test
    fun recognizesExplicitSamsungGearVrMetadata() {
        val result = EndGvrCompatibility.inspect(
            packageName = "com.example.classicvr",
            metadataKeys = listOf("com.samsung.android.vr.application.mode"),
            requiredFeatures = emptyList(),
            archiveEntries = listOf("lib/armeabi-v7a/libvrapi.so")
        )

        assertEquals(EndGvrClassification.GEAR_VR_MARKED, result.classification)
        assertTrue(result.gearVrMarkers.isNotEmpty())
        assertTrue(result.legacyOculusMarkers.isNotEmpty())
    }

    @Test
    fun recognizesExplicitSamsungGearVrMarkerInsideMetadataValue() {
        val result = EndGvrCompatibility.inspect(
            packageName = "com.example.classicvr",
            metadataKeys = listOf("com.example.vr.mode"),
            metadataValues = listOf("com.samsung.android.hmt.vrsvc"),
            requiredFeatures = emptyList(),
            archiveEntries = emptyList()
        )

        assertEquals(EndGvrClassification.GEAR_VR_MARKED, result.classification)
        assertTrue(result.gearVrMarkers.any { it.contains("metadata value") })
    }

    @Test
    fun doesNotClassifyOrdinaryQuestMetadataValueAsGearVr() {
        val result = EndGvrCompatibility.inspect(
            packageName = "com.oculus.quest.example",
            metadataKeys = listOf("com.oculus.vr.application.mode"),
            metadataValues = listOf("quest"),
            requiredFeatures = emptyList(),
            archiveEntries = listOf("lib/arm64-v8a/libOVRPlugin.so")
        )

        assertEquals(EndGvrClassification.NO_GEAR_VR_EVIDENCE, result.classification)
    }

    @Test
    fun recognizesLegacyVrApiWithoutClaimingItIsDefinitelyGearVr() {
        val result = EndGvrCompatibility.inspect(
            packageName = "com.example.legacyvr",
            metadataKeys = emptyList(),
            requiredFeatures = emptyList(),
            archiveEntries = listOf("lib/armeabi-v7a/libvrapi.so")
        )

        assertEquals(EndGvrClassification.LEGACY_OCULUS_MOBILE_SDK, result.classification)
        assertTrue(result.gearVrMarkers.isEmpty())
    }

    @Test
    fun doesNotTreatAQuestPackageAsGearVrWithoutGearEvidence() {
        val result = EndGvrCompatibility.inspect(
            packageName = "com.oculus.quest.example",
            metadataKeys = listOf("com.oculus.vr.application.mode"),
            requiredFeatures = emptyList(),
            archiveEntries = listOf("lib/arm64-v8a/libOVRPlugin.so")
        )

        assertEquals(EndGvrClassification.NO_GEAR_VR_EVIDENCE, result.classification)
    }

    @Test
    fun recognizesSamsungGearVrPackageNamespaceCaseInsensitively() {
        val result = EndGvrCompatibility.inspect(
            packageName = "COM.SAMSUNG.ANDROID.HMT.VRSVC",
            metadataKeys = emptyList(),
            requiredFeatures = emptyList(),
            archiveEntries = emptyList()
        )

        assertEquals(EndGvrClassification.GEAR_VR_MARKED, result.classification)
    }

    @Test
    fun recognizesGearVrServiceAndPermissionInManifest() {
        val result = EndGvrCompatibility.inspect(
            packageName = "com.example.classicvr",
            metadataKeys = emptyList(),
            requiredFeatures = emptyList(),
            archiveEntries = emptyList(),
            requestedPermissions = listOf("com.samsung.android.hmt.vrsvc.permission.READ"),
            serviceNames = listOf("com.samsung.android.hmt.vrsvc.system.SystemService")
        )

        assertEquals(EndGvrClassification.GEAR_VR_MARKED, result.classification)
        assertTrue(result.gearVrMarkers.any { it.contains("manifest permission") })
        assertTrue(result.gearVrMarkers.any { it.contains("manifest component") })
    }

    @Test
    fun ordinaryOculusNamesDoNotBecomeSamsungGearVrEvidence() {
        val result = EndGvrCompatibility.inspect(
            packageName = "com.oculus.example",
            metadataKeys = listOf("com.oculus.vr.application.mode"),
            requiredFeatures = emptyList(),
            archiveEntries = listOf("lib/arm64-v8a/libOVRPlugin.so"),
            serviceNames = listOf("com.oculus.vr.system.OculusService")
        )

        assertEquals(EndGvrClassification.NO_GEAR_VR_EVIDENCE, result.classification)
    }

    @Test
    fun ordinaryOpenXrArchiveHasNoGearVrEvidence() {
        val result = EndGvrCompatibility.inspect(
            packageName = "com.example.openxr",
            metadataKeys = emptyList(),
            requiredFeatures = listOf("org.khronos.openxr"),
            archiveEntries = listOf("lib/arm64-v8a/libopenxr_loader.so")
        )

        assertEquals(EndGvrClassification.NO_GEAR_VR_EVIDENCE, result.classification)
    }
}
