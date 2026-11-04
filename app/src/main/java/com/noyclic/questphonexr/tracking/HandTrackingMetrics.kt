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
    val lastFramePixelStride: Int = 0
) {
    val droppedFrames: Long
        get() = droppedBusyFrames + droppedIntervalFrames
}
