package com.noyclic.questphonexr.tracking

import kotlin.math.sqrt

/** One MediaPipe result before temporal identity, filtering and gesture state are applied. */
internal data class HandObservation(
    val points: List<HandPoint>,
    val worldPoints: List<HandPoint>,
    val observedSide: HandSide,
    val handednessConfidence: Float
)

/**
 * Maintains stable per-hand state independently of MediaPipe's result-list order.
 *
 * The tracker is deliberately bounded to two hands. Its hot path uses fixed-size arrays and
 * direct comparisons instead of per-frame sorting, sets, candidate objects and sequences.
 */
internal class HandTrackManager(
    private val maxHands: Int = 2,
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
    private val pinchReleaseDelayMs: Long = DEFAULT_PINCH_RELEASE_DELAY_MS
) {
    private data class Center(val x: Float, val y: Float)

    private inner class Track(
        val id: Long,
        observation: HandObservation,
        timestampMs: Long
    ) {
        // Keep the palm/knuckles quiet while all five fingertips receive a faster
        // articulation response. Pinch decisions still use fresh, unfiltered landmarks.
        private val imageFilter = OneEuroHandFilter(
            minCutoffHz = IMAGE_MIN_CUTOFF_HZ,
            beta = IMAGE_BETA,
            derivativeCutoffHz = IMAGE_DERIVATIVE_CUTOFF_HZ,
            responsiveLandmarkA = THUMB_TIP_INDEX,
            responsiveLandmarkB = INDEX_TIP_INDEX,
            responsiveLandmarkIndices = FINGER_TIP_INDICES,
            responsiveMinCutoffHz = RESPONSIVE_TIP_MIN_CUTOFF_HZ,
            responsiveBeta = RESPONSIVE_TIP_BETA,
            derivativeDeadzone = IMAGE_DERIVATIVE_DEADZONE,
            responsiveDerivativeDeadzone = RESPONSIVE_TIP_DERIVATIVE_DEADZONE,
            maxLandmarkSpeedPerSecond = IMAGE_LANDMARK_MAX_SPEED,
            responsiveMaxLandmarkSpeedPerSecond = FINGERTIP_MAX_SPEED
        )
        // World landmarks are used for displayed pose mapping, not for gesture decisions.
        // A bounded metric-space filter contains occasional world-pose jumps while leaving
        // raw world samples available to PinchEstimator below.
        private val worldFilter = OneEuroHandFilter(
            minCutoffHz = WORLD_MIN_CUTOFF_HZ,
            beta = WORLD_BETA,
            derivativeCutoffHz = WORLD_DERIVATIVE_CUTOFF_HZ,
            responsiveLandmarkA = THUMB_TIP_INDEX,
            responsiveLandmarkB = INDEX_TIP_INDEX,
            responsiveLandmarkIndices = FINGER_TIP_INDICES,
            responsiveMinCutoffHz = WORLD_TIP_MIN_CUTOFF_HZ,
            responsiveBeta = WORLD_TIP_BETA,
            derivativeDeadzone = WORLD_DERIVATIVE_DEADZONE,
            responsiveDerivativeDeadzone = WORLD_TIP_DERIVATIVE_DEADZONE,
            maxLandmarkSpeedPerSecond = WORLD_LANDMARK_MAX_SPEED,
            responsiveMaxLandmarkSpeedPerSecond = WORLD_TIP_MAX_SPEED
        )
        private val pinchEstimator = PinchEstimator()

        var side: HandSide = HandSide.UNKNOWN
            private set
        private var sideCandidate = HandSide.UNKNOWN
        private var sideCandidateCount = 0
        private var lastRawCenter = centerOf(observation.points)
        private var center = lastRawCenter
        private var lastPalmWidth = palmWidthOf(observation.points)
        private var velocityX = 0f
        private var velocityY = 0f
        var lastSeenTimestampMs: Long = timestampMs
            private set
        private var lastMeasurementTimestampMs: Long = timestampMs
        private var pinchWasReleasedForLoss = false
        var output: TrackedHand = TrackedHand(emptyList())
            private set

        init {
            accept(observation, timestampMs)
        }

        fun associationCost(observation: HandObservation, timestampMs: Long): Float {
            val measuredCenter = centerOf(observation.points)
            val dt = ((timestampMs - lastMeasurementTimestampMs).coerceIn(1L, 120L)) / 1_000f
            val predictedX = (center.x + velocityX * dt).coerceIn(-0.2f, 1.2f)
            val predictedY = (center.y + velocityY * dt).coerceIn(-0.2f, 1.2f)
            val dx = measuredCenter.x - predictedX
            val dy = measuredCenter.y - predictedY
            val distance = sqrt(dx * dx + dy * dy)

            val confidentSide = observation.handednessConfidence >= SIDE_CONFIDENCE_FOR_MATCH
            val sideAdjustment = when {
                !confidentSide || observation.observedSide == HandSide.UNKNOWN ||
                    side == HandSide.UNKNOWN -> 0f
                observation.observedSide == side -> -SIDE_MATCH_BONUS
                else -> SIDE_MISMATCH_PENALTY
            }
            // Palm scale is a weak shape cue, not a hard identity rule: a hand can
            // approach the camera and change apparent size, so the penalty is bounded.
            val observedPalmWidth = palmWidthOf(observation.points)
            val scaleAdjustment = if (
                lastPalmWidth.isFinite() && observedPalmWidth.isFinite() &&
                lastPalmWidth > MIN_ASSOCIATION_PALM_WIDTH &&
                observedPalmWidth > MIN_ASSOCIATION_PALM_WIDTH
            ) {
                val scaleRatio = maxOf(lastPalmWidth, observedPalmWidth) /
                    minOf(lastPalmWidth, observedPalmWidth)
                (kotlin.math.ln(scaleRatio.toDouble()).toFloat() * PALM_SCALE_COST_WEIGHT)
                    .coerceIn(0f, MAX_PALM_SCALE_COST)
            } else {
                0f
            }
            return distance + sideAdjustment + scaleAdjustment
        }

        fun accept(observation: HandObservation, timestampMs: Long) {
            val nextCenter = centerOf(observation.points)
            val elapsedMs = timestampMs - lastMeasurementTimestampMs
            if (elapsedMs in 1L..VELOCITY_RESET_GAP_MS) {
                val dt = elapsedMs / 1_000f
                val measuredVelocityX = ((nextCenter.x - lastRawCenter.x) / dt)
                    .coerceIn(-MAX_IMAGE_VELOCITY, MAX_IMAGE_VELOCITY)
                val measuredVelocityY = ((nextCenter.y - lastRawCenter.y) / dt)
                    .coerceIn(-MAX_IMAGE_VELOCITY, MAX_IMAGE_VELOCITY)
                velocityX += (measuredVelocityX - velocityX) * VELOCITY_RESPONSE
                velocityY += (measuredVelocityY - velocityY) * VELOCITY_RESPONSE
            } else if (elapsedMs > VELOCITY_RESET_GAP_MS) {
                // Motion and gesture state become stale after a medium dropout.
                velocityX = 0f
                velocityY = 0f
                pinchEstimator.reset()
                // Keep the mesh filter warm for short/medium gaps, but do not blend
                // a pose across a long disappearance: reacquisition must snap to the
                // fresh hand pose once the gap exceeds the visual reset threshold.
                if (elapsedMs > FILTER_RESET_GAP_MS) {
                    imageFilter.reset()
                    worldFilter.reset()
                }
            }

            lastRawCenter = nextCenter
            center = nextCenter
            lastPalmWidth = palmWidthOf(observation.points)
            lastMeasurementTimestampMs = timestampMs
            lastSeenTimestampMs = timestampMs
            pinchWasReleasedForLoss = false
            updateSide(observation)

            val filteredPoints = imageFilter.update(observation.points, timestampMs)
            val filteredWorldPoints = if (observation.worldPoints.size == LANDMARK_COUNT) {
                worldFilter.update(observation.worldPoints, timestampMs)
            } else {
                worldFilter.reset()
                emptyList()
            }
            // The render mesh is filtered for visual stability, but gestures use the
            // freshest MediaPipe tips. Running pinch through the One Euro mesh and then
            // through its own time filter adds two stages of lag to the same finger motion.
            val pinch = pinchEstimator.update(
                points = observation.points,
                timestampMs = timestampMs,
                worldPoints = observation.worldPoints
            )
            output = TrackedHand(
                points = filteredPoints,
                pinchStrength = pinch.strength,
                isPinching = pinch.isPinching,
                pinchDistanceRatio = pinch.normalizedDistanceRatio,
                pinchUsesWorldLandmarks = pinch.usedWorldLandmarks,
                side = side,
                worldPoints = filteredWorldPoints,
                trackingId = id,
                rawPoints = observation.points,
                rawWorldPoints = observation.worldPoints,
                rawTimestampMs = timestampMs
            )
        }

        fun visibleOutput(timestampMs: Long): TrackedHand? {
            val ageMs = timestampMs - lastSeenTimestampMs
            if (ageMs > MAX_OUTPUT_HOLD_MS) return null
            if (ageMs <= 0L || output.points.isEmpty()) return output

            // Bridge a brief detector miss with a small, decelerating translation.
            // The displacement is always measured from the last real pose (never
            // accumulated), capped, and limited to image landmarks; hand-local world
            // landmarks and discrete pinch state are not predicted.
            val elapsedMs = ageMs.coerceAtMost(MAX_OCCLUSION_PREDICTION_MS).toFloat()
            val effectivePredictionMs = elapsedMs -
                elapsedMs * elapsedMs / (2f * MAX_OCCLUSION_PREDICTION_MS.toFloat())
            val predictionSeconds = effectivePredictionMs / 1_000f
            val dx = (velocityX * predictionSeconds)
                .coerceIn(-MAX_OCCLUSION_SHIFT, MAX_OCCLUSION_SHIFT)
            val dy = (velocityY * predictionSeconds)
                .coerceIn(-MAX_OCCLUSION_SHIFT, MAX_OCCLUSION_SHIFT)
            if (dx == 0f && dy == 0f) return output
            return output.copy(
                points = output.points.map { point ->
                    point.copy(x = point.x + dx, y = point.y + dy)
                }
            )
        }

        fun markMissing(timestampMs: Long) {
            if (!pinchWasReleasedForLoss &&
                timestampMs - lastSeenTimestampMs >= pinchReleaseDelayMs
            ) {
                pinchEstimator.reset()
                output = output.copy(
                    pinchStrength = 0f,
                    isPinching = false,
                    pinchDistanceRatio = Float.POSITIVE_INFINITY,
                    pinchUsesWorldLandmarks = false
                )
                pinchWasReleasedForLoss = true
            }
        }

        fun reset() {
            resetMotionAndGesture()
            imageFilter.reset()
            worldFilter.reset()
        }

        private fun resetMotionAndGesture() {
            velocityX = 0f
            velocityY = 0f
            imageFilter.reset()
            worldFilter.reset()
            pinchEstimator.reset()
        }

        private fun updateSide(observation: HandObservation) {
            val candidate = observation.observedSide
            if (candidate == HandSide.UNKNOWN ||
                observation.handednessConfidence < MIN_SIDE_CONFIDENCE
            ) return

            if (side == candidate) {
                sideCandidate = candidate
                sideCandidateCount = 0
                return
            }

            if (sideCandidate == candidate) {
                sideCandidateCount++
            } else {
                sideCandidate = candidate
                sideCandidateCount = 1
            }

            val requiredVotes = when {
                side == HandSide.UNKNOWN &&
                    observation.handednessConfidence >= DIRECT_SIDE_CONFIDENCE -> 1
                side == HandSide.UNKNOWN -> SIDE_CONFIRMATION_FRAMES
                else -> SIDE_CORRECTION_FRAMES
            }
            if (sideCandidateCount >= requiredVotes) {
                side = candidate
                sideCandidateCount = 0
            }
        }
    }

    private val tracks = ArrayList<Track>(maxHands)
    // Scratch storage is owned by this single-result manager and never escapes update().
    private val validObservations = ArrayList<HandObservation>(maxHands)
    private val assignedObservations = BooleanArray(MAX_TRACKS)
    private val assignedTrackIds = LongArray(MAX_TRACKS)
    private var nextTrackId = 1L
    private var lastFrameTimestampMs: Long? = null

    init {
        require(maxHands in 1..2) { "maxHands must be 1 or 2." }
        require(retentionMs > 0L)
        require(pinchReleaseDelayMs in 1L..retentionMs)
    }

    fun update(observations: List<HandObservation>, timestampMs: Long): List<TrackedHand> {
        val previousFrameTimestamp = lastFrameTimestampMs
        if (previousFrameTimestamp != null && timestampMs <= previousFrameTimestamp) {
            reset()
        }
        lastFrameTimestampMs = timestampMs

        var trackIndex = 0
        while (trackIndex < tracks.size) {
            val track = tracks[trackIndex]
            if (timestampMs - track.lastSeenTimestampMs > retentionMs) {
                track.reset()
                tracks.removeAt(trackIndex)
            } else {
                trackIndex++
            }
        }

        validObservations.clear()
        for (observation in observations) {
            if (validObservations.size >= maxHands) break
            if (isUsable(observation)) validObservations.add(observation)
        }

        assignedObservations.fill(false)
        assignedTrackIds.fill(0L)
        var assignedTrackCount = 0

        // Select the best complete two-hand assignment when both hands are visible.
        if (validObservations.size == 2 && tracks.size == 2) {
            val first = tracks[0]
            val second = tracks[1]
            val directA = first.associationCost(validObservations[0], timestampMs)
            val directB = second.associationCost(validObservations[1], timestampMs)
            val crossedA = second.associationCost(validObservations[0], timestampMs)
            val crossedB = first.associationCost(validObservations[1], timestampMs)

            val directValid = directA <= MAX_ASSOCIATION_COST && directB <= MAX_ASSOCIATION_COST
            val crossedValid = crossedA <= MAX_ASSOCIATION_COST && crossedB <= MAX_ASSOCIATION_COST
            if (directValid || crossedValid) {
                val useDirect = directValid &&
                    (!crossedValid || directA + directB <= crossedA + crossedB)
                if (useDirect) {
                    first.accept(validObservations[0], timestampMs)
                    second.accept(validObservations[1], timestampMs)
                } else {
                    second.accept(validObservations[0], timestampMs)
                    first.accept(validObservations[1], timestampMs)
                }
                assignedObservations[0] = true
                assignedObservations[1] = true
                assignedTrackIds[0] = first.id
                assignedTrackIds[1] = second.id
                assignedTrackCount = 2
            }
        }

        // Partial visibility or a rejected pair: make the lowest-cost remaining match.
        for (observationIndex in validObservations.indices) {
            if (assignedObservations[observationIndex]) continue
            var bestTrack: Track? = null
            var bestCost = MAX_ASSOCIATION_COST
            for (candidate in tracks) {
                if (isAssigned(candidate.id, assignedTrackIds, assignedTrackCount)) continue
                val cost = candidate.associationCost(validObservations[observationIndex], timestampMs)
                if (cost < bestCost) {
                    bestCost = cost
                    bestTrack = candidate
                }
            }
            if (bestTrack != null) {
                bestTrack.accept(validObservations[observationIndex], timestampMs)
                assignedObservations[observationIndex] = true
                if (assignedTrackCount < assignedTrackIds.size) {
                    assignedTrackIds[assignedTrackCount++] = bestTrack.id
                }
            }
        }

        // Create new tracks or recycle only an absent, stale track.
        for (observationIndex in validObservations.indices) {
            if (assignedObservations[observationIndex]) continue
            if (tracks.size >= maxHands) {
                var replaceIndex = -1
                var oldestSeen = Long.MAX_VALUE
                for (index in tracks.indices) {
                    val candidate = tracks[index]
                    if (isAssigned(candidate.id, assignedTrackIds, assignedTrackCount)) continue
                    if (timestampMs - candidate.lastSeenTimestampMs >= REPLACE_STALE_TRACK_MS &&
                        candidate.lastSeenTimestampMs < oldestSeen
                    ) {
                        oldestSeen = candidate.lastSeenTimestampMs
                        replaceIndex = index
                    }
                }
                if (replaceIndex >= 0) {
                    tracks.removeAt(replaceIndex).reset()
                }
            }
            if (tracks.size < maxHands) {
                val created = Track(nextTrackId++, validObservations[observationIndex], timestampMs)
                tracks.add(created)
                assignedObservations[observationIndex] = true
                if (assignedTrackCount < assignedTrackIds.size) {
                    assignedTrackIds[assignedTrackCount++] = created.id
                }
            }
        }

        for (track in tracks) {
            if (!isAssigned(track.id, assignedTrackIds, assignedTrackCount)) {
                track.markMissing(timestampMs)
            }
        }

        val visible = ArrayList<TrackedHand>(maxHands)
        for (track in tracks) {
            val current = if (isAssigned(track.id, assignedTrackIds, assignedTrackCount)) {
                track.output
            } else {
                track.visibleOutput(timestampMs)
            }
            if (current != null) visible.add(current)
        }
        return visible
    }

    fun reset() {
        for (track in tracks) track.reset()
        tracks.clear()
        validObservations.clear()
        assignedObservations.fill(false)
        assignedTrackIds.fill(0L)
        lastFrameTimestampMs = null
    }

    private fun isAssigned(id: Long, assignedIds: LongArray, count: Int): Boolean {
        for (index in 0 until count) {
            if (assignedIds[index] == id) return true
        }
        return false
    }

    private fun isUsable(observation: HandObservation): Boolean {
        if (observation.points.size != LANDMARK_COUNT ||
            !observation.handednessConfidence.isFinite()
        ) return false
        for (point in observation.points) {
            if (!point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite()) return false
        }
        return true
    }

    private fun centerOf(points: List<HandPoint>): Center {
        var sumX = 0f
        var sumY = 0f
        for (index in PALM_CENTER_INDICES) {
            sumX += points[index].x
            sumY += points[index].y
        }
        return Center(sumX / PALM_CENTER_INDICES.size, sumY / PALM_CENTER_INDICES.size)
    }

    private fun palmWidthOf(points: List<HandPoint>): Float {
        val indexMcp = points[5]
        val pinkyMcp = points[17]
        val dx = indexMcp.x - pinkyMcp.x
        val dy = indexMcp.y - pinkyMcp.y
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        val PALM_CENTER_INDICES = intArrayOf(0, 5, 9, 13, 17)
        const val LANDMARK_COUNT = 21
        const val THUMB_TIP_INDEX = 4
        const val INDEX_TIP_INDEX = 8
        const val IMAGE_MIN_CUTOFF_HZ = 1.65f
        const val IMAGE_BETA = 0.28f
        const val IMAGE_DERIVATIVE_CUTOFF_HZ = 0.80f
        const val IMAGE_DERIVATIVE_DEADZONE = 0.18f
        const val RESPONSIVE_TIP_MIN_CUTOFF_HZ = 3.0f
        const val RESPONSIVE_TIP_BETA = 0.72f
        const val RESPONSIVE_TIP_DERIVATIVE_DEADZONE = 0.10f
        val FINGER_TIP_INDICES = intArrayOf(12, 16, 20)
        const val IMAGE_LANDMARK_MAX_SPEED = 18f
        const val FINGERTIP_MAX_SPEED = 28f
        const val MAX_TRACKS = 2
        const val DEFAULT_RETENTION_MS = 350L
        const val DEFAULT_PINCH_RELEASE_DELAY_MS = 120L
        // The persistent EndXR service may run inference at 10 Hz (100 ms intervals).
        // Keep a missed result visible long enough for the next inference to reacquire it.
        const val MAX_OUTPUT_HOLD_MS = 140L
        const val MAX_OCCLUSION_PREDICTION_MS = 100L
        const val MAX_OCCLUSION_SHIFT = 0.08f
        const val MIN_ASSOCIATION_PALM_WIDTH = 0.015f
        const val PALM_SCALE_COST_WEIGHT = 0.12f
        const val MAX_PALM_SCALE_COST = 0.18f
        const val VELOCITY_RESET_GAP_MS = 120L
        const val FILTER_RESET_GAP_MS = 180L
        const val REPLACE_STALE_TRACK_MS = 180L
        const val MAX_IMAGE_VELOCITY = 3.0f
        const val VELOCITY_RESPONSE = 0.45f
        const val MAX_ASSOCIATION_COST = 0.78f
        const val SIDE_CONFIDENCE_FOR_MATCH = 0.75f
        const val MIN_SIDE_CONFIDENCE = 0.45f
        const val DIRECT_SIDE_CONFIDENCE = 0.82f
        const val SIDE_CONFIRMATION_FRAMES = 2
        const val SIDE_CORRECTION_FRAMES = 3
        const val SIDE_MATCH_BONUS = 0.10f
        const val SIDE_MISMATCH_PENALTY = 0.36f
        const val WORLD_MIN_CUTOFF_HZ = 1.55f
        const val WORLD_BETA = 0.20f
        const val WORLD_DERIVATIVE_CUTOFF_HZ = 0.75f
        const val WORLD_DERIVATIVE_DEADZONE = 0.012f
        const val WORLD_TIP_MIN_CUTOFF_HZ = 2.6f
        const val WORLD_TIP_BETA = 0.34f
        const val WORLD_TIP_DERIVATIVE_DEADZONE = 0.006f
        const val WORLD_LANDMARK_MAX_SPEED = 2.4f
        const val WORLD_TIP_MAX_SPEED = 4.0f
    }
}
