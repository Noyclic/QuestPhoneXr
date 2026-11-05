package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HandCameraPreferenceTest {
    @Test
    fun rearCameraIsDefaultEvenWhenFrontCameraExists() {
        assertEquals(
            HandCameraPreference.Lens.BACK,
            HandCameraPreference.chooseLens(false, frontAvailable = true, backAvailable = true)
        )
    }

    @Test
    fun frontCameraRequiresExplicitOptIn() {
        assertEquals(
            HandCameraPreference.Lens.FRONT,
            HandCameraPreference.chooseLens(true, frontAvailable = true, backAvailable = true)
        )
    }

    @Test
    fun unavailableFrontCameraUsesRearCamera() {
        assertEquals(
            HandCameraPreference.Lens.BACK,
            HandCameraPreference.chooseLens(true, frontAvailable = false, backAvailable = true)
        )
    }

    @Test
    fun unavailableRearCameraDoesNotSilentlyEnableFrontCamera() {
        assertThrows(IllegalStateException::class.java) {
            HandCameraPreference.chooseLens(false, frontAvailable = true, backAvailable = false)
        }
    }
}
