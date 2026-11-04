package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndXrRuntimeReadinessTest {
    @Test
    fun inventoryMarksSandboxInputPartialButDoesNotClaimCrossProcessInputOr6Dof() {
        val byId = EndXrRuntimeReadiness.capabilities.associateBy { it.id }
        assertEquals(RuntimeCapabilityState.PARTIAL, byId.getValue("openxr-actions").state)
        assertEquals(RuntimeCapabilityState.PARTIAL, byId.getValue("controller-input").state)
        assertTrue(byId.getValue("controller-input").details.contains("separate package"))
        assertTrue(byId.getValue("controller-input").details.contains("clears input onPause"))
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
        assertTrue(warnings.single().contains("ввод Sandbox уже отображается"))
        assertTrue(warnings.single().contains("Отдельный игровой процесс"))
        assertTrue(warnings.single().contains("foreground-служба"))
        assertTrue(warnings.single().contains("haptics"))
    }
}
