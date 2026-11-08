package com.noyclic.questphonexr.runtime

import com.noyclic.questphonexr.sandbox.MonocularHandDepthEstimator
import com.noyclic.questphonexr.tracking.HandPoint
import com.noyclic.questphonexr.tracking.HandGestureEstimator
import com.noyclic.questphonexr.tracking.HandKinematicReconstructor
import com.noyclic.questphonexr.tracking.AdaptiveHandOrientationFilter
import com.noyclic.questphonexr.tracking.AdaptiveHandRootPositionFilter
import com.noyclic.questphonexr.tracking.HandSide
import com.noyclic.questphonexr.tracking.NaturalGestureClassifier
import com.noyclic.questphonexr.tracking.NaturalGestureResult
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.tracking.TrackedHand
import kotlin.math.sqrt

/**
 * Camera-local hand poses are estimates, not metric controller tracking.
 * The 26 OpenXR joint positions are reconstructed from MediaPipe's 21 landmarks:
 * palm and four finger metacarpal bases are derived, while the measured wrist,
 * thumb and finger joints retain their MediaPipe positions.
 */
internal data class EndXrEncodedHandInput(
    val leftRow: FloatArray,
    val rightRow: FloatArray,
    val leftPose: EndXrHandPoseSample,
    val rightPose: EndXrHandPoseSample,
    val leftJoints: EndXrHandJointSample,
    val rightJoints: EndXrHandJointSample,
    val leftGesture: NaturalGestureResult,
    val rightGesture: NaturalGestureResult
)

internal object EndXrHandInputEncoder {
    private data class Vec3(val x: Float, val y: Float, val z: Float) {
        operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
        operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
        operator fun times(scale: Float) = Vec3(x * scale, y * scale, z * scale)
        fun length(): Float = sqrt(x * x + y * y + z * z)
        fun normalized(): Vec3? {
            val length = length()
            if (!length.isFinite() || length < 0.0001f) return null
            return this * (1f / length)
        }
    }

    private data class EncodedHand(
        val pose: EndXrHandPoseSample,
        val joints: EndXrHandJointSample
    ) {
        companion object {
            val EMPTY = EncodedHand(
                EndXrHandPoseSample.EMPTY,
                EndXrHandJointSample.EMPTY
            )
        }
    }

    private class EncodedHandSampleCache {
        private var trackingId = NO_TRACKING_ID
        private var sampleTimestampMs = Long.MIN_VALUE
        private var encoded = EncodedHand.EMPTY

        fun matches(nextTrackingId: Long, nextTimestampMs: Long): Boolean =
            trackingId == nextTrackingId && sampleTimestampMs == nextTimestampMs

        fun value(): EncodedHand = encoded

        fun store(nextTrackingId: Long, nextTimestampMs: Long, value: EncodedHand) {
            trackingId = nextTrackingId
            sampleTimestampMs = nextTimestampMs
            encoded = value
        }

        fun reset() {
            trackingId = NO_TRACKING_ID
            sampleTimestampMs = Long.MIN_VALUE
            encoded = EncodedHand.EMPTY
        }

        private companion object {
            const val NO_TRACKING_ID = Long.MIN_VALUE
        }
    }

    private val leftDepthEstimator = MonocularHandDepthEstimator()
    private val rightDepthEstimator = MonocularHandDepthEstimator()
    private val leftSkeletonReconstructor = HandKinematicReconstructor()
    private val rightSkeletonReconstructor = HandKinematicReconstructor()
    private val leftOrientationFilter = AdaptiveHandOrientationFilter()
    private val rightOrientationFilter = AdaptiveHandOrientationFilter()
    private val leftRootPositionFilter = AdaptiveHandRootPositionFilter()
    private val rightRootPositionFilter = AdaptiveHandRootPositionFilter()
    private val leftSampleCache = EncodedHandSampleCache()
    private val rightSampleCache = EncodedHandSampleCache()
    private val leftGestureClassifier = NaturalGestureClassifier()
    private val rightGestureClassifier = NaturalGestureClassifier()

