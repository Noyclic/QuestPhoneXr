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
        return points.take(HAND_LANDMARK_COUNT).mapIndexed { index, point ->
            val relativeDepth = (
                worldPoints.getOrNull(index)?.z
                    ?: point.z.coerceIn(-MAX_RELATIVE_DEPTH, MAX_RELATIVE_DEPTH)
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
            cameraPoint.toWorldSpace(cameraToWorldRotation)
        }
    }

    /**
     * Sensor rotation is also used as the renderer's world-to-camera view rotation.
     * Transposing it maps camera-relative hand positions back into the stable world frame.
     */
    private fun SandboxVector3.toWorldSpace(rotation: FloatArray?): SandboxVector3 {
        if (rotation == null || rotation.size < 9 || !rotation.take(9).all { it.isFinite() }) {
            return this
        }
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

/**
 * Per-joint adaptive low-pass filter.
 *
 * The time-based overload keeps the response stable across render rates and
 * reduces smoothing lag when the hand is moving quickly. Invalid samples never
 * poison the last valid pose.
 */
internal class SandboxHandPoseSmoother(
    private val response: Float = DEFAULT_RESPONSE
) {
    private var previous: List<SandboxVector3> = emptyList()

    init {
        require(response > 0f && response <= 1f) {
            "Hand pose smoothing response must be in the range (0, 1]."
        }
    }

    /** Compatibility overload for deterministic callers and existing tests. */
    fun update(target: List<SandboxVector3>): List<SandboxVector3> {
        if (target.isEmpty()) {
            reset()
            return emptyList()
        }
        if (!target.all(::isFinite)) return previous
        return filter(target, response)
    }

    /**
     * Updates using elapsed render time. At low motion this follows the configured
     * 60 Hz response; at higher motion it increases the response to reduce lag.
     */
    fun update(
        target: List<SandboxVector3>,
        deltaSeconds: Float
    ): List<SandboxVector3> {
        if (target.isEmpty()) {
            reset()
            return emptyList()
        }
        if (!target.all(::isFinite)) return previous

        val current = previous
        if (current.isEmpty() || current.size != target.size) {
            previous = target.toList()
            return previous
        }

        val dt = if (deltaSeconds.isFinite()) {
            deltaSeconds.coerceIn(MIN_DELTA_SECONDS, MAX_DELTA_SECONDS)
        } else {
            DEFAULT_DELTA_SECONDS
        }

        // Filter each landmark independently. A single fast fingertip should catch up
        // without forcing the entire palm to use the same low-smoothing response.
        val baseTimeConstant = if (response >= 1f) {
            0f
        } else {
            -1f / (60f * kotlin.math.ln(1f - response))
        }
        val filtered = target.mapIndexed { index, next ->
            val displacement = distance(current[index], next)
            val speed = displacement / dt
            val motion = (
                (speed - LOW_MOTION_SPEED) /
                    (HIGH_MOTION_SPEED - LOW_MOTION_SPEED)
                ).coerceIn(0f, 1f)
            val timeConstant = baseTimeConstant * (1f - FAST_MOTION_TIME_REDUCTION * motion)
            val alpha = if (timeConstant <= MIN_TIME_CONSTANT_SECONDS) {
                1f
            } else {
                (1f - kotlin.math.exp(-dt / timeConstant)).coerceIn(0f, 1f)
            }
            current[index] + (next - current[index]) * alpha
        }
        previous = filtered
        return filtered
    }

    private fun filter(
        target: List<SandboxVector3>,
        alpha: Float
    ): List<SandboxVector3> {
        val current = previous
        val filtered = if (current.size != target.size) {
            target.toList()
        } else {
            target.mapIndexed { index, next ->
                current[index] + (next - current[index]) * alpha
            }
        }
        previous = filtered
        return filtered
    }

    private fun isFinite(point: SandboxVector3): Boolean =
        point.x.isFinite() && point.y.isFinite() && point.z.isFinite()

    private fun distance(a: SandboxVector3, b: SandboxVector3): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun reset() {
        previous = emptyList()
    }

    private companion object {
        const val DEFAULT_RESPONSE = 0.42f
        const val MIN_DELTA_SECONDS = 1f / 120f
        const val MAX_DELTA_SECONDS = 0.1f
        const val DEFAULT_DELTA_SECONDS = 1f / 60f
        const val LOW_MOTION_SPEED = 0.35f
        const val HIGH_MOTION_SPEED = 2.25f
        const val FAST_MOTION_TIME_REDUCTION = 0.65f
        const val MIN_TIME_CONSTANT_SECONDS = 0.0001f
    }
}
