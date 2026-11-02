package com.noyclic.questphonexr.vr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneVrCalibrationTest {
    @Test
    fun viewerProfileClampsValuesToSupportedCalibrationRange() {
        val profile = ViewerProfile(
            ipdMillimeters = 120,
            fieldOfViewDegrees = 20,
            swapEyes = true
        ).normalized()

        assertEquals(ViewerProfile.MAX_IPD_MM, profile.ipdMillimeters)
        assertEquals(ViewerProfile.MIN_FOV_DEGREES, profile.fieldOfViewDegrees)
        assertEquals(true, profile.swapEyes)
    }

    @Test
    fun defaultProfileUsesConservativeShineconStartingValues() {
        val profile = ViewerProfile().normalized()

        assertEquals(64, profile.ipdMillimeters)
        assertEquals(86, profile.fieldOfViewDegrees)
        assertEquals(false, profile.swapEyes)
    }

    @Test
    fun recenteringAtTheCurrentOrientationProducesIdentityRotation() {
        val yaw = floatArrayOf(
            0f, 0f, 1f,
            0f, 1f, 0f,
            -1f, 0f, 0f
        )

        assertArrayEquals(
            floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f
            ),
            PhoneVrMath.relativeRotation(yaw, yaw),
            0.0001f
        )
    }

    @Test
    fun missingReferenceKeepsTheSensorOrientationUnchanged() {
        val rotation = floatArrayOf(
            0f, 0f, 1f,
            0f, 1f, 0f,
            -1f, 0f, 0f
        )

        assertArrayEquals(rotation, PhoneVrMath.relativeRotation(rotation, null), 0f)
    }
}
