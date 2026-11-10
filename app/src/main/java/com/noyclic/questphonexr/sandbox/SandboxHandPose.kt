package com.noyclic.questphonexr.sandbox

import com.noyclic.questphonexr.tracking.HandPoint
import kotlin.math.pow

/** Maps MediaPipe image landmarks across the wider free-space sandbox reach volume, not just the platform footprint. */
internal object SandboxHandPoseMapper {
    // Convert normalized camera coordinates into a perspective volume. Unlike the
    // old fixed-size plane, the reachable width/height grow with estimated distance.
    private const val HORIZONTAL_FOV_SCALE = 2.4f
    private const val VERTICAL_FOV_SCALE = 1.8f
    private const val CURSOR_DEPTH = -2.55f
    private const val MAX_RELATIVE_DEPTH = 0.45f
    private const val MAX_HORIZONTAL_REACH = 8.0f
    private const val MAX_VERTICAL_REACH = 6.0f

    fun map(
        points: List<HandPoint>,
        calibration: SandboxHandCalibration = SandboxHandCalibration.DEFAULT,
        estimatedDistanceMeters: Float? = null,
        worldPoints: List<HandPoint> = emptyList(),
        cameraToWorldRotation: FloatArray? = null
    ): List<SandboxVector3> {
        val safeCalibration = calibration.normalized()
        val distance = (estimatedDistanceMeters ?: -CURSOR_DEPTH).coerceIn(0.5f, 6.0f)
        val horizontalReach = distance * HORIZONTAL_FOV_SCALE
        val verticalReach = distance * VERTICAL_FOV_SCALE
        val rotation = if (isValidRotation(cameraToWorldRotation)) cameraToWorldRotation else null
        val count = minOf(points.size, HAND_LANDMARK_COUNT)
        val mapped = ArrayList<SandboxVector3>(count)

        for (index in 0 until count) {
            val point = points[index]
            val relativeDepth = (
                if (index < worldPoints.size) worldPoints[index].z
                else point.z.coerceIn(-MAX_RELATIVE_DEPTH, MAX_RELATIVE_DEPTH)
                ).coerceIn(-MAX_RELATIVE_DEPTH, MAX_RELATIVE_DEPTH)
            val cameraPoint = SandboxVector3(
                x = (
                    (point.x - 0.5f) * horizontalReach * safeCalibration.horizontalScale +
                        safeCalibration.horizontalOffset
                    ).coerceIn(-MAX_HORIZONTAL_REACH, MAX_HORIZONTAL_REACH),
                y = (
                    (0.5f - point.y) * verticalReach * safeCalibration.verticalScale +
                        safeCalibration.verticalOffset
                    ).coerceIn(-MAX_VERTICAL_REACH, MAX_VERTICAL_REACH),
                z = -distance + relativeDepth * safeCalibration.relativeDepthScale
            )
            mapped.add(cameraPoint.toWorldSpace(rotation))
        }
        return mapped
    }

    /**
     * Sensor rotation is also used as the renderer's world-to-camera view rotation.
     * Transposing it maps camera-relative hand positions back into the stable world frame.
     */
    private fun isValidRotation(rotation: FloatArray?): Boolean {
        if (rotation == null || rotation.size < 9) return false
        for (index in 0 until 9) {
            if (!rotation[index].isFinite()) return false
        }
        return true
    }

    private fun SandboxVector3.toWorldSpace(rotation: FloatArray?): SandboxVector3 {
        if (rotation == null) return this
        return SandboxVector3(
            x = rotation[0] * x + rotation[1] * y + rotation[2] * z,
            y = rotation[3] * x + rotation[4] * y + rotation[5] * z,
            z = rotation[6] * x + rotation[7] * y + rotation[8] * z
        )
    }

    private const val HAND_LANDMARK_COUNT = 21
}


/**
 * Estimates hand distance from apparent palm width. This is monocular, relative depth:
 * it is useful for interaction but is not a metric 6DoF room pose.
 */
internal class MonocularHandDepthEstimator(
    private val referencePalmWidth: Float = 0.18f,
    private val referenceDistanceMeters: Float = 2.55f,
    private val minimumDistanceMeters: Float = 1.0f,
    private val maximumDistanceMeters: Float = 4.5f,
    private val smoothing: Float = 0.28f,
    private val maximumStepMeters: Float = 0.22f
) {
    private var filteredDistance: Float? = null
    private var lastTimestampMs = 0L

    /** Legacy sample-count overload retained for callers without source timestamps. */
    fun update(points: List<HandPoint>): Float? =
        updateMeasured(points, elapsedMs = null)

    /**
     * Timestamp-aware update for camera frames. Duplicate/out-of-order samples
     * cannot keep nudging the depth estimate during faster Sandbox rendering.
     */
    fun update(points: List<HandPoint>, timestampMs: Long): Float? {
        if (timestampMs <= 0L) return update(points)
        val previousTimestamp = lastTimestampMs
        if (previousTimestamp > 0L && timestampMs <= previousTimestamp) {
            return filteredDistance
        }
        lastTimestampMs = timestampMs
        val elapsedMs = if (previousTimestamp > 0L) timestampMs - previousTimestamp else null
        return updateMeasured(points, elapsedMs)
    }

    private fun updateMeasured(points: List<HandPoint>, elapsedMs: Long?): Float? {
        val indexMcp = points.getOrNull(5) ?: return filteredDistance
        val pinkyMcp = points.getOrNull(17) ?: return filteredDistance
        val dx = indexMcp.x - pinkyMcp.x
        val dy = indexMcp.y - pinkyMcp.y
        val palmWidth = kotlin.math.sqrt(dx * dx + dy * dy)
        if (!palmWidth.isFinite() || palmWidth < MIN_VALID_PALM_WIDTH) return filteredDistance

        val measured = (referenceDistanceMeters * referencePalmWidth / palmWidth)
            .coerceIn(minimumDistanceMeters, maximumDistanceMeters)
        val previous = filteredDistance
        val next = if (previous == null) {
            measured
        } else if (elapsedMs == null) {
            val desiredStep = (measured - previous) * smoothing
            previous + desiredStep.coerceIn(-maximumStepMeters, maximumStepMeters)
        } else if (elapsedMs > MAX_CONTINUOUS_GAP_MS) {
            measured
        } else {
            val deltaSeconds = (elapsedMs / 1_000f).coerceIn(
                MIN_DELTA_SECONDS,
                MAX_DELTA_SECONDS
            )
            // Preserve the configured response at the reference rate while
            // making the filter independent of how often the frame is sampled.
            val alpha = (
                1f - (1f - smoothing).pow(deltaSeconds * REFERENCE_FPS)
                ).coerceIn(0f, 1f)
            val maximumStep = maximumStepMeters * REFERENCE_FPS * deltaSeconds
            val desiredStep = (measured - previous) * alpha
            previous + desiredStep.coerceIn(-maximumStep, maximumStep)
        }
        filteredDistance = next
        return next
    }

    fun reset() {
        filteredDistance = null
        lastTimestampMs = 0L
    }

    private companion object {
        const val MIN_VALID_PALM_WIDTH = 0.035f
        const val REFERENCE_FPS = 30f
        const val MIN_DELTA_SECONDS = 0.001f
        const val MAX_DELTA_SECONDS = 0.25f
        const val MAX_CONTINUOUS_GAP_MS = 250L
    }
}
