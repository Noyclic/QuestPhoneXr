package com.noyclic.questphonexr.tracking

/**
 * One Float tensor read from LiteRT. Call TensorBuffer.readFloat() before constructing this
 * value so quantized model outputs are dequantized with the model's tensor quantization params.
 */
data class LiteRtFloatTensor(
    val shape: IntArray,
    val values: FloatArray
)

/**
 * The four named output tensors from ST's hand_spe model.
 *
 * Keep output names semantic rather than depending on the order returned by CompiledModel:
 * hand type, normalized geometry, presence probability, and image-space geometry.
 */
data class StHandLandmarksTensors(
    val handType: LiteRtFloatTensor,
    val normalizedLandmarks: LiteRtFloatTensor,
    val handPresence: LiteRtFloatTensor,
    val imageLandmarks: LiteRtFloatTensor
)

enum class StHandDecodeRejection {
    INVALID_MODEL_INPUT_SIZE,
    INVALID_FRAME_TIMESTAMP,
    INVALID_PIPELINE_LATENCY,
    INVALID_TENSOR_SHAPE,
    INVALID_TENSOR_LENGTH,
    NON_FINITE_OUTPUT,
    INVALID_HAND_TYPE,
    INVALID_HAND_PRESENCE,
    NO_HAND_PRESENT,
    INVALID_LANDMARK_COORDINATE
}

sealed class StHandLandmarksDecodeResult {
    data class Success(
        val hand: TrackedHand,
        /** Presence probability is the calibrated quality signal exposed by this model. */
        val qualityScore: Float,
        val handednessConfidence: Float
    ) : StHandLandmarksDecodeResult()

    data class Rejected(val reason: StHandDecodeRejection) : StHandLandmarksDecodeResult()
}

/**
 * Decodes the four-output ST hand_spe model contract into EndXR's common 21-point hand.
 *
 * The model is top-down: imageLandmarks are expressed in model-crop pixels and must be mapped
 * through the exact square ROI that produced the inference input. This adapter deliberately
 * emits no worldPoints because this model does not provide calibrated hand-local meters.
 *
 * The normalizedLandmarks tensor is checked for structural/finite validity because ST's
 * reference postprocessor uses it as an intermediate geometry output. The reference pipeline
 * returns imageLandmarks as the coordinates used to draw the hand in input-image space.
 */
object StHandLandmarksOutputDecoder {
    const val LANDMARK_COUNT = 21
    const val VALUES_PER_LANDMARK = 3
    const val LANDMARK_VALUE_COUNT = LANDMARK_COUNT * VALUES_PER_LANDMARK
    const val DEFAULT_MINIMUM_PRESENCE = 0.15f
    const val DEFAULT_HANDEDNESS_DEAD_ZONE = 0.10f

    fun decode(
        outputs: StHandLandmarksTensors,
        crop: HandRoiCrop,
        modelInputWidthPx: Int,
        modelInputHeightPx: Int,
        frameTimestampMs: Long,
        pipelineLatencyMs: Long = 0L,
        minimumPresence: Float = DEFAULT_MINIMUM_PRESENCE,
        handednessDeadZone: Float = DEFAULT_HANDEDNESS_DEAD_ZONE
    ): StHandLandmarksDecodeResult {
        if (modelInputWidthPx <= 0 || modelInputHeightPx <= 0) {
            return reject(StHandDecodeRejection.INVALID_MODEL_INPUT_SIZE)
        }
        if (frameTimestampMs <= 0L) {
            return reject(StHandDecodeRejection.INVALID_FRAME_TIMESTAMP)
        }
        if (pipelineLatencyMs < 0L) {
            return reject(StHandDecodeRejection.INVALID_PIPELINE_LATENCY)
        }
        if (!minimumPresence.isFinite() || minimumPresence !in 0f..1f ||
            !handednessDeadZone.isFinite() || handednessDeadZone !in 0f..0.49f
        ) {
            return reject(StHandDecodeRejection.INVALID_HAND_PRESENCE)
        }

        if (!outputs.handType.hasShape(1, 1) ||
            !outputs.handPresence.hasShape(1, 1) ||
            !outputs.normalizedLandmarks.hasShape(1, LANDMARK_VALUE_COUNT) ||
            !outputs.imageLandmarks.hasShape(1, LANDMARK_VALUE_COUNT)
        ) {
            return reject(StHandDecodeRejection.INVALID_TENSOR_SHAPE)
        }
        if (!outputs.handType.values.all(Float::isFinite) ||
            !outputs.handPresence.values.all(Float::isFinite) ||
            !outputs.normalizedLandmarks.values.all(Float::isFinite) ||
            !outputs.imageLandmarks.values.all(Float::isFinite)
        ) {
            return reject(StHandDecodeRejection.NON_FINITE_OUTPUT)
        }

        val handType = outputs.handType.values[0]
        val handPresence = outputs.handPresence.values[0]
        if (handType !in 0f..1f) return reject(StHandDecodeRejection.INVALID_HAND_TYPE)
        if (handPresence !in 0f..1f) return reject(StHandDecodeRejection.INVALID_HAND_PRESENCE)
        if (handPresence < minimumPresence) return reject(StHandDecodeRejection.NO_HAND_PRESENT)

        val modelWidth = modelInputWidthPx.toFloat()
        val modelHeight = modelInputHeightPx.toFloat()
        val maxDimension = maxOf(modelInputWidthPx, modelInputHeightPx).toFloat()
        val points = ArrayList<HandPoint>(LANDMARK_COUNT)
        for (index in 0 until LANDMARK_COUNT) {
            val offset = index * VALUES_PER_LANDMARK
            val modelX = outputs.imageLandmarks.values[offset]
            val modelY = outputs.imageLandmarks.values[offset + 1]
            val modelZ = outputs.imageLandmarks.values[offset + 2]

            // Keep a modest border around the crop for fingertips which cross its edge,
            // but reject grossly corrupt output before it can enter the shared input path.
            if (modelX !in -modelWidth * 0.5f..modelWidth * 1.5f ||
                modelY !in -modelHeight * 0.5f..modelHeight * 1.5f ||
                modelZ !in -maxDimension * 2f..maxDimension * 2f
            ) {
                return reject(StHandDecodeRejection.INVALID_LANDMARK_COORDINATE)
            }
            val source = crop.toSourceNormalized(modelX / modelWidth, modelY / modelHeight)
                ?: return reject(StHandDecodeRejection.INVALID_LANDMARK_COORDINATE)
            points += HandPoint(
                x = source.first,
                y = source.second,
                z = modelZ / maxDimension
            )
        }

        val sideConfidence = (kotlin.math.abs(handType - 0.5f) * 2f).coerceIn(0f, 1f)
        val side = when {
            handType < 0.5f - handednessDeadZone -> HandSide.LEFT
            handType > 0.5f + handednessDeadZone -> HandSide.RIGHT
            else -> HandSide.UNKNOWN
        }

        return StHandLandmarksDecodeResult.Success(
            hand = TrackedHand(
                points = points,
                side = side,
                rawPoints = points
            ),
            qualityScore = handPresence,
            handednessConfidence = sideConfidence
        )
    }

    private fun LiteRtFloatTensor.hasShape(vararg expected: Int): Boolean =
        shape.contentEquals(expected) && values.size == expected.fold(1) { acc, dim ->
            acc * dim
        }

    private fun reject(reason: StHandDecodeRejection) =
        StHandLandmarksDecodeResult.Rejected(reason)
}
