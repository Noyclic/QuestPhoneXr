package com.noyclic.questphonexr.tracking

/**
 * Minimal asynchronous engine contract used by the top-down ROI refinement coordinator.
 * Implementations must allow only one outstanding input lease and release it after callback.
 */
interface RtmposeHandInferenceEngine {
    fun acquireInputBuffer(): FloatArray?
    fun discardInputBuffer()
    fun submit(
        crop: HandRoiCrop,
        frameTimestampMs: Long,
        roiConfidence: Float,
        callback: (Result<RtmposeInferenceResult>) -> Unit
    )
}
