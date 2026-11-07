package com.noyclic.questphonexr.tracking

import kotlin.math.sqrt

internal enum class NaturalHandGesture {
    NONE,
    OPEN_PALM,
    POINT,
    VICTORY,
    THUMBS_UP
}

internal enum class FingerPose {
    EXTENDED,
    CURLED,
    UNKNOWN
}

internal data class FingerState(
    val pose: FingerPose = FingerPose.UNKNOWN,
    val extensionScore: Float = 0f,
    val confidence: Float = 0f
) {
    companion object {
        val UNKNOWN = FingerState()
    }
}

internal data class NaturalGestureResult(
    val gesture: NaturalHandGesture = NaturalHandGesture.NONE,
    val confidence: Float = 0f,
    val thumb: FingerState = FingerState.UNKNOWN,
    val index: FingerState = FingerState.UNKNOWN,
    val middle: FingerState = FingerState.UNKNOWN,
    val ring: FingerState = FingerState.UNKNOWN,
    val little: FingerState = FingerState.UNKNOWN,
    val usedWorldLandmarks: Boolean = false,
    val timestampMs: Long = 0L
) {
    companion object {
        val EMPTY = NaturalGestureResult()
    }
}

/**
 * Recognizes deliberate static hand poses from the newest MediaPipe observation.
 *
 * This class deliberately consumes TrackedHand.rawPoints/rawWorldPoints rather
 * than the render-filtered mesh. Duplicate held observations are ignored, so a
 * short visibility hold cannot satisfy the temporal confirmation requirement.
 */
internal class NaturalGestureClassifier {
    private data class Measurement(
        val thumb: FingerState,
        val index: FingerState,
        val middle: FingerState,
        val ring: FingerState,
        val little: FingerState,
        val usedWorldLandmarks: Boolean
    )

    private data class Candidate(
        val gesture: NaturalHandGesture,
        val confidence: Float
    )

    private var previousTrackingId: Long? = null
    private var previousTimestampMs: Long? = null
    private var pendingGesture = NaturalHandGesture.NONE
    private var pendingFrames = 0
    private var activeGesture = NaturalHandGesture.NONE
    private var activeConfidence = 0f
    private var lastResult = NaturalGestureResult.EMPTY

    @Synchronized
    fun update(hand: TrackedHand?, frameTimestampMs: Long): NaturalGestureResult {
        if (hand == null) {
            reset()
            return NaturalGestureResult.EMPTY.copy(timestampMs = frameTimestampMs)
        }

        val sampleTimestampMs = hand.rawTimestampMs.takeIf { it >= 0L } ?: frameTimestampMs
        if (previousTrackingId != null && previousTrackingId != hand.trackingId) {
            resetState()
        }

        val previousTimestamp = previousTimestampMs
        if (previousTimestamp != null) {
            if (sampleTimestampMs == previousTimestamp) {
                // A held visible pose is the same camera observation, not a new sample.
                return lastResult
            }
            if (sampleTimestampMs < previousTimestamp ||
                sampleTimestampMs - previousTimestamp > MAX_SAMPLE_GAP_MS
            ) {
                resetState()
            }
        }

        previousTrackingId = hand.trackingId
        previousTimestampMs = sampleTimestampMs

        val measured = measure(hand)
        if (measured == null) {
            resetState()
            return NaturalGestureResult.EMPTY.copy(timestampMs = sampleTimestampMs)
        }

        val candidate = classify(measured)
        val acceptedGesture = if (candidate.confidence >= MIN_CANDIDATE_CONFIDENCE) {
            candidate.gesture
        } else {
            NaturalHandGesture.NONE
        }

        if (acceptedGesture == pendingGesture) {
            pendingFrames += 1
        } else {
            pendingGesture = acceptedGesture
            pendingFrames = 1
        }

        if (pendingFrames >= REQUIRED_CONFIRMATION_FRAMES) {
            activeGesture = acceptedGesture
            activeConfidence = if (acceptedGesture == NaturalHandGesture.NONE) {
                0f
            } else {
                candidate.confidence
            }
        } else if (acceptedGesture == activeGesture && activeGesture != NaturalHandGesture.NONE) {
            activeConfidence = candidate.confidence
        }

        return NaturalGestureResult(
            gesture = activeGesture,
            confidence = activeConfidence.coerceIn(0f, 1f),
            thumb = measured.thumb,
            index = measured.index,
            middle = measured.middle,
            ring = measured.ring,
            little = measured.little,
            usedWorldLandmarks = measured.usedWorldLandmarks,
            timestampMs = sampleTimestampMs
        ).also { lastResult = it }
    }

