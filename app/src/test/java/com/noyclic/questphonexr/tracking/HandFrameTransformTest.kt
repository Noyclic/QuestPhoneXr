package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class HandFrameTransformTest {

    @Test
    fun normalizesCameraRotationAndHandlesNegativeValues() {
        assertEquals(0, normalizeCameraRotationDegrees(0))
        assertEquals(90, normalizeCameraRotationDegrees(90))
        assertEquals(180, normalizeCameraRotationDegrees(180))
        assertEquals(270, normalizeCameraRotationDegrees(270))
        assertEquals(90, normalizeCameraRotationDegrees(450))
        assertEquals(270, normalizeCameraRotationDegrees(-90))
        assertEquals(0, normalizeCameraRotationDegrees(45))
        assertEquals(0, normalizeCameraRotationDegrees(361))
    }

    @Test
    fun mapsLetterboxedCenterToRotatedCameraFrameCenter() {
        val transform = HandFrameTransform(
            leftPx = 105f,
            topPx = 0f,
            scale = 0.5625f,
            targetWidthPx = 480,
            targetHeightPx = 360,
            rotatedWidthPx = 480,
            rotatedHeightPx = 640
        )
        val mapped = transform.toRotatedFrameNormalized(0.5f, 0.5f)
        assertEquals(0.5f, mapped.first, 0.0001f)
        assertEquals(0.5f, mapped.second, 0.0001f)
    }

    @Test
    fun keepsQuarterTurnLandmarksInTheRotatedDisplayCoordinateFrame() {
        // A 640x480 source rotated by 90 degrees becomes 480x640. It is
        // letterboxed into the 480x360 model image, but the output coordinates
        // must stay in that rotated frame for the sensor-to-world mapping.
        val transform = HandFrameTransform(
            leftPx = 105f,
            topPx = 0f,
            scale = 0.5625f,
            targetWidthPx = 480,
            targetHeightPx = 360,
            rotatedWidthPx = 480,
            rotatedHeightPx = 640
        )
        val mapped = transform.toRotatedFrameNormalized(0.66875f, 0.2f)
        assertEquals(0.8f, mapped.first, 0.0001f)
        assertEquals(0.2f, mapped.second, 0.0001f)
    }

    @Test
    fun removesFrontCameraMirrorWithoutUndoingCameraRotation() {
        val transform = HandFrameTransform(
            leftPx = 105f,
            topPx = 0f,
            scale = 0.5625f,
            targetWidthPx = 480,
            targetHeightPx = 360,
            rotatedWidthPx = 480,
            rotatedHeightPx = 640,
            mirroredHorizontally = true
        )
        val mapped = transform.toRotatedFrameNormalized(0.66875f, 0.2f)
        assertEquals(0.2f, mapped.first, 0.0001f)
        assertEquals(0.2f, mapped.second, 0.0001f)
    }

    @Test
    fun clampsLandmarksThatFallInsideLetterboxBars() {
        val transform = HandFrameTransform(
            leftPx = 80f,
            topPx = 0f,
            scale = 0.5f,
            targetWidthPx = 480,
            targetHeightPx = 360,
            rotatedWidthPx = 640,
            rotatedHeightPx = 480
        )
        val mapped = transform.toRotatedFrameNormalized(0f, 0.5f)
        assertEquals(0f, mapped.first, 0.0001f)
        assertEquals(0.75f, mapped.second, 0.0001f)
    }

    @Test
    fun unmirrorsFrontCameraLandmarksBackIntoOriginalHorizontalCoordinates() {
        val transform = HandFrameTransform(
            leftPx = 0f,
            topPx = 0f,
            scale = 1f,
            targetWidthPx = 640,
            targetHeightPx = 480,
            rotatedWidthPx = 640,
            rotatedHeightPx = 480,
            mirroredHorizontally = true
        )
        val mappedLeft = transform.toRotatedFrameNormalized(0.2f, 0.4f)
        val mappedRight = transform.toRotatedFrameNormalized(0.8f, 0.4f)

        assertEquals(0.8f, mappedLeft.first, 0.0001f)
        assertEquals(0.2f, mappedRight.first, 0.0001f)
        assertEquals(0.4f, mappedLeft.second, 0.0001f)
    }

    @Test
    fun mapsUnletterboxedCoordinatesWithoutChangingThem() {
        val transform = HandFrameTransform(
            leftPx = 0f,
            topPx = 0f,
            scale = 1f,
            targetWidthPx = 320,
            targetHeightPx = 240,
            rotatedWidthPx = 320,
            rotatedHeightPx = 240
        )
        val mapped = transform.toRotatedFrameNormalized(0.25f, 0.75f)
        assertEquals(0.25f, mapped.first, 0.0001f)
        assertEquals(0.75f, mapped.second, 0.0001f)
    }
}
