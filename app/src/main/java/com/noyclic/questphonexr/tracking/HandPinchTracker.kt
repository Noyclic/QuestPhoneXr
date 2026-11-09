package com.noyclic.questphonexr.tracking

/**
 * Keeps pinch hysteresis state attached to a physical hand, not MediaPipe's result-list
 * position. The first LEFT/RIGHT detection gets a stable handedness key; UNKNOWN hands
 * use their current result slot because no reliable identity was supplied by the model.
 *
 * Missing hands are removed at the end of each result frame, so reacquisition starts from
 * a neutral gesture state rather than inheriting a stale pinch.
 */
internal data class HandPinchSample(
    val side: HandSide,
    val points: List<HandPoint>
)

internal class HandPinchTracker(
    private val estimatorFactory: () -> PinchEstimator = { PinchEstimator() }
) {
    private data class TrackKey(val side: HandSide, val fallbackSlot: Int)

    private val estimators = mutableMapOf<TrackKey, PinchEstimator>()

    fun updateFrame(samples: List<HandPinchSample>, timestampMs: Long): List<PinchSignal> {
        val activeKeys = HashSet<TrackKey>(samples.size)
        val seenKnownSides = HashSet<HandSide>()
        val signals = samples.mapIndexed { index, sample ->
            val key = if (
                sample.side != HandSide.UNKNOWN && seenKnownSides.add(sample.side)
            ) {
                TrackKey(sample.side, NO_FALLBACK_SLOT)
            } else {
                TrackKey(sample.side, index)
            }
            activeKeys += key
            estimators.getOrPut(key, estimatorFactory).update(sample.points, timestampMs)
        }

        val lostKeys = estimators.keys.filterNot(activeKeys::contains)
        lostKeys.forEach { key -> estimators.remove(key)?.reset() }
        return signals
    }

    fun reset() {
        estimators.values.forEach(PinchEstimator::reset)
        estimators.clear()
    }

    private companion object {
        const val NO_FALLBACK_SLOT = -1
    }
}
