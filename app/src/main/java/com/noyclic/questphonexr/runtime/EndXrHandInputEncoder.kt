package com.noyclic.questphonexr.runtime

import com.noyclic.questphonexr.input.ControllerInputSource
import com.noyclic.questphonexr.input.VirtualControllerMapper
import com.noyclic.questphonexr.input.VirtualControllerState
import com.noyclic.questphonexr.sandbox.MonocularHandDepthEstimator
import com.noyclic.questphonexr.tracking.HandPoint
import com.noyclic.questphonexr.tracking.HandSide
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.tracking.TrackedHand
import com.noyclic.questphonexr.vr.PhoneVrMath
import kotlin.math.sqrt

/**
 * Camera-local palm poses are estimates, not metric controller tracking.
 * Image x/y place the palm in the interaction frustum; apparent palm width
 * estimates distance; MediaPipe world landmarks supply palm orientation.
 *
 * This deliberately does not call the result 6DoF. Translation is monocular
 * and orientation is derived from a hand skeleton, not a tracked controller.
 */
internal data class EndXrEncodedHandInput(
    val leftRow: FloatArray,
    val rightRow: FloatArray,
    val leftPose: EndXrHandPoseSample,
    val rightPose: EndXrHandPoseSample
)

internal object EndXrHandInputEncoder {
    private data class Vec3(val x: Float, val y: Float, val z: Float) {
        operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
        operator fun times(scale: Float) = Vec3(x * scale, y * scale, z * scale)
        fun length(): Float = sqrt(x * x + y * y + z * z)
        fun normalized(): Vec3? {
            val length = length()
            if (!length.isFinite() || length < 0.0001f) return null
            return this * (1f / length)
        }
    }

    private val mapper = VirtualControllerMapper()
    private val leftDepthEstimator = MonocularHandDepthEstimator()
    private val rightDepthEstimator = MonocularHandDepthEstimator()
    private val leftOrientationSmoother = PalmOrientationSmoother()
    private val rightOrientationSmoother = PalmOrientationSmoother()

    @Synchronized
    fun encode(frame: HandTrackingFrame): EndXrEncodedHandInput {
        val mapped = mapper.map(frame, emptyList())
        val hands = frame.hands.filter { it.side != HandSide.UNKNOWN }.associateBy { it.side }
        val left = hands[HandSide.LEFT]
        val right = hands[HandSide.RIGHT]

        val leftPose = encodePose(left, leftDepthEstimator, leftOrientationSmoother, frame.timestampMs)
        val rightPose = encodePose(right, rightDepthEstimator, rightOrientationSmoother, frame.timestampMs)
        if (left == null) {
            leftDepthEstimator.reset()
            leftOrientationSmoother.reset()
        }
        if (right == null) {
            rightDepthEstimator.reset()
            rightOrientationSmoother.reset()
        }

        return EndXrEncodedHandInput(
            leftRow = encodeState(mapped.left),
            rightRow = encodeState(mapped.right),
            leftPose = leftPose,
            rightPose = rightPose
        )
    }

    private fun encodePose(
        hand: TrackedHand?,
        depthEstimator: MonocularHandDepthEstimator,
        orientationSmoother: PalmOrientationSmoother,
        timestampMs: Long
    ): EndXrHandPoseSample {
        if (hand == null || hand.points.size < LANDMARK_COUNT ||
            hand.worldPoints.size < LANDMARK_COUNT
        ) {
            orientationSmoother.reset()
            return EndXrHandPoseSample.EMPTY
        }

        val imageCenter = average(hand.points, PALM_INDICES) ?: run {
            orientationSmoother.reset()
            return EndXrHandPoseSample.EMPTY
        }
        val worldCenter = average(hand.worldPoints, PALM_INDICES) ?: run {
            orientationSmoother.reset()
            return EndXrHandPoseSample.EMPTY
        }
        val distance = depthEstimator.update(hand.points)?.takeIf { it.isFinite() }
            ?: DEFAULT_HAND_DISTANCE_METERS

        val x = ((imageCenter.x - 0.5f) * distance * HORIZONTAL_FOV_SCALE)
            .coerceIn(-MAX_HAND_X_METERS, MAX_HAND_X_METERS)
        val y = ((0.5f - imageCenter.y) * distance * VERTICAL_FOV_SCALE)
            .coerceIn(-MAX_HAND_Y_METERS, MAX_HAND_Y_METERS)
        val z = (-distance + worldCenter.z.coerceIn(-MAX_LOCAL_DEPTH_METERS, MAX_LOCAL_DEPTH_METERS) *
            LOCAL_DEPTH_SCALE).coerceIn(-MAX_HAND_DISTANCE_METERS, -0.25f)

        val rawOrientation = palmOrientation(hand.worldPoints) ?: run {
            orientationSmoother.reset()
            return EndXrHandPoseSample.EMPTY
        }
        val orientation = orientationSmoother.update(rawOrientation, timestampMs)
            ?: return EndXrHandPoseSample.EMPTY
        return EndXrHandPoseSample.fromCameraPose(
            x = x, y = y, z = z,
            qx = orientation[0], qy = orientation[1],
            qz = orientation[2], qw = orientation[3]
        )
    }

