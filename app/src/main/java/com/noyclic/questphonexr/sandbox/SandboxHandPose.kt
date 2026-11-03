package com.noyclic.questphonexr.sandbox

import com.noyclic.questphonexr.tracking.HandPoint

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
        worldPoints: List<HandPoint> = emptyList()
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
            SandboxVector3(
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
        }
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

    fun update(points: List<HandPoint>): Float? {
        val indexMcp = points.getOrNull(5) ?: return filteredDistance
        val pinkyMcp = points.getOrNull(17) ?: return filteredDistance
        val dx = indexMcp.x - pinkyMcp.x
        val dy = indexMcp.y - pinkyMcp.y
        val palmWidth = kotlin.math.sqrt(dx * dx + dy * dy)
        if (!palmWidth.isFinite() || palmWidth < MIN_VALID_PALM_WIDTH) return filteredDistance

        val measured = (referenceDistanceMeters * referencePalmWidth / palmWidth)
            .coerceIn(minimumDistanceMeters, maximumDistanceMeters)
        val previous = filteredDistance
        val next = if (previous == null) measured else {
            val desiredStep = (measured - previous) * smoothing
            previous + desiredStep.coerceIn(-maximumStepMeters, maximumStepMeters)
        }
        filteredDistance = next
        return next
    }

    fun reset() {
        filteredDistance = null
    }

    private companion object {
        const val MIN_VALID_PALM_WIDTH = 0.035f
    }
}

/** Per-joint low-pass filter. It resets cleanly when tracking disappears or point count changes. */
internal class SandboxHandPoseSmoother(
    private val response: Float = DEFAULT_RESPONSE
) {
    private var previous: List<SandboxVector3> = emptyList()

    init {
        require(response > 0f && response <= 1f) {
            "Hand pose smoothing response must be in the range (0, 1]."
        }
    }

    fun update(target: List<SandboxVector3>): List<SandboxVector3> {
        if (target.isEmpty()) {
            reset()
            return emptyList()
        }
        val current = previous
        val filtered = if (current.size != target.size) {
            target.toList()
        } else {
            target.mapIndexed { index, next ->
                current[index] + (next - current[index]) * response
            }
        }
        previous = filtered
        return filtered
    }

    fun reset() {
        previous = emptyList()
    }

    private companion object {
        const val DEFAULT_RESPONSE = 0.42f
    }
}
