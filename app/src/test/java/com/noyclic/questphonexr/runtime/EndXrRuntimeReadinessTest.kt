package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndXrRuntimeReadinessTest {
    @Test
    fun inventoryMarksCrossProcessInputPartialAndDoesNotClaim6Dof() {
        val byId = EndXrRuntimeReadiness.capabilities.associateBy { it.id }
        assertEquals(RuntimeCapabilityState.PARTIAL, byId.getValue("openxr-actions").state)
        assertEquals(RuntimeCapabilityState.PARTIAL, byId.getValue("controller-input").state)
        assertEquals(RuntimeCapabilityState.PARTIAL, byId.getValue("input-package-authorization").state)
        assertTrue(byId.getValue("controller-input").details.contains("foreground service"))
        assertTrue(byId.getValue("controller-input").details.contains("power grip"))
        assertTrue(byId.getValue("controller-input").details.contains("26-joint"))
        assertTrue(byId.getValue("controller-input").details.contains("can be revoked"))
        assertTrue(byId.getValue("controller-input").details.contains("does not inject"))
        assertTrue(byId.getValue("controller-input").details.contains("POSE_INPUT"))
        assertTrue(byId.getValue("controller-input").details.contains("never TRACKED"))
        assertTrue(byId.getValue("head-orientation").details.contains("exact input URI"))
        assertEquals(RuntimeCapabilityState.NOT_IMPLEMENTED, byId.getValue("position-tracking").state)
        assertEquals(RuntimeCapabilityState.PARTIAL, byId.getValue("openxr-hand-tracking").state)
        assertTrue(byId.getValue("openxr-hand-tracking").details.contains("XR_EXT_hand_tracking"))
        assertTrue(byId.getValue("openxr-hand-tracking").details.contains("26 joints"))
        assertTrue(byId.getValue("openxr-hand-tracking").details.contains("never TRACKED"))
        assertEquals(RuntimeCapabilityState.NOT_VERIFIED, byId.getValue("runtime-broker").state)
    }

    @Test
    fun onlyInternalLoaderSessionAndProjectionPathIsMarkedVerified() {
        val verified = EndXrRuntimeReadiness.capabilities
            .filter { it.state == RuntimeCapabilityState.VERIFIED_IN_PROCESS }
            .map { it.id }
        assertEquals(listOf("loader-negotiation", "android-gles-session", "stereo-projection"), verified)
    }

    @Test
    fun openXrApkWarningExplainsPackageAuthorizationAndRemainingLimits() {
        val warnings = EndXrRuntimeReadiness.warningsFor(listOf("OpenXR"))
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("Перед запуском внешней игры"))
        assertTrue(warnings.single().contains("одно установленное приложение"))
        assertTrue(warnings.single().contains("controller_state"))
        assertTrue(warnings.single().contains("не настоящие tracked controller poses"))
        assertTrue(warnings.single().contains("Android gamepad capture"))
        assertTrue(warnings.single().contains("haptics"))
    }
}
