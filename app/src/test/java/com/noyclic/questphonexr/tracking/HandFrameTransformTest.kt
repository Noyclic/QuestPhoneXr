package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class HandFrameTransformTest {
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