    private fun average(points: List<HandPoint>, indices: IntArray): Vec3? {
        if (indices.any { it !in points.indices }) return null
        val selected = indices.map { points[it] }
        if (selected.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) return null
        val count = selected.size.toFloat()
        return Vec3(
            selected.sumOf { it.x.toDouble() }.toFloat() / count,
            selected.sumOf { it.y.toDouble() }.toFloat() / count,
            selected.sumOf { it.z.toDouble() }.toFloat() / count
        )
    }

    /**
     * Construct an approximate palm basis from wrist, index/middle/pinky MCP.
     * The basis is hand-local and camera-aligned; it is not a controller model.
     */
    private fun palmOrientation(points: List<HandPoint>): FloatArray? {
        val wrist = points.getOrNull(0)?.toVec3() ?: return null
        val indexMcp = points.getOrNull(5)?.toVec3() ?: return null
        val middleMcp = points.getOrNull(9)?.toVec3() ?: return null
        val pinkyMcp = points.getOrNull(17)?.toVec3() ?: return null

        val xAxis = (indexMcp - pinkyMcp).normalized() ?: return null
        val yCandidate = (middleMcp - wrist).normalized() ?: return null
        val zAxis = cross(xAxis, yCandidate).normalized() ?: return null
        val yAxis = cross(zAxis, xAxis).normalized() ?: return null
        return quaternionFromBasis(xAxis, yAxis, zAxis)
    }

    private fun HandPoint.toVec3(): Vec3? =
        if (x.isFinite() && y.isFinite() && z.isFinite()) Vec3(x, y, z) else null

    private fun cross(a: Vec3, b: Vec3) = Vec3(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x
    )

    /** Rotation matrix columns are the palm's local +X/+Y/+Z axes. */
    private fun quaternionFromBasis(x: Vec3, y: Vec3, z: Vec3): FloatArray? {
        val m00 = x.x; val m01 = y.x; val m02 = z.x
        val m10 = x.y; val m11 = y.y; val m12 = z.y
        val m20 = x.z; val m21 = y.z; val m22 = z.z
        val trace = m00 + m11 + m22
        val q = FloatArray(4)
        if (trace > 0f) {
            val scale = sqrt(trace + 1f) * 2f
            if (scale < 0.0001f) return null
            q[3] = 0.25f * scale
            q[0] = (m21 - m12) / scale
            q[1] = (m02 - m20) / scale
            q[2] = (m10 - m01) / scale
        } else if (m00 > m11 && m00 > m22) {
            val scale = sqrt(1f + m00 - m11 - m22) * 2f
            if (scale < 0.0001f) return null
            q[3] = (m21 - m12) / scale
            q[0] = 0.25f * scale
            q[1] = (m01 + m10) / scale
            q[2] = (m02 + m20) / scale
        } else if (m11 > m22) {
            val scale = sqrt(1f + m11 - m00 - m22) * 2f
            if (scale < 0.0001f) return null
            q[3] = (m02 - m20) / scale
            q[0] = (m01 + m10) / scale
            q[1] = 0.25f * scale
            q[2] = (m12 + m21) / scale
        } else {
            val scale = sqrt(1f + m22 - m00 - m11) * 2f
            if (scale < 0.0001f) return null
            q[3] = (m10 - m01) / scale
            q[0] = (m02 + m20) / scale
            q[1] = (m12 + m21) / scale
            q[2] = 0.25f * scale
        }
        return q.takeIf { it.all(Float::isFinite) }
    }

