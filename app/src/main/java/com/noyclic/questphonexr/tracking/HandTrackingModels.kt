package com.noyclic.questphonexr.tracking

import kotlin.math.exp
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

/**
 * Normalizes thumb/index distance by palm width and filters pinch state with hysteresis.
 * When source timestamps are available, smoothing is time-based so the same gesture has
 * comparable response at 30 and 60 inference samples per second.
 */
class PinchEstimator(
    private val smoothing: Float = 0.55f,
    private val pinchOnThreshold: Float = 0.72f,
    private val pinchOffThreshold: Float = 0.48f,
    private val timeConstantSeconds: Float = DEFAULT_TIME_CONSTANT_SECONDS,
    /** MediaPipe image-landmark z is approximate; partial weighting avoids over-trusting it. */
    private val depthWeight: Float = DEFAULT_DEPTH_WEIGHT
) {
    private var filteredStrength = 0f
    private var isPinching = false
    private var previousTimestampMs = 0L

    init {
        require(smoothing in 0f..1f) { "Smoothing must be in 0..1." }
        require(pinchOnThreshold in 0f..1f && pinchOffThreshold in 0f..1f)
        require(pinchOffThreshold <= pinchOnThreshold) {
            "Pinch release threshold must not exceed the press threshold."
        }
        require(timeConstantSeconds.isFinite() && timeConstantSeconds > 0f)
        require(depthWeight.isFinite() && depthWeight in 0f..1f)
    }

    fun update(points: List<HandPoint>, timestampMs: Long = 0L): PinchSignal {
        // Ignore duplicate/out-of-order inference callbacks instead of rewinding gesture state.
        if (timestampMs > 0L && previousTimestampMs > 0L &&
            timestampMs <= previousTimestampMs
        ) return signal()

        val thumb = points.getOrNull(4)
        val index = points.getOrNull(8)
        val indexMcp = points.getOrNull(5)
        val pinkyMcp = points.getOrNull(17)
        val required = listOfNotNull(thumb, index, indexMcp, pinkyMcp)
        if (required.size != 4 || required.any {
                !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite()
            }) {
            return resetForInvalidSample(timestampMs)
        }

        // Both the palm scale and fingertip gap include a conservative fraction of MediaPipe
        // image-space depth. This distinguishes fingers that overlap in 2D but sit at
        // different camera depths, without treating the approximate z channel as metric.
        val palmWidth = distance3d(indexMcp!!, pinkyMcp!!)
        if (!palmWidth.isFinite() || palmWidth < MIN_PALM_WIDTH) {
            return resetForInvalidSample(timestampMs)
        }
        val normalizedPinchDistance = distance3d(thumb!!, index!!) / palmWidth
        if (!normalizedPinchDistance.isFinite()) return resetForInvalidSample(timestampMs)

        val rawStrength = ((OPEN_RATIO - normalizedPinchDistance) / RATIO_RANGE)
            .coerceIn(0f, 1f)
        val alpha = smoothingAlpha(timestampMs)
        filteredStrength += (rawStrength - filteredStrength) * alpha
        if (!filteredStrength.isFinite()) return resetForInvalidSample(timestampMs)

        if (!isPinching && filteredStrength >= pinchOnThreshold) {
            isPinching = true
        } else if (isPinching && filteredStrength <= pinchOffThreshold) {
            isPinching = false
        }
        if (timestampMs > 0L) previousTimestampMs = timestampMs
        return signal()
    }

    fun reset() {
        filteredStrength = 0f
        isPinching = false
        previousTimestampMs = 0L
    }

    private fun resetForInvalidSample(timestampMs: Long): PinchSignal {
        reset()
        if (timestampMs > 0L) previousTimestampMs = timestampMs
        return signal()
    }

    private fun smoothingAlpha(timestampMs: Long): Float {
        if (timestampMs <= 0L || previousTimestampMs <= 0L) return smoothing
        val elapsedMs = (timestampMs - previousTimestampMs).coerceAtLeast(0L)
            .coerceAtMost(MAX_FILTER_GAP_MS)
        if (elapsedMs == 0L) return 0f
        val elapsedSeconds = elapsedMs / 1_000f
        return (1f - exp(-elapsedSeconds / timeConstantSeconds)).coerceIn(0f, 1f)
    }

    private fun signal() = PinchSignal(filteredStrength, isPinching)

    private fun distance3d(a: HandPoint, b: HandPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = (a.z - b.z) * depthWeight
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private companion object {
        const val MIN_PALM_WIDTH = 0.008f
        const val DEFAULT_DEPTH_WEIGHT = 0.60f
        const val OPEN_RATIO = 0.72f
        const val RATIO_RANGE = 0.52f
        const val DEFAULT_TIME_CONSTANT_SECONDS = 0.045f
        const val MAX_FILTER_GAP_MS = 250L
    }
}

data class TrackedHand(
    val points: List<HandPoint>,
    val pinchStrength: Float = 0f,
    val isPinching: Boolean = false,
    val side: HandSide = HandSide.UNKNOWN,
    /** MediaPipe world landmarks are hand-local 3D points measured in meters. */
    val worldPoints: List<HandPoint> = emptyList()
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
