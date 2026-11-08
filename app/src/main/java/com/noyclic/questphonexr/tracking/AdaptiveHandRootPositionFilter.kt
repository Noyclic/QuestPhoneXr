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
    derivativeCutoffHz: Float = DEFAULT_DERIVATIVE_CUTOFF_HZ
) {
    private val filter = OneEuroHandFilter(
        minCutoffHz = minCutoffHz,
        beta = beta,
        derivativeCutoffHz = derivativeCutoffHz,
        resetAfterGapMs = MAX_SAMPLE_GAP_MS,
        derivativeDeadzone = IMAGE_JITTER_DEADZONE
    )
    private var trackingId = NO_TRACKING_ID

    init {
        require(minCutoffHz > 0f && minCutoffHz.isFinite())
        require(beta >= 0f && beta.isFinite())
        require(derivativeCutoffHz > 0f && derivativeCutoffHz.isFinite())
    }

    @Synchronized
    fun update(x: Float, y: Float, nextTrackingId: Long, timestampMs: Long): HandRootPosition? {
        if (!x.isFinite() || !y.isFinite() || timestampMs < 0L) {
            reset()
            return null
        }
        if (trackingId != NO_TRACKING_ID && trackingId != nextTrackingId) {
            filter.reset()
        }
        trackingId = nextTrackingId

        val filtered = filter.update(
            listOf(HandPoint(x, y, 0f)),
            timestampMs
        ).firstOrNull() ?: return null
        return HandRootPosition(filtered.x, filtered.y)
    }

    @Synchronized
    fun reset() {
        filter.reset()
        trackingId = NO_TRACKING_ID
    }

    internal data class HandRootPosition(val x: Float, val y: Float)

    private companion object {
        const val NO_TRACKING_ID = Long.MIN_VALUE
        const val MAX_SAMPLE_GAP_MS = 220L
        const val DEFAULT_MIN_CUTOFF_HZ = 2.4f
        const val DEFAULT_BETA = 0.45f
        const val DEFAULT_DERIVATIVE_CUTOFF_HZ = 1.2f
        const val IMAGE_JITTER_DEADZONE = 0.0015f
    }
}
