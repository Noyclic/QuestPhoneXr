package com.noyclic.questphonexr.tracking

/**
 * Adaptive camera-plane stabilizer for the estimated OpenXR palm origin.
 *
 * It filters only normalized image-space X/Y. Depth remains owned by the
 * existing monocular depth estimator, and raw image/world observations remain
 * untouched for pinch and gesture classification.
 */
internal class AdaptiveHandRootPositionFilter(
    minCutoffHz: Float = DEFAULT_MIN_CUTOFF_HZ,
    beta: Float = DEFAULT_BETA,
    derivativeCutoffHz: Float = DEFAULT_DERIVATIVE_CUTOFF_HZ,
    private val maximumPositionSpeedMetersPerSecond: Float = DEFAULT_MAX_POSITION_SPEED
) {
    private val filter = OneEuroHandFilter(
        minCutoffHz = minCutoffHz,
        beta = beta,
        derivativeCutoffHz = derivativeCutoffHz,
        resetAfterGapMs = MAX_SAMPLE_GAP_MS,
        derivativeDeadzone = IMAGE_JITTER_DEADZONE
    )
    private var trackingId = NO_TRACKING_ID
    private var previousAcceptedInput: HandRootPosition? = null
    private var previousTimestampMs = Long.MIN_VALUE
    private var previousOutput: HandRootPosition? = null

    init {
        require(minCutoffHz > 0f && minCutoffHz.isFinite())
        require(beta >= 0f && beta.isFinite())
        require(derivativeCutoffHz > 0f && derivativeCutoffHz.isFinite())
        require(maximumPositionSpeedMetersPerSecond > 0f &&
            maximumPositionSpeedMetersPerSecond.isFinite())
    }

    @Synchronized
    fun update(
        x: Float,
        y: Float,
        nextTrackingId: Long,
        timestampMs: Long,
        metersPerNormalizedUnit: Float = 1f
    ): HandRootPosition? {
        if (!x.isFinite() || !y.isFinite() || timestampMs < 0L ||
            !metersPerNormalizedUnit.isFinite() || metersPerNormalizedUnit <= 0f
        ) {
            reset()
            return null
        }
        if (trackingId != NO_TRACKING_ID && trackingId != nextTrackingId) {
            filter.reset()
            clearSampleHistory()
        }
        trackingId = nextTrackingId
        val previousInput = previousAcceptedInput
        val previousTimestamp = previousTimestampMs
        if (previousInput != null && previousTimestamp != Long.MIN_VALUE) {
            val elapsedMs = timestampMs - previousTimestamp
            if (elapsedMs == 0L) return previousOutput
            if (elapsedMs < 0L || elapsedMs > MAX_SAMPLE_GAP_MS) {
                filter.reset()
                clearSampleHistory()
            }
        }
        var acceptedX = x
        var acceptedY = y
        val lastInput = previousAcceptedInput
        val lastTimestamp = previousTimestampMs
        if (lastInput != null && lastTimestamp != Long.MIN_VALUE) {
            val elapsedMs = timestampMs - lastTimestamp
            if (elapsedMs in 1L..MAX_SAMPLE_GAP_MS) {
                val deltaX = (x - lastInput.x) * metersPerNormalizedUnit
                val deltaY = (y - lastInput.y) * metersPerNormalizedUnit
                val distance = kotlin.math.sqrt(deltaX * deltaX + deltaY * deltaY)
                val maximumStep = maximumPositionSpeedMetersPerSecond * (elapsedMs / 1_000f)
                if (distance.isFinite() && distance > maximumStep && distance > 0f) {
                    val scale = maximumStep / distance
                    acceptedX = lastInput.x + (x - lastInput.x) * scale
                    acceptedY = lastInput.y + (y - lastInput.y) * scale
                }
            }
        }
        val filtered = filter.update(
            listOf(HandPoint(acceptedX, acceptedY, 0f)),
            timestampMs
        ).firstOrNull() ?: return null
        val output = HandRootPosition(filtered.x, filtered.y)
        previousAcceptedInput = HandRootPosition(acceptedX, acceptedY)
        previousTimestampMs = timestampMs
        previousOutput = output
        return output
    }

    private fun clearSampleHistory() {
        previousAcceptedInput = null
        previousTimestampMs = Long.MIN_VALUE
        previousOutput = null
    }

    @Synchronized
    fun reset() {
        filter.reset()
        trackingId = NO_TRACKING_ID
        clearSampleHistory()
    }

    internal data class HandRootPosition(val x: Float, val y: Float)

    private companion object {
        const val NO_TRACKING_ID = Long.MIN_VALUE
        const val MAX_SAMPLE_GAP_MS = 220L
        const val DEFAULT_MIN_CUTOFF_HZ = 2.4f
        const val DEFAULT_BETA = 0.45f
        const val DEFAULT_DERIVATIVE_CUTOFF_HZ = 1.2f
        const val IMAGE_JITTER_DEADZONE = 0.0015f
        const val DEFAULT_MAX_POSITION_SPEED = 8f
    }
}
