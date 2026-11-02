package com.noyclic.questphonexr.tracking

/**
 * Device-independent data consumed by the future sandbox.
 * x/y are normalized image coordinates; z uses MediaPipe image-relative units.
 */
data class HandPoint(val x: Float, val y: Float, val z: Float)

data class TrackedHand(val points: List<HandPoint>) {
    val thumbTip: HandPoint? get() = points.getOrNull(4)
    val indexTip: HandPoint? get() = points.getOrNull(8)
}

data class HandTrackingFrame(val timestampMs: Long, val hands: List<TrackedHand>) {
    val handCount: Int get() = hands.size
}