    @Synchronized
    fun reset() {
        resetState()
    }

    private fun resetState() {
        previousTrackingId = null
        previousTimestampMs = null
        pendingGesture = NaturalHandGesture.NONE
        pendingFrames = 0
        activeGesture = NaturalHandGesture.NONE
        activeConfidence = 0f
        lastResult = NaturalGestureResult.EMPTY
    }

    private fun measure(hand: TrackedHand): Measurement? {
        val world = hand.rawWorldPoints
        val useWorld = world.size >= LANDMARK_COUNT &&
            world.take(LANDMARK_COUNT).all(::isFinite) &&
            distance(world[5], world[17], useDepth = true) in MIN_WORLD_PALM_WIDTH..MAX_WORLD_PALM_WIDTH

        val points = if (useWorld) world else hand.rawPoints
        if (points.size < LANDMARK_COUNT ||
            points.take(LANDMARK_COUNT).any { !isFinite(it) }
        ) return null

        val depth = useWorld
        val wrist = points[0]
        val thumb = measureFinger(points, wrist, 1, 2, 3, 4, depth) ?: FingerState.UNKNOWN
        val index = measureFinger(points, wrist, 5, 6, 7, 8, depth) ?: FingerState.UNKNOWN
        val middle = measureFinger(points, wrist, 9, 10, 11, 12, depth) ?: FingerState.UNKNOWN
        val ring = measureFinger(points, wrist, 13, 14, 15, 16, depth) ?: FingerState.UNKNOWN
        val little = measureFinger(points, wrist, 17, 18, 19, 20, depth) ?: FingerState.UNKNOWN

        return Measurement(thumb, index, middle, ring, little, useWorld)
    }

    private fun measureFinger(
        points: List<HandPoint>,
        wrist: HandPoint,
        mcp: Int,
        pip: Int,
        dip: Int,
        tip: Int,
        useDepth: Boolean
    ): FingerState? {
        val base = points[mcp]
        val proximal = points[pip]
        val intermediate = points[dip]
        val fingertip = points[tip]
        val first = distance(base, proximal, useDepth)
        val second = distance(proximal, intermediate, useDepth)
        val third = distance(intermediate, fingertip, useDepth)
        val pathLength = first + second + third
        val directLength = distance(base, fingertip, useDepth)
        val baseReach = distance(wrist, base, useDepth)
        if (!pathLength.isFinite() || pathLength < MIN_FINGER_PATH ||
            !baseReach.isFinite() || baseReach < MIN_BASE_REACH ||
            !directLength.isFinite()
        ) return null

        val straightness = (directLength / pathLength).coerceIn(0f, 1f)
        val reachRatio = distance(wrist, fingertip, useDepth) / baseReach
        if (!reachRatio.isFinite()) return null

        val straightnessScore = ((straightness - STRAIGHTNESS_FLOOR) /
            (STRAIGHTNESS_CEILING - STRAIGHTNESS_FLOOR)).coerceIn(0f, 1f)
        val reachScore = ((reachRatio - REACH_FLOOR) /
            (REACH_CEILING - REACH_FLOOR)).coerceIn(0f, 1f)
        val score = (straightnessScore * STRAIGHTNESS_WEIGHT +
            reachScore * REACH_WEIGHT).coerceIn(0f, 1f)

        val pose = when {
            score >= EXTENDED_THRESHOLD -> FingerPose.EXTENDED
            score <= CURLED_THRESHOLD -> FingerPose.CURLED
            else -> FingerPose.UNKNOWN
        }
        val confidence = when (pose) {
            FingerPose.EXTENDED -> 0.55f + 0.45f *
                ((score - EXTENDED_THRESHOLD) / (1f - EXTENDED_THRESHOLD)).coerceIn(0f, 1f)
            FingerPose.CURLED -> 0.55f + 0.45f *
                ((CURLED_THRESHOLD - score) / CURLED_THRESHOLD).coerceIn(0f, 1f)
            FingerPose.UNKNOWN -> 0f
        }
        return FingerState(pose, score, confidence)
    }

