package com.noyclic.questphonexr.runtime

/**
 * Bounded retry budget for rebuilding a camera/inference pipeline.
 *
 * A long stable run clears the previous failure streak. Rapid repeated failures use
 * increasing delays and eventually stop retrying instead of spinning forever.
 */
internal data class TrackingRecoveryRetry(
    val attempt: Int,
    val delayMs: Long
)

internal class TrackingRecoveryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val retryDelaysMs: LongArray = DEFAULT_RETRY_DELAYS_MS,
    private val healthyResetAfterMs: Long = DEFAULT_HEALTHY_RESET_AFTER_MS
) {
    private var attempts = 0
    private var lastFailureAtMs = -1L

    init {
        require(maxAttempts > 0)
        require(retryDelaysMs.isNotEmpty() && retryDelaysMs.all { it >= 0L })
        require(healthyResetAfterMs > 0L)
    }

    fun nextRetry(nowMs: Long): TrackingRecoveryRetry? {
        if (lastFailureAtMs >= 0L && nowMs < lastFailureAtMs) reset()
        if (attempts >= maxAttempts) return null
        attempts += 1
        lastFailureAtMs = nowMs
        val delayIndex = (attempts - 1).coerceAtMost(retryDelaysMs.lastIndex)
        return TrackingRecoveryRetry(attempts, retryDelaysMs[delayIndex])
    }

    fun observeHealthy(nowMs: Long) {
        resetAfterStableWindow(nowMs)
    }

    fun currentAttemptCount(): Int = attempts

    fun reset() {
        attempts = 0
        lastFailureAtMs = -1L
    }

    private fun resetAfterStableWindow(nowMs: Long) {
        if (lastFailureAtMs < 0L) return
        if (nowMs < lastFailureAtMs ||
            nowMs - lastFailureAtMs >= healthyResetAfterMs
        ) {
            reset()
        }
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_HEALTHY_RESET_AFTER_MS = 15_000L
        val DEFAULT_RETRY_DELAYS_MS = longArrayOf(300L, 900L, 1_800L)
    }
}