    @Synchronized
    fun encode(frame: HandTrackingFrame): EndXrEncodedHandInput {
        var left: TrackedHand? = null
        var right: TrackedHand? = null
        for (hand in frame.hands) {
            when (hand.side) {
                HandSide.LEFT -> left = hand
                HandSide.RIGHT -> right = hand
                HandSide.UNKNOWN -> Unit
            }
        }

        val leftEncoded = encodeHand(
            left, leftDepthEstimator, leftSkeletonReconstructor, leftOrientationFilter,
            leftRootPositionFilter, leftSampleCache, frame.timestampMs
        )
        val rightEncoded = encodeHand(
            right, rightDepthEstimator, rightSkeletonReconstructor, rightOrientationFilter,
            rightRootPositionFilter, rightSampleCache, frame.timestampMs
        )
        val leftGesture = leftGestureClassifier.update(left, frame.timestampMs)
        val rightGesture = rightGestureClassifier.update(right, frame.timestampMs)
        if (left == null) {
            leftDepthEstimator.reset()
            leftSkeletonReconstructor.reset()
            leftOrientationFilter.reset()
            leftRootPositionFilter.reset()
            leftSampleCache.reset()
        }
        if (right == null) {
            rightDepthEstimator.reset()
            rightSkeletonReconstructor.reset()
            rightOrientationFilter.reset()
            rightRootPositionFilter.reset()
            rightSampleCache.reset()
        }

        return EndXrEncodedHandInput(
            leftRow = encodeControllerRow(left),
            rightRow = encodeControllerRow(right),
            leftPose = leftEncoded.pose,
            rightPose = rightEncoded.pose,
            leftJoints = leftEncoded.joints,
            rightJoints = rightEncoded.joints,
            leftGesture = leftGesture,
            rightGesture = rightGesture
        )
    }

    /**
     * Camera hands supply pinch trigger and a digital natural power grip only.
     * Stick axes and physical face buttons remain neutral, not fabricated.
     */
    private fun encodeControllerRow(hand: TrackedHand?): FloatArray {
        if (hand == null || hand.points.size < LANDMARK_COUNT ||
            hand.points.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }
        ) return FloatArray(CONTROLLER_ROW_SIZE)

