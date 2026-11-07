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
 * Maintains per-hand temporal state independently of the order MediaPipe returns detections.
 *
 * Association combines a short velocity prediction with handedness evidence. Each persistent
 * track owns its own image/world landmark filters and pinch state, so a reordered result cannot
 * transfer one hand's gesture or filter history to the other hand.
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
        private val worldFilter = OneEuroHandFilter(minCutoffHz = 1.0f, beta = 0.04f)
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
            val dt = ((timestampMs - lastMeasurementTimestampMs).coerceIn(1L, 120L)) / 1000f
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
                val dt = elapsedMs / 1000f
                val measuredVelocityX = ((nextCenter.x - lastRawCenter.x) / dt)
                    .coerceIn(-MAX_IMAGE_VELOCITY, MAX_IMAGE_VELOCITY)
                val measuredVelocityY = ((nextCenter.y - lastRawCenter.y) / dt)
                    .coerceIn(-MAX_IMAGE_VELOCITY, MAX_IMAGE_VELOCITY)
                velocityX += (measuredVelocityX - velocityX) * VELOCITY_RESPONSE
                velocityY += (measuredVelocityY - velocityY) * VELOCITY_RESPONSE
            } else if (elapsedMs > VELOCITY_RESET_GAP_MS) {
                velocityX = 0f
                velocityY = 0f
                imageFilter.reset()
                worldFilter.reset()
                pinchEstimator.reset()
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

            if (sideCandidate == candidate) sideCandidateCount++ else {
                sideCandidate = candidate
                sideCandidateCount = 1
            }

            val requiredVotes = when {
                side == HandSide.UNKNOWN && observation.handednessConfidence >= DIRECT_SIDE_CONFIDENCE -> 1
                side == HandSide.UNKNOWN -> SIDE_CONFIRMATION_FRAMES
                else -> SIDE_CORRECTION_FRAMES
            }
            if (sideCandidateCount >= requiredVotes) {
                side = candidate
                sideCandidateCount = 0
            }
        }
    }

    private val tracks = mutableListOf<Track>()
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

        tracks.removeAll { track ->
            val expired = timestampMs - track.lastSeenTimestampMs > retentionMs
            if (expired) track.reset()
            expired
        }

        val validObservations = observations
            .asSequence()
            .filter(::isUsable)
            .take(maxHands)
            .toList()
        val availableTracks = tracks.toList()
        val assignedTracks = mutableSetOf<Long>()
        val assignedObservations = mutableSetOf<Int>()

        // Greedy lowest-cost matching is sufficient for the supported one/two-hand model.
        // Side evidence breaks ties when the two hands cross or MediaPipe reorders its result.
        val candidates = buildList {
            validObservations.forEachIndexed { observationIndex, observation ->
                availableTracks.forEach { track ->
                    val cost = track.associationCost(observation, timestampMs)
                    if (cost <= MAX_ASSOCIATION_COST) {
                        add(Association(observationIndex, track.id, cost))
                    }
                }
            }
        }.sortedBy { it.cost }

        candidates.forEach { candidate ->
            if (candidate.observationIndex !in assignedObservations &&
                candidate.trackId !in assignedTracks
            ) {
                val track = availableTracks.first { it.id == candidate.trackId }
                track.accept(validObservations[candidate.observationIndex], timestampMs)
                assignedObservations += candidate.observationIndex
                assignedTracks += track.id
            }
        }

        validObservations.forEachIndexed { index, observation ->
            if (index in assignedObservations) return@forEachIndexed
            if (tracks.size < maxHands) {
                val track = Track(nextTrackId++, observation, timestampMs)
                tracks += track
                assignedTracks += track.id
                assignedObservations += index
            } else {
                // Reuse only a track that has been absent long enough to be clearly stale.
                val replaceable = tracks
                    .filter { it.id !in assignedTracks }
                    .filter { timestampMs - it.lastSeenTimestampMs >= REPLACE_STALE_TRACK_MS }
                    .maxByOrNull { it.lastSeenTimestampMs }
                if (replaceable != null) {
                    replaceable.reset()
                    tracks.remove(replaceable)
                    val track = Track(nextTrackId++, observation, timestampMs)
                    tracks += track
                    assignedTracks += track.id
                    assignedObservations += index
                }
            }
        }

        tracks.filter { it.id !in assignedTracks }.forEach { it.markMissing(timestampMs) }
        return tracks
            .filter { it.id in assignedTracks }
            .sortedBy { it.id }
            .map { it.output }
    }

    fun reset() {
        tracks.forEach { it.reset() }
        tracks.clear()
        lastFrameTimestampMs = null
    }

    private data class Association(
        val observationIndex: Int,
        val trackId: Long,
        val cost: Float
    )

    private fun isUsable(observation: HandObservation): Boolean =
        observation.points.size == LANDMARK_COUNT &&
            observation.points.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() } &&
            observation.handednessConfidence.isFinite()

    private fun centerOf(points: List<HandPoint>): Center {
        val sumX = PALM_CENTER_INDICES.sumOf { points[it].x.toDouble() }
        val sumY = PALM_CENTER_INDICES.sumOf { points[it].y.toDouble() }
        return Center(
            (sumX / PALM_CENTER_INDICES.size).toFloat(),
            (sumY / PALM_CENTER_INDICES.size).toFloat()
        )
    }

    private companion object {
        val PALM_CENTER_INDICES = intArrayOf(0, 5, 9, 13, 17)
        const val LANDMARK_COUNT = 21
        const val DEFAULT_RETENTION_MS = 350L
        const val DEFAULT_PINCH_RELEASE_DELAY_MS = 120L
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
    }
}
