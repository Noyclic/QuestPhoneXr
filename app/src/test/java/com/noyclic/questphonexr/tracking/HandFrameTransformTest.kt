package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class HandFrameTransformTest {

    @Test
    fun inverseRotationRestoresSourceCoordinatesForAllCameraOrientations() {
        val cases = listOf(
            Triple(0, 0.2f to 0.3f, 0.2f to 0.3f),
            Triple(90, 0.7f to 0.2f, 0.2f to 0.3f),
            Triple(180, 0.8f to 0.7f, 0.2f to 0.3f),
            Triple(270, 0.3f to 0.8f, 0.2f to 0.3f)
        )
        cases.forEach { (rotation, rotated, expected) ->
            val transform = HandFrameTransform(
                leftPx = 0f, topPx = 0f, scale = 1f,
                targetWidthPx = 100, targetHeightPx = 100,
                rotatedWidthPx = 100, rotatedHeightPx = 100,
                rotationDegrees = rotation
            )
            val actual = transform.toSourceNormalized(rotated.first, rotated.second)
            assertEquals(expected.first, actual.first, 0.001f)
            assertEquals(expected.second, actual.second, 0.001f)
        }
    }

    @Test
    fun unmirroringHappensBeforeInverseCameraRotation() {
        val transform = HandFrameTransform(
            leftPx = 0f, topPx = 0f, scale = 1f,
            targetWidthPx = 100, targetHeightPx = 100,
            rotatedWidthPx = 100, rotatedHeightPx = 100,
            mirroredHorizontally = true,
            rotationDegrees = 90
        )
        val actual = transform.toSourceNormalized(0.8f, 0.2f)
        assertEquals(0.2f, actual.first, 0.001f)
        assertEquals(0.2f, actual.second, 0.001f)
    }
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
    fun mapsLetterboxedCenterToSourceCenter() {
        val transform = HandFrameTransform(
            leftPx = 105f,
            topPx = 0f,
            scale = 0.5625f,
            targetWidthPx = 480,
            targetHeightPx = 360,
            rotatedWidthPx = 480,
            rotatedHeightPx = 640
        )
        val mapped = transform.toSourceNormalized(0.5f, 0.5f)
        assertEquals(0.5f, mapped.first, 0.0001f)
        assertEquals(0.5f, mapped.second, 0.0001f)
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
        val mapped = transform.toSourceNormalized(0f, 0.5f)
        assertEquals(0f, mapped.first, 0.0001f)
        assertEquals(0.75f, mapped.second, 0.0001f)
    }

    @Test
    fun unmirrorsFrontCameraLandmarksBackIntoOriginalCameraCoordinates() {
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
        val mappedLeft = transform.toSourceNormalized(0.2f, 0.4f)
        val mappedRight = transform.toSourceNormalized(0.8f, 0.4f)

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
        val mapped = transform.toSourceNormalized(0.25f, 0.75f)
        assertEquals(0.25f, mapped.first, 0.0001f)
        assertEquals(0.75f, mapped.second, 0.0001f)
    }
}
