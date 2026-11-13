package com.noyclic.questphonexr.tracking

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Refines MediaPipe's already-detected hands with RTMPose top-down crops.
 *
 * MediaPipe remains the full-frame detector and source of handedness, hand-local world points
 * and pinch state. RTMPose only replaces image landmarks for crops it successfully refines.
 * A missed crop or inference error leaves that hand's MediaPipe landmarks unchanged.
 *
 * The raw camera ARGB buffer is copied into a reusable private snapshot before returning, so the
 * analyzer can safely reuse its Bitmap/pixel storage while asynchronous GPU inference proceeds.
 * One camera frame is refined at a time; newer frames are rejected while the previous one is in
 * flight rather than queuing stale camera work.
 */
class RtmposeHandRefinementCoordinator(
    private val engine: RtmposeHandInferenceEngine,
    private val roiConfidence: Float = DEFAULT_DETECTOR_CONFIDENCE,
    private val minimumCropPx: Int = HandRoiCrop.DEFAULT_MINIMUM_CROP_PX
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    private var sourcePixelSnapshot = IntArray(0)

    init {
        require(roiConfidence.isFinite() && roiConfidence in 0f..1f)
        require(minimumCropPx > 0)
    }

    /**
     * Starts sequential one-crop-at-a-time refinement.
     *
     * [sourceArgb] is the raw, unrotated CameraX ARGB_8888 buffer. [sourceRotationDegrees] is
     * CameraX's right-angle rotation. MediaPipe landmarks are already in the upright,
     * unmirrored coordinate frame, so the crop is constructed in the rotated dimensions and
     * the preprocessor maps those pixels back to the raw buffer.
     *
     * Returns false when closed, already processing a frame, or supplied invalid source data.
     * The callback is invoked once after all hands have been refined or safely skipped.
     */
    fun refine(
        frame: HandTrackingFrame,
        sourceArgb: IntArray,
        rawWidthPx: Int,
        rawHeightPx: Int,
        sourceRotationDegrees: Int,
        callback: (Result<RtmposeRefinementOutcome>) -> Unit
    ): Boolean {
        if (closed.get() || !inFlight.compareAndSet(false, true)) return false

        val rotation = normalizeRightAngleRotation(sourceRotationDegrees) ?: run {
            inFlight.set(false)
            return false
        }
        if (rawWidthPx <= 0 || rawHeightPx <= 0 ||
            sourceArgb.size.toLong() < rawWidthPx.toLong() * rawHeightPx.toLong() ||
            frame.timestampMs <= 0L
        ) {
            inFlight.set(false)
            return false
        }

        val rotatedWidth = if (rotation == 90 || rotation == 270) rawHeightPx else rawWidthPx
        val rotatedHeight = if (rotation == 90 || rotation == 270) rawWidthPx else rawHeightPx
        val pixelCount = rawWidthPx * rawHeightPx
        if (sourcePixelSnapshot.size != pixelCount) {
            sourcePixelSnapshot = IntArray(pixelCount)
        }
        sourceArgb.copyInto(sourcePixelSnapshot, endIndex = pixelCount)

        val outputHands = frame.hands.toMutableList()
        val pendingHands = frame.hands.indices
        var nextHandIndex = 0
        var refinedCount = 0
        var skippedCount = 0
        var additionalLatencyMs = 0L
        var finished = false

        fun finish() {
            if (finished) return
            finished = true
            inFlight.set(false)
            val refinedFrame = frame.copy(
                hands = outputHands.toList(),
                pipelineLatencyMs = frame.pipelineLatencyMs + additionalLatencyMs
            )
            runCatching {
                callback(
                    Result.success(
                        RtmposeRefinementOutcome(
                            frame = refinedFrame,
                            refinedHands = refinedCount,
                            skippedHands = skippedCount,
                            roiQualityScore = if (refinedCount > 0) roiConfidence else null,
                            addedInferenceLatencyMs = additionalLatencyMs
                        )
                    )
                )
            }
        }

        fun processNext() {
            if (finished) return
            if (closed.get()) {
                finish()
                return
            }
            if (nextHandIndex >= pendingHands.count()) {
                finish()
                return
            }

            val handIndex = pendingHands[nextHandIndex++]
            val originalHand = frame.hands[handIndex]
            val crop = HandRoiCrop.fromLandmarks(
                points = originalHand.points,
                sourceWidthPx = rotatedWidth,
                sourceHeightPx = rotatedHeight,
                minimumCropPx = minimumCropPx
            )
            if (crop == null) {
                skippedCount += 1
                processNext()
                return
            }

            val input = engine.acquireInputBuffer()
            if (input == null) {
                skippedCount += 1
                processNext()
                return
            }
            val prepared = HandRoiRgbPreprocessor.writeRtmposeImageNetNchwFloat(
                sourceArgb = sourcePixelSnapshot,
                crop = crop,
                outputSizePx = RtmposeLiteRtRunner.INPUT_SIZE_PX,
                destinationNchw = input,
                sourceRotationDegrees = rotation
            )
            if (!prepared) {
                engine.discardInputBuffer()
                skippedCount += 1
                processNext()
                return
            }

            try {
                engine.submit(
                    crop = crop,
                    frameTimestampMs = frame.timestampMs,
                    roiConfidence = roiConfidence
                ) { result ->
                    result.fold(
                        onSuccess = { inference ->
                            val decoded = inference.decoded
                            if (decoded is RtmposeHandDecodeResult.Success) {
                                val refinedHand = decoded.frame.hands.singleOrNull()
                                if (refinedHand != null && refinedHand.points.size == RtmposeHandOutputDecoder.LANDMARK_COUNT) {
                                    // Retain MediaPipe-only semantics and gesture state. RTMPose
                                    // has no reliable depth, handedness or pinch output of its own.
                                    outputHands[handIndex] = originalHand.copy(
                                        points = refinedHand.points,
                                        rawPoints = refinedHand.points
                                    )
                                    refinedCount += 1
                                    additionalLatencyMs += inference.endToEndInferenceLatencyMs
                                } else {
                                    skippedCount += 1
                                }
                            } else {
                                skippedCount += 1
                            }
                        },
                        onFailure = {
                            skippedCount += 1
                        }
                    )
                    processNext()
                }
            } catch (_: Exception) {
                engine.discardInputBuffer()
                skippedCount += 1
                processNext()
            }
        }

        processNext()
        return true
    }

    override fun close() {
        closed.set(true)
    }

    companion object {
        /** Neutral confirmed-detector baseline; not a calibrated model confidence. */
        const val DEFAULT_DETECTOR_CONFIDENCE = 0.5f
    }
}

data class RtmposeRefinementOutcome(
    val frame: HandTrackingFrame,
    val refinedHands: Int,
    val skippedHands: Int,
    /** Nullable when no hand was successfully refined. */
    val roiQualityScore: Float?,
    val addedInferenceLatencyMs: Long
)
