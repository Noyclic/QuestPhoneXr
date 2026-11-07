package com.noyclic.questphonexr.tracking

import kotlin.math.sqrt

/** Device-independent hand data. Image x/y are normalized; z is image-relative. */
data class HandPoint(val x: Float, val y: Float, val z: Float)

enum class HandSide {
    LEFT,
    RIGHT,
    UNKNOWN;

    fun opposite(): HandSide = when (this) {
        LEFT -> RIGHT
        RIGHT -> LEFT
        UNKNOWN -> UNKNOWN
    }
}

data class PinchSignal(val strength: Float, val isPinching: Boolean)

/** Normalizes thumb/index distance by palm width and applies time-aware hysteresis. */
class PinchEstimator(
    private val smoothing: Float = 0.55f,
    private val pinchOnThreshold: Float = 0.72f,
    private val pinchOffThreshold: Float = 0.48f
) {
    private var filteredStrength = 0f
    private var isPinching = false
    private var lastTimestampMs: Long? = null

    init {
        require(smoothing > 0f && smoothing <= 1f && smoothing.isFinite())
        require(pinchOnThreshold in 0f..1f && pinchOffThreshold in 0f..1f)
        require(pinchOffThreshold < pinchOnThreshold)
    }

    fun update(points: List<HandPoint>, timestampMs: Long? = null): PinchSignal {
        val thumb = points.getOrNull(4)
        val index = points.getOrNull(8)
        val indexMcp = points.getOrNull(5)
        val pinkyMcp = points.getOrNull(17)
        if (thumb == null || index == null || indexMcp == null || pinkyMcp == null ||
            listOf(thumb, index, indexMcp, pinkyMcp).any {
                !it.x.isFinite() || !it.y.isFinite()
            }
        ) {
            reset()
            return PinchSignal(0f, false)
        }

        val palmWidth = distance(indexMcp, pinkyMcp).coerceAtLeast(MIN_PALM_WIDTH)
        val normalizedPinchDistance = distance(thumb, index) / palmWidth
        val rawStrength = ((OPEN_RATIO - normalizedPinchDistance) / RATIO_RANGE).coerceIn(0f, 1f)
        val alpha = smoothingAlpha(timestampMs)
        filteredStrength += (rawStrength - filteredStrength) * alpha

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
        lastTimestampMs = null
    }

    private fun smoothingAlpha(timestampMs: Long?): Float {
        if (timestampMs == null) return smoothing
        val previous = lastTimestampMs
        lastTimestampMs = timestampMs
        if (previous == null || timestampMs <= previous) return smoothing
        val elapsedMs = (timestampMs - previous).coerceIn(1L, MAX_PINCH_SAMPLE_GAP_MS)
        if (smoothing >= 1f) return 1f
        val timeConstantMs = -REFERENCE_INTERVAL_MS / kotlin.math.ln(1.0 - smoothing.toDouble())
        return (1.0 - kotlin.math.exp(-elapsedMs.toDouble() / timeConstantMs))
            .toFloat()
            .coerceIn(0f, 1f)
    }

    private fun distance(a: HandPoint, b: HandPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        const val MIN_PALM_WIDTH = 0.015f
        const val OPEN_RATIO = 0.72f
        const val RATIO_RANGE = 0.52f
        const val REFERENCE_INTERVAL_MS = 16.667
        const val MAX_PINCH_SAMPLE_GAP_MS = 200L
    }
}

data class TrackedHand(
    val points: List<HandPoint>,
    val pinchStrength: Float = 0f,
    val isPinching: Boolean = false,
    val side: HandSide = HandSide.UNKNOWN,
    /** MediaPipe world landmarks are hand-local 3D points measured in meters. */
    val worldPoints: List<HandPoint> = emptyList(),
    /** Stable ID assigned by temporal hand association, zero for externally constructed samples. */
    val trackingId: Long = 0L
) {
    val thumbTip: HandPoint? get() = points.getOrNull(4)
    val indexTip: HandPoint? get() = points.getOrNull(8)
}

data class HandTrackingFrame(
    val timestampMs: Long,
    val hands: List<TrackedHand>,
    /** Camera conversion + MediaPipe callback latency, not model-only execution time. */
    val pipelineLatencyMs: Long = 0L,
    val sourceWidthPx: Int = 0,
    val sourceHeightPx: Int = 0,
    val inputWidthPx: Int = 0,
    val inputHeightPx: Int = 0,
    /** Time spent copying, orienting and letterboxing the accepted camera frame. */
    val preprocessingLatencyMs: Long = 0L
) {
    val handCount: Int get() = hands.size
}
