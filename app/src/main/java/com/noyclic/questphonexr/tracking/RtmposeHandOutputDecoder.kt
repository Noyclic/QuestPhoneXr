package com.noyclic.questphonexr.tracking

/**
 * RTMPose-Hand SimCC model outputs. The model emits independent X and Y distributions
 * with one 512-bin axis per each of the 21 hand keypoints.
 */
data class RtmposeHandSimccTensors(
    val simccX: LiteRtFloatTensor,
    val simccY: LiteRtFloatTensor
)

enum class RtmposeHandDecodeRejection {
    INVALID_FRAME_TIMESTAMP,
    INVALID_PIPELINE_LATENCY,
    INVALID_ROI_CONFIDENCE,
    ROI_NOT_CONFIDENT,
    INVALID_TENSOR_SHAPE,
    NON_FINITE_OUTPUT
}

sealed class RtmposeHandDecodeResult {
    data class Success(
        val frame: HandTrackingFrame,
        /**
         * RTMPose-Hand has no hand-presence or handedness output. This score is the
         * caller-supplied detector/ROI confidence, not a confidence inferred from SimCC.
         */
        val qualityScore: Float
    ) : RtmposeHandDecodeResult()

    data class Rejected(val reason: RtmposeHandDecodeRejection) : RtmposeHandDecodeResult()
}

/**
 * Decodes RTMPose-Hand-LiteRT's two SimCC output tensors into the shared EndXR hand frame.
 *
 * The model is top-down and predicts exactly one centered hand. A valid crop must therefore
 * come from an upstream detector/track association. The upstream confidence is required and
 * gates publication; a high-confidence model argmax alone does not prove a hand is present.
 *
 * SimCC coordinate = argmax(bin) / 2.0, in 256x256 model-input pixels. The 21-point order is
 * wrist followed by the four joints for thumb, index, middle, ring and little finger.
 */
object RtmposeHandOutputDecoder {
    const val MODEL_INPUT_SIZE_PX = 256
    const val LANDMARK_COUNT = 21
    const val SIMCC_BIN_COUNT = 512
    const val SIMCC_SPLIT_RATIO = 2f
    const val DEFAULT_MINIMUM_ROI_CONFIDENCE = 0.15f

    fun decode(
        outputs: RtmposeHandSimccTensors,
        crop: HandRoiCrop,
        frameTimestampMs: Long,
        pipelineLatencyMs: Long = 0L,
        roiConfidence: Float,
        minimumRoiConfidence: Float = DEFAULT_MINIMUM_ROI_CONFIDENCE
    ): RtmposeHandDecodeResult {
        if (frameTimestampMs <= 0L) {
            return reject(RtmposeHandDecodeRejection.INVALID_FRAME_TIMESTAMP)
        }
        if (pipelineLatencyMs < 0L) {
            return reject(RtmposeHandDecodeRejection.INVALID_PIPELINE_LATENCY)
        }
        if (!roiConfidence.isFinite() || roiConfidence !in 0f..1f ||
            !minimumRoiConfidence.isFinite() || minimumRoiConfidence !in 0f..1f
        ) {
            return reject(RtmposeHandDecodeRejection.INVALID_ROI_CONFIDENCE)
        }
        if (roiConfidence < minimumRoiConfidence) {
            return reject(RtmposeHandDecodeRejection.ROI_NOT_CONFIDENT)
        }
        if (!outputs.simccX.hasExpectedShape() || !outputs.simccY.hasExpectedShape()) {
            return reject(RtmposeHandDecodeRejection.INVALID_TENSOR_SHAPE)
        }
        if (!outputs.simccX.values.all(Float::isFinite) ||
            !outputs.simccY.values.all(Float::isFinite)
        ) {
            return reject(RtmposeHandDecodeRejection.NON_FINITE_OUTPUT)
        }

        val points = ArrayList<HandPoint>(LANDMARK_COUNT)
        for (landmarkIndex in 0 until LANDMARK_COUNT) {
            val xBin = argMax(
                outputs.simccX.values,
                landmarkIndex * SIMCC_BIN_COUNT,
                SIMCC_BIN_COUNT
            )
            val yBin = argMax(
                outputs.simccY.values,
                landmarkIndex * SIMCC_BIN_COUNT,
                SIMCC_BIN_COUNT
            )
            val modelX = xBin / SIMCC_SPLIT_RATIO
            val modelY = yBin / SIMCC_SPLIT_RATIO
            val source = crop.toSourceNormalized(
                modelX / MODEL_INPUT_SIZE_PX,
                modelY / MODEL_INPUT_SIZE_PX
            ) ?: return reject(RtmposeHandDecodeRejection.INVALID_TENSOR_SHAPE)

            points += HandPoint(
                x = source.first,
                y = source.second,
                // The 2D RTMPose model has no depth/world output.
                z = 0f
            )
        }

        val hand = TrackedHand(
            points = points,
            side = HandSide.UNKNOWN,
            rawPoints = points
        )
        return RtmposeHandDecodeResult.Success(
            frame = HandTrackingFrame(
                timestampMs = frameTimestampMs,
                hands = listOf(hand),
                pipelineLatencyMs = pipelineLatencyMs,
                sourceWidthPx = crop.sourceWidthPx,
                sourceHeightPx = crop.sourceHeightPx,
                inputWidthPx = MODEL_INPUT_SIZE_PX,
                inputHeightPx = MODEL_INPUT_SIZE_PX
            ),
            qualityScore = roiConfidence
        )
    }

    private fun LiteRtFloatTensor.hasExpectedShape(): Boolean =
        shape.contentEquals(intArrayOf(1, LANDMARK_COUNT, SIMCC_BIN_COUNT)) &&
            values.size == LANDMARK_COUNT * SIMCC_BIN_COUNT

    private fun argMax(values: FloatArray, startIndex: Int, count: Int): Int {
        var bestIndex = 0
        var bestValue = values[startIndex]
        for (index in 1 until count) {
            val candidate = values[startIndex + index]
            if (candidate > bestValue) {
                bestValue = candidate
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun reject(reason: RtmposeHandDecodeRejection) =
        RtmposeHandDecodeResult.Rejected(reason)
}
