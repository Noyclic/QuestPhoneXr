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

data class PinchSignal(
    val strength: Float,
    val isPinching: Boolean,
    /** Raw thumb/index distance divided by palm width; infinity means unavailable. */
    val normalizedDistanceRatio: Float = Float.POSITIVE_INFINITY,
    /** True when the ratio came from MediaPipe's hand-local 3D world landmarks. */
    val usedWorldLandmarks: Boolean = false
)

/**
 * Measures thumb/index closure relative to palm width.
 *
 * Gesture input should be the newest raw MediaPipe image landmarks. The visual hand mesh
 * may be One Euro filtered separately; filtering both the mesh and this signal compounds
 * fingertip lag and can prevent a quick pinch from crossing the activation threshold.
 */
class PinchEstimator(
    private val smoothing: Float = 0.55f,
    private val pinchOnThreshold: Float = 0.62f,
    private val pinchOffThreshold: Float = 0.40f
) {
    private var filteredStrength = 0f
    private var isPinching = false
    private var lastTimestampMs: Long? = null

    init {
        require(smoothing > 0f && smoothing <= 1f && smoothing.isFinite())
        require(pinchOnThreshold in 0f..1f && pinchOffThreshold in 0f..1f)
        require(pinchOffThreshold < pinchOnThreshold)
    }

    fun update(
        points: List<HandPoint>,
        timestampMs: Long? = null,
        worldPoints: List<HandPoint> = emptyList()
    ): PinchSignal {
        val thumb = points.getOrNull(4)
        val index = points.getOrNull(8)
        val indexMcp = points.getOrNull(5)
        val pinkyMcp = points.getOrNull(17)
        if (thumb == null || index == null || indexMcp == null || pinkyMcp == null ||
            !thumb.x.isFinite() || !thumb.y.isFinite() ||
            !index.x.isFinite() || !index.y.isFinite() ||
            !indexMcp.x.isFinite() || !indexMcp.y.isFinite() ||
            !pinkyMcp.x.isFinite() || !pinkyMcp.y.isFinite()
        ) {
            reset()
            return PinchSignal(0f, false)
        }

        // A 2D camera projection compresses the apparent palm width when the hand
        // turns edge-on. Prefer MediaPipe's hand-local 3D distances when its palm
        // geometry is complete and physically plausible; fall back to image space
        // for models/frames that do not provide usable world landmarks.
        val worldThumb = worldPoints.getOrNull(4)
        val worldIndex = worldPoints.getOrNull(8)
        val worldIndexMcp = worldPoints.getOrNull(5)
        val worldPinkyMcp = worldPoints.getOrNull(17)
        val hasFiniteWorldGeometry =
            worldThumb != null && worldIndex != null &&
                worldIndexMcp != null && worldPinkyMcp != null &&
                worldThumb.x.isFinite() && worldThumb.y.isFinite() && worldThumb.z.isFinite() &&
                worldIndex.x.isFinite() && worldIndex.y.isFinite() && worldIndex.z.isFinite() &&
                worldIndexMcp.x.isFinite() && worldIndexMcp.y.isFinite() && worldIndexMcp.z.isFinite() &&
                worldPinkyMcp.x.isFinite() && worldPinkyMcp.y.isFinite() && worldPinkyMcp.z.isFinite()

        val worldPalmWidth = if (hasFiniteWorldGeometry) {
            distance3d(checkNotNull(worldIndexMcp), checkNotNull(worldPinkyMcp))
        } else {
            Float.NaN
        }
        val worldTipDistance = if (hasFiniteWorldGeometry) {
            distance3d(checkNotNull(worldThumb), checkNotNull(worldIndex))
        } else {
            Float.NaN
        }
        val useWorldGeometry = worldPalmWidth.isFinite() &&
            worldTipDistance.isFinite() &&
            worldPalmWidth in MIN_WORLD_PALM_WIDTH_METERS..MAX_WORLD_PALM_WIDTH_METERS

        val measuredPalmWidth = if (useWorldGeometry) {
            worldPalmWidth
        } else {
            distance(indexMcp, pinkyMcp)
        }
        val measuredTipDistance = if (useWorldGeometry) {
            worldTipDistance
        } else {
            distance(thumb, index)
        }
        if (!measuredPalmWidth.isFinite() || !measuredTipDistance.isFinite()) {
            reset()
            return PinchSignal(0f, false)
        }
        val minimumPalmWidth = if (useWorldGeometry) MIN_WORLD_PALM_WIDTH_METERS else MIN_PALM_WIDTH
        val palmWidth = measuredPalmWidth.coerceAtLeast(minimumPalmWidth)
        val normalizedPinchDistance = measuredTipDistance / palmWidth
        if (!normalizedPinchDistance.isFinite()) {
            reset()
            return PinchSignal(0f, false)
        }
        val rawStrength = ((OPEN_RATIO - normalizedPinchDistance) / RATIO_RANGE)
            .coerceIn(0f, 1f)
        val timeAlpha = smoothingAlpha(timestampMs)
        // A near-closed fingertip pair is a high-confidence gesture. Let a clear
        // full pinch cross the press threshold on this observation instead of
        // adding another inference interval; partial closure still uses normal smoothing.
        val alpha = if (!isPinching && rawStrength >= FAST_PRESS_RAW_STRENGTH) {
            maxOf(timeAlpha, FAST_PRESS_ALPHA)
        } else {
            timeAlpha
        }
        filteredStrength += (rawStrength - filteredStrength) * alpha

        if (!isPinching && filteredStrength >= pinchOnThreshold) {
            isPinching = true
        } else if (isPinching && filteredStrength <= pinchOffThreshold) {
            isPinching = false
        }
        return PinchSignal(filteredStrength, isPinching, normalizedPinchDistance, useWorldGeometry)
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

    private fun distance3d(a: HandPoint, b: HandPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private companion object {
        const val MIN_PALM_WIDTH = 0.015f
        const val MIN_WORLD_PALM_WIDTH_METERS = 0.02f
        const val MAX_WORLD_PALM_WIDTH_METERS = 0.16f
        const val OPEN_RATIO = 0.72f
        const val RATIO_RANGE = 0.52f
        const val FAST_PRESS_RAW_STRENGTH = 0.90f
        const val FAST_PRESS_ALPHA = 0.82f
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
    val trackingId: Long = 0L,
    /** Latest raw thumb/index distance relative to palm width; infinity means unavailable. */
    val pinchDistanceRatio: Float = Float.POSITIVE_INFINITY,
    /** True when pinchDistanceRatio was derived from world-space landmarks. */
    val pinchUsesWorldLandmarks: Boolean = false
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
