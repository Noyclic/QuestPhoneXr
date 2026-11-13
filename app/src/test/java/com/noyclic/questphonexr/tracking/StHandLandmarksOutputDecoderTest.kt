package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StHandLandmarksOutputDecoderTest {

    @Test
    fun decodesTwentyOnePointsAndMapsCropPixelsToCameraCoordinates() {
        val result = decoder().decode(
            outputs = tensors(handType = 0.1f, presence = 0.92f),
            crop = HandRoiCrop(
                leftPx = 100,
                topPx = 50,
                sizePx = 224,
                sourceWidthPx = 640,
                sourceHeightPx = 480
            ),
            modelInputWidthPx = 224,
            modelInputHeightPx = 224,
            frameTimestampMs = 1_000L,
            pipelineLatencyMs = 24L
        )

        val decoded = assertSuccess(result)
        assertEquals(21, decoded.hand.points.size)
        assertEquals(HandSide.LEFT, decoded.hand.side)
        assertEquals(0.92f, decoded.qualityScore, 0.0001f)
        assertEquals(0.8f, decoded.handednessConfidence, 0.0001f)
        assertEquals(212f / 640f, decoded.hand.points[0].x, 0.0001f)
        assertEquals(162f / 480f, decoded.hand.points[0].y, 0.0001f)
        assertTrue(decoded.hand.worldPoints.isEmpty())
        assertEquals(decoded.hand.points, decoded.hand.rawPoints)
    }

    @Test
    fun identifiesRightHandAndKeepsAmbiguousSideUnknown() {
        val crop = HandRoiCrop(0, 0, 224, 224, 224)
        val right = assertSuccess(
            decoder().decode(tensors(handType = 0.9f), crop, 224, 224, 1L)
        )
        assertEquals(HandSide.RIGHT, right.hand.side)
        assertEquals(0.8f, right.handednessConfidence, 0.0001f)

        val ambiguous = assertSuccess(
            decoder().decode(tensors(handType = 0.52f), crop, 224, 224, 2L)
        )
        assertEquals(HandSide.UNKNOWN, ambiguous.hand.side)
        assertEquals(0.04f, ambiguous.handednessConfidence, 0.0001f)
    }

    @Test
    fun rejectsHandPresenceBelowConfiguredThreshold() {
        val result = decoder().decode(
            tensors(presence = 0.14f),
            HandRoiCrop(0, 0, 224, 224, 224),
            224,
            224,
            1L
        )
        assertEquals(
            StHandDecodeRejection.NO_HAND_PRESENT,
            assertRejected(result).reason
        )
    }

    @Test
    fun rejectsUnexpectedTensorShapesAndLengths() {
        val crop = HandRoiCrop(0, 0, 224, 224, 224)
        val base = tensors()
        val badShape = base.copy(
            imageLandmarks = LiteRtFloatTensor(intArrayOf(1, 1, 63), base.imageLandmarks.values)
        )
        assertEquals(
            StHandDecodeRejection.INVALID_TENSOR_SHAPE,
            assertRejected(decoder().decode(badShape, crop, 224, 224, 1L)).reason
        )
        val badLength = base.copy(
            normalizedLandmarks = LiteRtFloatTensor(intArrayOf(1, 63), FloatArray(62))
        )
        assertEquals(
            StHandDecodeRejection.INVALID_TENSOR_SHAPE,
            assertRejected(decoder().decode(badLength, crop, 224, 224, 1L)).reason
        )
    }

    @Test
    fun rejectsNonFiniteMetadataAndLandmarks() {
        val crop = HandRoiCrop(0, 0, 224, 224, 224)
        val base = tensors()
        assertEquals(
            StHandDecodeRejection.NON_FINITE_OUTPUT,
            assertRejected(
                decoder().decode(
                    base.copy(
                        imageLandmarks = base.imageLandmarks.copy(
                            values = base.imageLandmarks.values.copyOf().also {
                                it[17] = Float.NaN
                            }
                        )
                    ),
                    crop, 224, 224, 1L
                )
            ).reason
        )
        assertEquals(
            StHandDecodeRejection.INVALID_HAND_TYPE,
            assertRejected(
                decoder().decode(tensors(handType = 1.2f), crop, 224, 224, 1L)
            ).reason
        )
        assertEquals(
            StHandDecodeRejection.INVALID_HAND_PRESENCE,
            assertRejected(
                decoder().decode(tensors(presence = -0.1f), crop, 224, 224, 1L)
            ).reason
        )
        assertEquals(
            StHandDecodeRejection.INVALID_LANDMARK_COORDINATE,
            assertRejected(
                decoder().decode(
                    base.copy(
                        imageLandmarks = base.imageLandmarks.copy(
                            values = base.imageLandmarks.values.copyOf().also {
                                it[0] = 400f
                            }
                        )
                    ),
                    crop, 224, 224, 1L
                )
            ).reason
        )
    }

    @Test
    fun rejectsInvalidFrameTimingAndDimensions() {
        val crop = HandRoiCrop(0, 0, 224, 224, 224)
        val outputs = tensors()
        assertEquals(
            StHandDecodeRejection.INVALID_FRAME_TIMESTAMP,
            assertRejected(
                decoder().decode(outputs, crop, 224, 224, 0L)
            ).reason
        )
        assertEquals(
            StHandDecodeRejection.INVALID_PIPELINE_LATENCY,
            assertRejected(
                decoder().decode(outputs, crop, 224, 224, 1L, -1L)
            ).reason
        )
        assertEquals(
            StHandDecodeRejection.INVALID_MODEL_INPUT_SIZE,
            assertRejected(
                decoder().decode(outputs, crop, 0, 224, 1L)
            ).reason
        )
    }

    @Test
    fun permitsBorderLandmarksAndMapsThemOutsideTheOriginalCropWhenWithinLimits() {
        val crop = HandRoiCrop(100, 50, 224, 640, 480)
        val output = tensors().copy(
            imageLandmarks = LiteRtFloatTensor(
                intArrayOf(1, 63),
                FloatArray(63).also { values ->
                    for (index in 0 until 21) {
                        values[index * 3] = -20f
                        values[index * 3 + 1] = 112f
                    }
                }
            )
        )
        val decoded = assertSuccess(
            decoder().decode(output, crop, 224, 224, 10L)
        )
        assertTrue(decoded.hand.points.first().x < 100f / 640f)
    }

    private fun decoder() = StHandLandmarksOutputDecoder

    private fun tensors(
        handType: Float = 0.9f,
        presence: Float = 0.9f
    ) = StHandLandmarksTensors(
        handType = LiteRtFloatTensor(intArrayOf(1, 1), floatArrayOf(handType)),
        normalizedLandmarks = LiteRtFloatTensor(
            intArrayOf(1, 63),
            FloatArray(63) { it / 63f }
        ),
        handPresence = LiteRtFloatTensor(intArrayOf(1, 1), floatArrayOf(presence)),
        imageLandmarks = LiteRtFloatTensor(
            intArrayOf(1, 63),
            FloatArray(63) { index ->
                when (index % 3) {
                    0 -> 112f
                    1 -> 112f
                    else -> 0f
                }
            }
        )
    )

    private fun assertSuccess(
        result: StHandLandmarksDecodeResult
    ): StHandLandmarksDecodeResult.Success {
        assertTrue(
            "Expected success, got $result",
            result is StHandLandmarksDecodeResult.Success
        )
        return result as StHandLandmarksDecodeResult.Success
    }

    private fun assertRejected(
        result: StHandLandmarksDecodeResult
    ): StHandLandmarksDecodeResult.Rejected {
        assertTrue(
            "Expected rejection, got $result",
            result is StHandLandmarksDecodeResult.Rejected
        )
        return result as StHandLandmarksDecodeResult.Rejected
    }
}
