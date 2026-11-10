package com.noyclic.questphonexr.tracking

/** Cumulative camera-to-inference counters for the in-app performance panel. */
data class HandTrackingMetricsSnapshot(
    val receivedFrames: Long,
    val submittedFrames: Long,
    val completedFrames: Long,
    val droppedBusyFrames: Long,
    val droppedIntervalFrames: Long,
    val lastPreprocessingMs: Long,
    val averagePreprocessingMs: Float,
    val lastFrameWidthPx: Int = 0,
    val lastFrameHeightPx: Int = 0,
    val lastFrameFormat: Int = 0,
    val lastFrameRotationDegrees: Int = 0,
    val lastFrameRowStride: Int = 0,
    val lastFramePixelStride: Int = 0,
    val lastFrameLumaMean: Float = 0f,
    val lastFrameLumaStdDev: Float = 0f,
    /** Async MediaPipe callback accounting, including late/unmatched callbacks. */
    val callbackCount: Long = 0L,
    val matchedCallbackCount: Long = 0L,
    val unmatchedCallbackCount: Long = 0L,
    val lastSubmittedTimestampMs: Long = 0L,
    val lastCallbackTimestampMs: Long = 0L,
    val lastCallbackImageWasSeparateWrapper: Boolean = false,
    /** Approximate MediaPipe inference time, excluding conversion and result mapping. */
    val lastInferenceMs: Long = 0L,
    val averageInferenceMs: Float = 0f,
    /** Time spent converting landmarks, associating hands and updating gesture state. */
    val lastResultProcessingMs: Long = 0L,
    val averageResultProcessingMs: Float = 0f,
    val delegateName: String = "CPU",
    val delegateInitThreadId: Long = 0L,
    val lastAnalyzeThreadId: Long = 0L,
    val delegateThreadMatchesAnalyzer: Boolean = false
) {
    val droppedFrames: Long
        get() = droppedBusyFrames + droppedIntervalFrames
}
