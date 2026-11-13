package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmposeHandOutputDecoderTest {

    @Test
    fun decodesSimccArgmaxAndMapsPointsBackThroughRoi() {
        val outputs = outputs().also {
            it.simccX.values[0 * RtmposeHandOutputDecoder.SIMCC_BIN_COUNT + 200] = 10f
            it.simccY.values[0 * RtmposeHandOutputDecoder.SIMCC_BIN_COUNT + 120] = 11f
            it.simccX.values[1 * RtmposeHandOutputDecoder.SIMCC_BIN_COUNT + 511] = 12f
            it.simccY.values[1 * RtmposeHandOutputDecoder.SIMCC_BIN_COUNT + 511] = 13f
        }
        val crop = HandRoiCrop(10, 20, 256, 640, 480)

        val decoded = assertSuccess(
            RtmposeHandOutputDecoder.decode(
                outputs = outputs,
                crop = crop,
                frameTimestampMs = 1_000L,
                pipelineLatencyMs = 18L,
                roiConfidence = 0.76f
            )
        )

        assertEquals(21, decoded.frame.hands.single().points.size)
        assertEquals(110f / 640f, decoded.frame.hands.single().points[0].x, 0.0001f)
        assertEquals(80f / 480f, decoded.frame.hands.single().points[0].y, 0.0001f)
        assertEquals(265.5f / 640f, decoded.frame.hands.single().points[1].x, 0.0001f)
        assertEquals(275.5f / 480f, decoded.frame.hands.single().points[1].y, 0.0001f)
        assertEquals(HandSide.UNKNOWN, decoded.frame.hands.single().side)
        assertTrue(decoded.frame.hands.single().worldPoints.isEmpty())
        assertEquals(0f, decoded.frame.hands.single().points[0].z, 0f)
        assertEquals(0.76f, decoded.qualityScore, 0.0001f)
        assertEquals(18L, decoded.frame.pipelineLatencyMs)
        assertEquals(256, decoded.frame.inputWidthPx)
    }

    @Test
    fun rejectsRoiWithoutSufficientUpstreamPresenceConfidence() {
        val result = RtmposeHandOutputDecoder.decode(
            outputs(),
            HandRoiCrop(0, 0, 256, 256, 256),
            frameTimestampMs = 1L,
            roiConfidence = 0.1f
        )
        assertEquals(
            RtmposeHandDecodeRejection.ROI_NOT_CONFIDENT,
            assertRejected(result).reason
        )
    }

    @Test
    fun validatesExactSimccTensorShapesAndLengths() {
        val base = outputs()
        val badShape = base.copy(
            simccX = LiteRtFloatTensor(intArrayOf(1, 512, 21), base.simccX.values)
        )
        assertEquals(
            RtmposeHandDecodeRejection.INVALID_TENSOR_SHAPE,
            assertRejected(
                RtmposeHandOutputDecoder.decode(
                    badShape, HandRoiCrop(0, 0, 256, 256, 256), 1L, roiConfidence = 0.8f
                )
            ).reason
        )
        val badLength = base.copy(
            simccY = LiteRtFloatTensor(intArrayOf(1, 21, 512), FloatArray(21 * 512 - 1))
        )
        assertEquals(
            RtmposeHandDecodeRejection.INVALID_TENSOR_SHAPE,
            assertRejected(
                RtmposeHandOutputDecoder.decode(
                    badLength, HandRoiCrop(0, 0, 256, 256, 256), 2L, roiConfidence = 0.8f
                )
            ).reason
        )
    }

    @Test
    fun rejectsNonFiniteSimccAndInvalidMetadata() {
        val base = outputs()
        val badOutput = base.copy(
            simccY = base.simccY.copy(
                values = base.simccY.values.copyOf().also { it[42] = Float.NaN }
            )
        )
        val crop = HandRoiCrop(0, 0, 256, 256, 256)
        assertEquals(
            RtmposeHandDecodeRejection.NON_FINITE_OUTPUT,
            assertRejected(
                RtmposeHandOutputDecoder.decode(
                    badOutput, crop, 1L, roiConfidence = 0.8f
                )
            ).reason
        )
        assertEquals(
            RtmposeHandDecodeRejection.INVALID_FRAME_TIMESTAMP,
            assertRejected(
                RtmposeHandOutputDecoder.decode(
                    base, crop, 0L, roiConfidence = 0.8f
                )
            ).reason
        )
        assertEquals(
            RtmposeHandDecodeRejection.INVALID_PIPELINE_LATENCY,
            assertRejected(
                RtmposeHandOutputDecoder.decode(
                    base, crop, 1L, -1L, roiConfidence = 0.8f
                )
            ).reason
        )
        assertEquals(
            RtmposeHandDecodeRejection.INVALID_ROI_CONFIDENCE,
            assertRejected(
                RtmposeHandOutputDecoder.decode(
                    base, crop, 1L, roiConfidence = Float.NaN
                )
            ).reason
        )
    }

    private fun outputs() = RtmposeHandSimccTensors(
        simccX = LiteRtFloatTensor(
            intArrayOf(1, 21, 512),
            FloatArray(21 * 512)
        ),
        simccY = LiteRtFloatTensor(
            intArrayOf(1, 21, 512),
            FloatArray(21 * 512)
        )
    )

    private fun assertSuccess(result: RtmposeHandDecodeResult): RtmposeHandDecodeResult.Success {
        assertTrue("Expected success, got $result", result is RtmposeHandDecodeResult.Success)
        return result as RtmposeHandDecodeResult.Success
    }

    private fun assertRejected(result: RtmposeHandDecodeResult): RtmposeHandDecodeResult.Rejected {
        assertTrue("Expected rejection, got $result", result is RtmposeHandDecodeResult.Rejected)
        return result as RtmposeHandDecodeResult.Rejected
    }
}
