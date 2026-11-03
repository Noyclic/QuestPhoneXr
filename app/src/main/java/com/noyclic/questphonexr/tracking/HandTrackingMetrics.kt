package com.noyclic.questphonexr.tracking

/** Cumulative camera-to-inference counters for the in-app performance panel. */
data class HandTrackingMetricsSnapshot(
    val receivedFrames: Long,
    val submittedFrames: Long,
    val completedFrames: Long,
    val droppedBusyFrames: Long,
    val droppedIntervalFrames: Long,
    val lastPreprocessingMs: Long,
    val averagePreprocessingMs: Float
) {
    val droppedFrames: Long
        get() = droppedBusyFrames + droppedIntervalFrames
}
