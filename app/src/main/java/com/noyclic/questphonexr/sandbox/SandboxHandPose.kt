package com.noyclic.questphonexr.sandbox

import com.noyclic.questphonexr.tracking.HandPoint

/** Maps MediaPipe image landmarks into the sandbox's phone-VR interaction plane. */
internal object SandboxHandPoseMapper {
    private const val CURSOR_WIDTH = 3.0f
    private const val CURSOR_HEIGHT = 2.15f
    private const val CURSOR_DEPTH = -2.55f
    private const val MAX_RELATIVE_DEPTH = 0.45f

    fun map(
        points: List<HandPoint>,
        calibration: SandboxHandCalibration = SandboxHandCalibration.DEFAULT
    ): List<SandboxVector3> {
        val safeCalibration = calibration.normalized()
        return points.take(HAND_LANDMARK_COUNT).map { point ->
            SandboxVector3(
                x = (
                    (point.x - 0.5f) * CURSOR_WIDTH * safeCalibration.horizontalScale +
                        safeCalibration.horizontalOffset
                    ).coerceIn(-1.65f, 1.65f),
                y = (
                    (0.5f - point.y) * CURSOR_HEIGHT * safeCalibration.verticalScale +
                        safeCalibration.verticalOffset
                    ).coerceIn(-1.15f, 1.15f),
                z = CURSOR_DEPTH +
                    point.z.coerceIn(-MAX_RELATIVE_DEPTH, MAX_RELATIVE_DEPTH) *
                    safeCalibration.relativeDepthScale
            )
        }
    }

    private const val HAND_LANDMARK_COUNT = 21
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