    private fun encodeState(state: VirtualControllerState): FloatArray = floatArrayOf(
        if (state.source != ControllerInputSource.NONE) 1f else 0f,
        state.trigger.coerceIn(0f, 1f),
        state.grip.coerceIn(0f, 1f),
        0f,
        0f,
        0f
    )

    /**
     * Per-hand temporal quaternion filter. Duplicate callbacks are ignored so the
     * foreground service and Sandbox render loop cannot advance one sample twice.
     */
    private class PalmOrientationSmoother {
        private var previous: FloatArray? = null
        private var previousTimestampMs = 0L

        fun update(raw: FloatArray, timestampMs: Long): FloatArray? {
            val normalized = PhoneVrMath.normalizedQuaternion(raw) ?: return null
            val current = previous

            if (current == null || timestampMs <= 0L || previousTimestampMs <= 0L) {
                previous = normalized
                previousTimestampMs = timestampMs.coerceAtLeast(0L)
                return normalized.copyOf()
            }

            // Ignore duplicate and out-of-order results; never rewind a tracked pose.
            if (timestampMs <= previousTimestampMs) return current.copyOf()

            val elapsedMs = timestampMs - previousTimestampMs
            if (elapsedMs > MAX_CONTINUOUS_GAP_MS) {
                previous = normalized
                previousTimestampMs = timestampMs
                return normalized.copyOf()
            }

            val deltaSeconds = (elapsedMs / 1_000f).coerceIn(
                MIN_DELTA_SECONDS,
                MAX_DELTA_SECONDS
            )
            val dot = kotlin.math.abs(
                current[0] * normalized[0] +
                    current[1] * normalized[1] +
                    current[2] * normalized[2] +
                    current[3] * normalized[3]
            ).coerceIn(0f, 1f)
            val angularSpeed = (
                2.0 * kotlin.math.acos(dot.toDouble()) / deltaSeconds.toDouble()
                ).toFloat()
            val fastMotion = (
                (angularSpeed - LOW_ANGULAR_SPEED_RAD_S) /
                    (HIGH_ANGULAR_SPEED_RAD_S - LOW_ANGULAR_SPEED_RAD_S)
                ).coerceIn(0f, 1f)
            val timeConstant = BASE_TIME_CONSTANT_SECONDS *
                (1f - FAST_MOTION_REDUCTION * fastMotion)
            val alpha = (
                1f - kotlin.math.exp(-deltaSeconds / timeConstant)
                ).coerceIn(0f, 1f)

            val filtered = PhoneVrMath.slerpQuat(current, normalized, alpha) ?: return null
            previous = filtered
            previousTimestampMs = timestampMs
            return filtered.copyOf()
        }

        fun reset() {
            previous = null
            previousTimestampMs = 0L
        }

        private companion object {
            const val BASE_TIME_CONSTANT_SECONDS = 0.045f
            const val FAST_MOTION_REDUCTION = 0.50f
            const val LOW_ANGULAR_SPEED_RAD_S = 1.5f
            const val HIGH_ANGULAR_SPEED_RAD_S = 8f
            const val MIN_DELTA_SECONDS = 0.001f
            const val MAX_DELTA_SECONDS = 0.1f
            const val MAX_CONTINUOUS_GAP_MS = 250L
        }
    }

    private const val LANDMARK_COUNT = 21
    private val PALM_INDICES = intArrayOf(0, 5, 9, 13, 17)
    private const val DEFAULT_HAND_DISTANCE_METERS = 2.55f
    private const val HORIZONTAL_FOV_SCALE = 2.4f
    private const val VERTICAL_FOV_SCALE = 1.8f
    private const val LOCAL_DEPTH_SCALE = 0.55f
    private const val MAX_LOCAL_DEPTH_METERS = 0.45f
    private const val MAX_HAND_X_METERS = 8f
    private const val MAX_HAND_Y_METERS = 6f
    private const val MAX_HAND_DISTANCE_METERS = 6f
}
