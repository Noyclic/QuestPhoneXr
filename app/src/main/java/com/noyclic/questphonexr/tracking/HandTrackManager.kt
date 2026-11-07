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
        private val imageFilter = OneEuroHandFilter()
        private val worldFilter = OneEuroHandFilter(
            minCutoffHz = WORLD_MIN_CUTOFF_HZ,
            beta = WORLD_BETA
        )
        private val pinchEstimator = PinchEstimator()

        var side: HandSide = HandSide.UNKNOWN
            private set
        private var sideCandidate = HandSide.UNKNOWN
        private var sideCandidateCount = 0
        private var lastRawCenter = centerOf(observation.points)
        private var center = lastRawCenter
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
            return distance + sideAdjustment
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
                resetMotionAndGesture()
            }

            lastRawCenter = nextCenter
            center = nextCenter
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
            val pinch = pinchEstimator.update(filteredPoints, timestampMs)
            output = TrackedHand(
                points = filteredPoints,
                pinchStrength = pinch.strength,
                isPinching = pinch.isPinching,
                side = side,
                worldPoints = filteredWorldPoints,
                trackingId = id
            )
        }

        fun visibleOutput(timestampMs: Long): TrackedHand? =
            output.takeIf { timestampMs - lastSeenTimestampMs <= MAX_OUTPUT_HOLD_MS }

        fun markMissing(timestampMs: Long) {
            if (!pinchWasReleasedForLoss &&
                timestampMs - lastSeenTimestampMs >= pinchReleaseDelayMs
            ) {
                pinchEstimator.reset()
                output = output.copy(pinchStrength = 0f, isPinching = false)
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

    private companion object {
        val PALM_CENTER_INDICES = intArrayOf(0, 5, 9, 13, 17)
        const val LANDMARK_COUNT = 21
        const val MAX_TRACKS = 2
        const val DEFAULT_RETENTION_MS = 350L
        const val DEFAULT_PINCH_RELEASE_DELAY_MS = 120L
        const val MAX_OUTPUT_HOLD_MS = 80L
        const val VELOCITY_RESET_GAP_MS = 120L
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
        const val WORLD_MIN_CUTOFF_HZ = 1.7f
        const val WORLD_BETA = 0.18f
    }
}
