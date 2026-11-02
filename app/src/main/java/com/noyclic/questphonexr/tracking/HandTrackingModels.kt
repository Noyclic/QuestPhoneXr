package com.noyclic.questphonexr.tracking

import kotlin.math.hypot

/** Device-independent hand data. x/y are normalized image coordinates; z is image-relative. */
data class HandPoint(val x: Float, val y: Float, val z: Float)

data class PinchSignal(val strength: Float, val isPinching: Boolean)

/** Normalizes thumb/index distance by palm width and filters pinch state with hysteresis. */
class PinchEstimator(
    private val smoothing: Float = 0.55f,
    private val pinchOnThreshold: Float = 0.72f,
    private val pinchOffThreshold: Float = 0.48f
) {
    private var filteredStrength = 0f
    private var isPinching = false

    fun update(points: List<HandPoint>): PinchSignal {
        val thumb = points.getOrNull(4)
        val index = points.getOrNull(8)
        val indexMcp = points.getOrNull(5)
        val pinkyMcp = points.getOrNull(17)
        if (thumb == null || index == null || indexMcp == null || pinkyMcp == null) {
            return PinchSignal(filteredStrength, isPinching)
        }

        val palmWidth = distance(indexMcp, pinkyMcp).coerceAtLeast(MIN_PALM_WIDTH)
        val normalizedPinchDistance = distance(thumb, index) / palmWidth
        val rawStrength = ((OPEN_RATIO - normalizedPinchDistance) / RATIO_RANGE).coerceIn(0f, 1f)
        filteredStrength += (rawStrength - filteredStrength) * smoothing

        if (!isPinching && filteredStrength >= pinchOnThreshold) {
            isPinching = true
        } else if (isPinching && filteredStrength <= pinchOffThreshold) {
            isPinching = false
        }
        return PinchSignal(filteredStrength, isPinching)
    }

    fun reset() {
        filteredStrength = 0f
        isPinching = false
    }

    private fun distance(a: HandPoint, b: HandPoint): Float = hypot(a.x - b.x, a.y - b.y)

    private companion object {
        const val MIN_PALM_WIDTH = 0.015f
        const val OPEN_RATIO = 0.72f
        const val RATIO_RANGE = 0.52f
    }
}

data class TrackedHand(
    val points: List<HandPoint>,
    val pinchStrength: Float = 0f,
    val isPinching: Boolean = false
) {
    val thumbTip: HandPoint? get() = points.getOrNull(4)
    val indexTip: HandPoint? get() = points.getOrNull(8)
}

data class HandTrackingFrame(val timestampMs: Long, val hands: List<TrackedHand>) {
    val handCount: Int get() = hands.size
}