        val rawTrigger = hand.pinchStrength.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val trigger = if (hand.isPinching) maxOf(rawTrigger, PINCH_ACTIVE_FLOOR) else rawTrigger
        val grip = if (HandGestureEstimator.isPowerGrip(hand)) 1f else 0f
        return floatArrayOf(1f, trigger, grip, 0f, 0f, 0f)
    }

    private fun encodeHand(
        hand: TrackedHand?,
        depthEstimator: MonocularHandDepthEstimator,
        skeletonReconstructor: HandKinematicReconstructor,
        orientationFilter: AdaptiveHandOrientationFilter,
        rootPositionFilter: AdaptiveHandRootPositionFilter,
        sampleCache: EncodedHandSampleCache,
        timestampMs: Long
    ): EncodedHand {
        if (hand == null) {
            sampleCache.reset()
            return EncodedHand.EMPTY
        }

        val sampleTimestampMs = hand.rawTimestampMs.takeIf { it >= 0L } ?: timestampMs
        if (sampleCache.matches(hand.trackingId, sampleTimestampMs)) {
            val sampleAgeMs = timestampMs - sampleTimestampMs
            if (sampleAgeMs in 0L..MAX_CACHED_SAMPLE_AGE_MS) {
                // A temporary detector miss can expose the last raw sample again.
                // Reuse its encoded pose/joints instead of feeding the same timestamp
                // into temporal filters, which would otherwise reset their history.
                return sampleCache.value()
            }

            sampleCache.reset()
            skeletonReconstructor.reset()
            orientationFilter.reset()
            rootPositionFilter.reset()
            depthEstimator.reset()
            return EncodedHand.EMPTY
        }

        val encoded = encodeHandFresh(
            hand, depthEstimator, skeletonReconstructor, orientationFilter,
            rootPositionFilter, timestampMs
        )
        sampleCache.store(hand.trackingId, sampleTimestampMs, encoded)
        return encoded
    }

    private fun encodeHandFresh(
        hand: TrackedHand,
        depthEstimator: MonocularHandDepthEstimator,
        skeletonReconstructor: HandKinematicReconstructor,
        orientationFilter: AdaptiveHandOrientationFilter,
        rootPositionFilter: AdaptiveHandRootPositionFilter,
        timestampMs: Long
    ): EncodedHand {
        if (hand == null || hand.rawPoints.size < LANDMARK_COUNT ||
            hand.rawWorldPoints.size < LANDMARK_COUNT ||
            hand.rawPoints.take(LANDMARK_COUNT).any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() } ||
            hand.rawWorldPoints.take(LANDMARK_COUNT).any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }
        ) {
            skeletonReconstructor.reset()
            orientationFilter.reset()
            rootPositionFilter.reset()
            depthEstimator.reset()
            return EncodedHand(EndXrHandPoseSample.EMPTY, EndXrHandJointSample.EMPTY)
        }

        // EndXR uses the newest observation here. The reconstructor owns the
        // adaptive joint filter; feeding it One-Euro-filtered render points would
        // stack two temporal filters and add avoidable input lag.
        val sampleTimestampMs = hand.rawTimestampMs.takeIf { it >= 0L } ?: timestampMs
        val imageCenter = average(hand.rawPoints, PALM_INDICES) ?: run {
            rootPositionFilter.reset()
            return EncodedHand(EndXrHandPoseSample.EMPTY, EndXrHandJointSample.EMPTY)
        }
        val worldCenter = average(hand.rawWorldPoints, PALM_INDICES) ?: run {
            rootPositionFilter.reset()
            return EncodedHand(EndXrHandPoseSample.EMPTY, EndXrHandJointSample.EMPTY)
        }
        // Depth is already smoothed by MonocularHandDepthEstimator. Filter the
        // camera-plane palm center separately, preserving fast deliberate moves.
        val stableImageCenter = rootPositionFilter.update(
            imageCenter.x, imageCenter.y, hand.trackingId, sampleTimestampMs
        ) ?: return EncodedHand(EndXrHandPoseSample.EMPTY, EndXrHandJointSample.EMPTY)
        val distance = depthEstimator.update(hand.rawPoints)?.takeIf { it.isFinite() }
            ?: DEFAULT_HAND_DISTANCE_METERS

        val x = ((stableImageCenter.x - 0.5f) * distance * HORIZONTAL_FOV_SCALE)
            .coerceIn(-MAX_HAND_X_METERS, MAX_HAND_X_METERS)
        val y = ((0.5f - stableImageCenter.y) * distance * VERTICAL_FOV_SCALE)
            .coerceIn(-MAX_HAND_Y_METERS, MAX_HAND_Y_METERS)
        val z = (-distance + worldCenter.z.coerceIn(-MAX_LOCAL_DEPTH_METERS, MAX_LOCAL_DEPTH_METERS) *
            LOCAL_DEPTH_SCALE).coerceIn(-MAX_HAND_DISTANCE_METERS, -0.25f)
        val measuredOrientation = palmOrientation(hand.rawWorldPoints) ?: run {
            orientationFilter.reset()
            return EncodedHand(EndXrHandPoseSample.EMPTY, EndXrHandJointSample.EMPTY)
        }
        val orientation = orientationFilter.update(
            measuredOrientation,
            trackingId = hand.trackingId,
            timestampMs = sampleTimestampMs
        ) ?: return EncodedHand(EndXrHandPoseSample.EMPTY, EndXrHandJointSample.EMPTY)
        val pose = EndXrHandPoseSample.fromCameraPose(
            x = x, y = y, z = z,
            qx = orientation[0], qy = orientation[1],
            qz = orientation[2], qw = orientation[3]
        )
        if (!pose.active) return EncodedHand(EndXrHandPoseSample.EMPTY, EndXrHandJointSample.EMPTY)

        val reconstructedJoints = skeletonReconstructor.reconstruct(
            hand.rawWorldPoints,
            hand.trackingId,
            sampleTimestampMs
        ) ?: return EncodedHand(pose, EndXrHandJointSample.EMPTY)
        val joints = encodeJoints(reconstructedJoints, pose)
        return EncodedHand(pose, joints)
    }

    /**
     * Reconstruct OpenXR's 26-joint order from MediaPipe's 21 landmarks.
     * Four finger metacarpals are estimated along the wrist-to-MCP segment.
     * All joint offsets and orientations are kept in MediaPipe world space,
     * so the native runtime composes them with the monocular palm pose once.
     */
    private fun encodeJoints(
        reconstructed: List<HandPoint>,
        palmPose: EndXrHandPoseSample
    ): EndXrHandJointSample {
        if (reconstructed.size != EndXrHandJointSample.JOINT_COUNT) {
            return EndXrHandJointSample.EMPTY
        }
        val jointPoints = reconstructed.map { it.toVec3() ?: return EndXrHandJointSample.EMPTY }
        val palm = jointPoints[0]
        if (jointPoints.size != EndXrHandJointSample.JOINT_COUNT) {
            return EndXrHandJointSample.EMPTY
        }

        val inversePalm = floatArrayOf(
            -palmPose.qx, -palmPose.qy, -palmPose.qz, palmPose.qw
        )
        val values = FloatArray(EndXrHandJointSample.FLOAT_COUNT)
        for (index in jointPoints.indices) {
            val localOffset = rotate(inversePalm, jointPoints[index] - palm)
            if (!localOffset.x.isFinite() || !localOffset.y.isFinite() ||
                !localOffset.z.isFinite() || kotlin.math.abs(localOffset.x) > 0.24f ||
                kotlin.math.abs(localOffset.y) > 0.24f ||
                kotlin.math.abs(localOffset.z) > 0.24f
            ) return EndXrHandJointSample.EMPTY

            val nextIndex = nextJointIndex(index)
            val direction = if (nextIndex >= 0) {
                (jointPoints[nextIndex] - jointPoints[index]).normalized()
            } else if (index > 1) {
                (jointPoints[index] - jointPoints[previousJointIndex(index)]).normalized()
            } else {
                null
            }
            val localDirection = direction?.let { rotate(inversePalm, it) }
            val localOrientation = localDirection?.let(::quaternionFromDirection)
                ?: floatArrayOf(0f, 0f, 0f, 1f)

            val offset = index * EndXrHandJointSample.FLOATS_PER_JOINT
            values[offset] = localOffset.x
            values[offset + 1] = localOffset.y
            values[offset + 2] = localOffset.z
            values[offset + 3] = jointRadius(index)
            values[offset + 4] = localOrientation[0]
            values[offset + 5] = localOrientation[1]
            values[offset + 6] = localOrientation[2]
            values[offset + 7] = localOrientation[3]
        }
        return EndXrHandJointSample.fromValues(values)
    }

    private fun nextJointIndex(index: Int): Int = when {
        index in 2..4 -> index + 1
        index in 6..9 -> index + 1
        index in 11..14 -> index + 1
        index in 16..19 -> index + 1
        index in 21..24 -> index + 1
        else -> -1
    }

    private fun previousJointIndex(index: Int): Int = when {
        index == 5 -> 4
        index in 7..10 -> index - 1
        index in 12..15 -> index - 1
        index in 17..20 -> index - 1
        index in 22..25 -> index - 1
        else -> 1
    }

    private fun quaternionFromDirection(direction: Vec3): FloatArray? {
        val forward = direction.normalized() ?: return null
        val reference = Vec3(0f, 0f, 1f)
        val right = cross(forward, reference).normalized()
            ?: cross(forward, Vec3(0f, 1f, 0f)).normalized()
            ?: return null
        val up = cross(right, forward).normalized() ?: return null
        return quaternionFromBasis(right, forward, up)
    }
    private fun jointRadius(index: Int): Float = when {
        index == 0 -> 0.022f
        index == 1 -> 0.014f
        index in 2..5 -> 0.010f
        index % 5 == 0 -> 0.008f
        else -> 0.009f
    }

    /** Rotate a vector by a normalized quaternion. */
    private fun rotate(q: FloatArray, value: Vec3): Vec3 {
        val qv = Vec3(q[0], q[1], q[2])
        val cross1 = cross(qv, value)
        val cross2 = cross(qv, cross1 + value * q[3])
        return value + cross2 * 2f
    }

    private fun average(points: List<HandPoint>, indices: IntArray): Vec3? {
        if (indices.isEmpty()) return null
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        for (index in indices) {
            val point = points.getOrNull(index) ?: return null
            if (!point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite()) return null
            sumX += point.x.toDouble()
            sumY += point.y.toDouble()
            sumZ += point.z.toDouble()
        }
        val count = indices.size.toDouble()
        return Vec3(
            (sumX / count).toFloat(),
            (sumY / count).toFloat(),
            (sumZ / count).toFloat()
        )
    }

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


    private const val MAX_CACHED_SAMPLE_AGE_MS = 140L
    private const val LANDMARK_COUNT = 21
    private const val CONTROLLER_ROW_SIZE = 6
    private const val PINCH_ACTIVE_FLOOR = 0.62f
    private val PALM_INDICES = intArrayOf(5, 9, 13, 17)
    private const val DEFAULT_HAND_DISTANCE_METERS = 2.55f
    private const val HORIZONTAL_FOV_SCALE = 2.4f
    private const val VERTICAL_FOV_SCALE = 1.8f
    private const val LOCAL_DEPTH_SCALE = 0.55f
    private const val MAX_LOCAL_DEPTH_METERS = 0.45f
    private const val MAX_HAND_X_METERS = 8f
    private const val MAX_HAND_Y_METERS = 6f
    private const val MAX_HAND_DISTANCE_METERS = 6f
}