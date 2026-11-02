package com.noyclic.questphonexr.vr

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerProfileCameraResolutionTest {
    @Test fun customCameraResolutionIsPreserved() {
        val profile = ViewerProfile(cameraWidthPx = 192, cameraHeightPx = 88).normalized()
        assertEquals(192, profile.cameraWidthPx)
        assertEquals(88, profile.cameraHeightPx)
    }

    @Test fun automaticResolutionRemainsAutomatic() {
        val profile = ViewerProfile().normalized()
        assertEquals(ViewerProfile.AUTO_CAMERA_SIZE, profile.cameraWidthPx)
        assertEquals(ViewerProfile.AUTO_CAMERA_SIZE, profile.cameraHeightPx)
    }

    @Test fun cameraDimensionsAreClampedToSafeLimits() {
        val profile = ViewerProfile(cameraWidthPx = 10, cameraHeightPx = 5000).normalized()
        assertEquals(ViewerProfile.MIN_CAMERA_WIDTH_PX, profile.cameraWidthPx)
        assertEquals(ViewerProfile.MAX_CAMERA_HEIGHT_PX, profile.cameraHeightPx)
    }
}