    private fun classify(measured: Measurement): Candidate {
        val thumb = measured.thumb
        val index = measured.index
        val middle = measured.middle
        val ring = measured.ring
        val little = measured.little

        fun isPose(state: FingerState, pose: FingerPose) = state.pose == pose
        fun confidence(vararg states: FingerState): Float =
            states.minOfOrNull { it.confidence } ?: 0f

        val gesture = when {
            isPose(thumb, FingerPose.EXTENDED) &&
                isPose(index, FingerPose.EXTENDED) &&
                isPose(middle, FingerPose.EXTENDED) &&
                isPose(ring, FingerPose.EXTENDED) &&
                isPose(little, FingerPose.EXTENDED) -> NaturalHandGesture.OPEN_PALM

            isPose(index, FingerPose.EXTENDED) &&
                isPose(middle, FingerPose.EXTENDED) &&
                isPose(ring, FingerPose.CURLED) &&
                isPose(little, FingerPose.CURLED) -> NaturalHandGesture.VICTORY

            isPose(index, FingerPose.EXTENDED) &&
                isPose(middle, FingerPose.CURLED) &&
                isPose(ring, FingerPose.CURLED) &&
                isPose(little, FingerPose.CURLED) -> NaturalHandGesture.POINT

            isPose(thumb, FingerPose.EXTENDED) &&
                isPose(index, FingerPose.CURLED) &&
                isPose(middle, FingerPose.CURLED) &&
                isPose(ring, FingerPose.CURLED) &&
                isPose(little, FingerPose.CURLED) -> NaturalHandGesture.THUMBS_UP

            else -> NaturalHandGesture.NONE
        }

        val confidence = when (gesture) {
            NaturalHandGesture.OPEN_PALM -> confidence(thumb, index, middle, ring, little)
            NaturalHandGesture.POINT -> confidence(index, middle, ring, little)
            NaturalHandGesture.VICTORY -> confidence(index, middle, ring, little)
            NaturalHandGesture.THUMBS_UP -> confidence(thumb, index, middle, ring, little)
            NaturalHandGesture.NONE -> 0f
        }.let { score -> if (measured.usedWorldLandmarks) score else score * IMAGE_FALLBACK_CONFIDENCE }

        return Candidate(gesture, confidence)
    }

    private fun isFinite(point: HandPoint): Boolean =
        point.x.isFinite() && point.y.isFinite() && point.z.isFinite()

    private fun distance(a: HandPoint, b: HandPoint, useDepth: Boolean): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = if (useDepth) a.z - b.z else 0f
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private companion object {
        const val LANDMARK_COUNT = 21
        const val MIN_WORLD_PALM_WIDTH = 0.02f
        const val MAX_WORLD_PALM_WIDTH = 0.16f
        const val MIN_FINGER_PATH = 0.008f
        const val MIN_BASE_REACH = 0.008f
        const val STRAIGHTNESS_FLOOR = 0.55f
        const val STRAIGHTNESS_CEILING = 0.97f
        const val REACH_FLOOR = 0.94f
        const val REACH_CEILING = 1.38f
        const val STRAIGHTNESS_WEIGHT = 0.78f
        const val REACH_WEIGHT = 0.22f
        const val EXTENDED_THRESHOLD = 0.72f
        const val CURLED_THRESHOLD = 0.40f
        const val MIN_CANDIDATE_CONFIDENCE = 0.42f
        const val IMAGE_FALLBACK_CONFIDENCE = 0.84f
        const val REQUIRED_CONFIRMATION_FRAMES = 2
        const val MAX_SAMPLE_GAP_MS = 250L
    }
}
