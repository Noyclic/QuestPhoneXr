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
        assertTrue(byId.getValue("controller-input").details.contains("foreground service"))
        assertTrue(byId.getValue("controller-input").details.contains("not globally captured"))
        assertTrue(byId.getValue("head-orientation").details.contains("Android IPC provider"))
        assertTrue(byId.getValue("head-orientation").details.contains("not positional 6DoF"))
        assertEquals(RuntimeCapabilityState.NOT_IMPLEMENTED, byId.getValue("position-tracking").state)
        assertEquals(RuntimeCapabilityState.NOT_IMPLEMENTED, byId.getValue("openxr-hand-tracking").state)
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
    fun openXrApkWarningExplainsThatLiveControllerInputIsStillMissing() {
        val warnings = EndXrRuntimeReadiness.warningsFor(listOf("OpenXR"))
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("Перед запуском внешней игры"))
        assertTrue(warnings.single().contains("camera foreground service"))
        assertTrue(warnings.single().contains("ориентацию головы"))
        assertTrue(warnings.single().contains("Android gamepad capture"))
        assertTrue(warnings.single().contains("controller poses"))
        assertTrue(warnings.single().contains("haptics"))
    }
}
