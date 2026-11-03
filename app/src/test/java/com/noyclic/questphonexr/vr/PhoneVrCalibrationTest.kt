package com.noyclic.questphonexr.vr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneVrCalibrationTest {
    @Test
    fun viewerProfileClampsValuesToSupportedCalibrationRange() {
        val profile = ViewerProfile(
            ipdMillimeters = 120,
            fieldOfViewDegrees = 20,
            swapEyes = true,
            targetFps = 200
        ).normalized()

        assertEquals(ViewerProfile.MAX_IPD_MM, profile.ipdMillimeters)
        assertEquals(ViewerProfile.MIN_FOV_DEGREES, profile.fieldOfViewDegrees)
        assertEquals(true, profile.swapEyes)
        assertEquals(60, profile.targetFps)
    }

    @Test
    fun defaultProfileUsesConservativeShineconStartingValues() {
        val profile = ViewerProfile().normalized()

        assertEquals(64, profile.ipdMillimeters)
        assertEquals(86, profile.fieldOfViewDegrees)
        assertEquals(false, profile.swapEyes)
        assertEquals(30, profile.targetFps)
    }

    @Test
    fun unsupportedFrameRateSnapsToNearestAvailableLimit() {
        assertEquals(45, ViewerProfile(targetFps = 38).normalized().targetFps)
        assertEquals(20, ViewerProfile(targetFps = 1).normalized().targetFps)
    }

    @Test
    fun lensDistortionSettingsAreClampedToTheSupportedRange() {
        assertEquals(
            ViewerProfile.MAX_LENS_DISTORTION_PERCENT,
            ViewerProfile(lensDistortionPercent = 999).normalized().lensDistortionPercent
        )
        assertEquals(
            ViewerProfile.MIN_LENS_DISTORTION_PERCENT,
            ViewerProfile(lensDistortionPercent = -999).normalized().lensDistortionPercent
        )
    }

    @Test
    fun neutralLensSettingLeavesTextureCoordinatesUnchanged() {
        val result = PhoneVrMath.distortedTextureCoordinate(0.82f, 0.27f, 0f)
        assertArrayEquals(floatArrayOf(0.82f, 0.27f), result, 0.0001f)
    }

    @Test
    fun lensWarpKeepsTheOpticalCenterFixedAndMovesEdgesByItsSign() {
        val center = PhoneVrMath.distortedTextureCoordinate(0.5f, 0.5f, 0.4f)
        val positive = PhoneVrMath.distortedTextureCoordinate(0.9f, 0.5f, 0.2f)
        val negative = PhoneVrMath.distortedTextureCoordinate(0.9f, 0.5f, -0.2f)

        assertArrayEquals(floatArrayOf(0.5f, 0.5f), center, 0.0001f)
        assertTrue(positive[0] > 0.9f)
        assertTrue(negative[0] < 0.9f)
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
