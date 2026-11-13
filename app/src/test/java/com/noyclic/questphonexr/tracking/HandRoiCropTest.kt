package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandRoiCropTest {

    @Test
    fun createsSquareCropWithPaddingFromTwentyOneLandmarks() {
        val crop = requireNotNull(
            HandRoiCrop.fromLandmarks(
                points = handPoints(0.25f, 0.25f, 0.75f, 0.75f),
                sourceWidthPx = 640,
                sourceHeightPx = 480,
                paddingFactor = 1.5f
            )
        )

        assertEquals(80, crop.leftPx)
        assertEquals(0, crop.topPx)
        assertEquals(480, crop.sizePx)
    }

    @Test
    fun mapsCropCenterAndCornersBackToSourceCoordinates() {
        val crop = HandRoiCrop(
            leftPx = 80,
            topPx = 40,
            sizePx = 240,
            sourceWidthPx = 640,
            sourceHeightPx = 480
        )

        val center = requireNotNull(crop.toSourceNormalized(0.5f, 0.5f))
        assertEquals(0.5f, center.first, 0.0001f)
        assertEquals(1f / 3f, center.second, 0.0001f)

        val topLeft = requireNotNull(crop.toSourceNormalized(0f, 0f))
        assertEquals(0.125f, topLeft.first, 0.0001f)
        assertEquals(1f / 12f, topLeft.second, 0.0001f)
    }

    @Test
    fun cropAndSourceCoordinateMappingsAreInverse() {
        val crop = HandRoiCrop(
            leftPx = 120,
            topPx = 60,
            sizePx = 240,
            sourceWidthPx = 640,
            sourceHeightPx = 480
        )
        val source = requireNotNull(crop.toSourceNormalized(0.3f, 0.7f))
        val roundTrip = requireNotNull(crop.toCropNormalized(source.first, source.second))

        assertEquals(0.3f, roundTrip.first, 0.0001f)
        assertEquals(0.7f, roundTrip.second, 0.0001f)
    }

    @Test
    fun cropRemainsInsideImageWhenHandTouchesEveryEdge() {
        val edgeCases = listOf(
            handPoints(-0.2f, 0.2f, 0.15f, 0.8f),
            handPoints(0.85f, 0.2f, 1.2f, 0.8f),
            handPoints(0.2f, -0.2f, 0.8f, 0.15f),
            handPoints(0.2f, 0.85f, 0.8f, 1.2f)
        )

        edgeCases.forEach { points ->
            val crop = requireNotNull(HandRoiCrop.fromLandmarks(points, 640, 480))
            assertTrue(crop.leftPx >= 0)
            assertTrue(crop.topPx >= 0)
            assertTrue(crop.leftPx + crop.sizePx <= 640)
            assertTrue(crop.topPx + crop.sizePx <= 480)
            assertTrue(crop.sizePx <= 480)
        }
    }

    @Test
    fun enforcesMinimumCropAndNeverExceedsShorterFrameDimension() {
        val crop = requireNotNull(
            HandRoiCrop.fromLandmarks(
                points = handPoints(0.49f, 0.49f, 0.51f, 0.51f),
                sourceWidthPx = 640,
                sourceHeightPx = 480,
                minimumCropPx = 96
            )
        )
        assertEquals(96, crop.sizePx)

        val tallFrameCrop = requireNotNull(
            HandRoiCrop.fromLandmarks(
                points = handPoints(0.1f, 0.1f, 0.9f, 0.9f),
                sourceWidthPx = 480,
                sourceHeightPx = 640,
                paddingFactor = 3f
            )
        )
        assertEquals(480, tallFrameCrop.sizePx)
    }

    @Test
    fun rejectsIncompleteNonFiniteAndDegenerateLandmarks() {
        val valid = handPoints(0.2f, 0.2f, 0.8f, 0.8f)
        assertNull(HandRoiCrop.fromLandmarks(valid.take(20), 640, 480))
        assertNull(
            HandRoiCrop.fromLandmarks(
                valid.toMutableList().also {
                    it[4] = HandPoint(Float.NaN, 0.5f, 0f)
                },
                640,
                480
            )
        )
        assertNull(HandRoiCrop.fromLandmarks(handPoints(0.5f, 0.5f, 0.5f, 0.5f), 640, 480))
        assertNull(HandRoiCrop.fromLandmarks(valid, 0, 480))
        assertNull(HandRoiCrop.fromLandmarks(valid, 640, 480, paddingFactor = Float.NaN))
    }

    @Test
    fun coordinateMappingRejectsNonFiniteInput() {
        val crop = HandRoiCrop(0, 0, 240, 640, 480)
        assertNull(crop.toSourceNormalized(Float.NaN, 0.5f))
        assertNull(crop.toCropNormalized(0.5f, Float.POSITIVE_INFINITY))
    }

    private fun handPoints(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): List<HandPoint> = List(HandRoiCrop.REQUIRED_HAND_LANDMARKS) { index ->
        val x = if (index % 2 == 0) left else right
        val y = if (index % 3 == 0) top else bottom
        HandPoint(x, y, 0f)
    }
}
