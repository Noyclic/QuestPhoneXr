package com.noyclic.questphonexr.sandbox

/**
 * Measures rendered frame intervals over a rolling window.
 *
 * The first frame is the interval anchor and is not counted as a completed interval.
 */
class FrameRateCounter(
    private val windowDurationNanos: Long = DEFAULT_WINDOW_NANOS
) {
    private var windowStartNanos: Long? = null
    private var frameCount = 0
    var latestFramesPerSecond: Float? = null
        private set

    init {
        require(windowDurationNanos > 0L) { "FPS measurement window must be positive." }
    }

    fun onFrame(frameTimeNanos: Long): Float? {
        val start = windowStartNanos
        if (start == null || frameTimeNanos <= start) {
            windowStartNanos = frameTimeNanos
            frameCount = 1
            return null
        }

        frameCount += 1
        val elapsed = frameTimeNanos - start
        if (elapsed < windowDurationNanos) return null

        val measured = ((frameCount - 1).toDouble() * NANOS_PER_SECOND / elapsed)
            .toFloat()
            .takeIf { it.isFinite() && it >= 0f }
        latestFramesPerSecond = measured
        windowStartNanos = frameTimeNanos
        frameCount = 1
        return measured
    }

    fun reset() {
        windowStartNanos = null
        frameCount = 0
        latestFramesPerSecond = null
    }

    private companion object {
        const val DEFAULT_WINDOW_NANOS = 1_000_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
