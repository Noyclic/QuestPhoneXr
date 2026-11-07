package com.noyclic.questphonexr.tracking

import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.exp

/**
 * Adaptive One Euro filter for MediaPipe landmarks.
 *
 * Low-speed motion receives stronger jitter reduction; fast motion raises the cutoff
 * so the hand does not feel as if it is being dragged behind the user's fingers.
 * Timestamps are supplied by MediaPipe and may be irregular in LIVE_STREAM mode.
 */
internal class OneEuroHandFilter(
    private val minCutoffHz: Float = 1.2f,
    private val beta: Float = 0.25f,
    private val derivativeCutoffHz: Float = 1.0f,
    private val resetAfterGapMs: Long = 250L
) {
    private data class AxisState(
        var value: Float,
        var derivative: Float
    )

    private var previousTimestampMs: Long? = null
    private var axes: Array<AxisState>? = null

    init {
        require(minCutoffHz > 0f && minCutoffHz.isFinite())
        require(beta >= 0f && beta.isFinite())
        require(derivativeCutoffHz > 0f && derivativeCutoffHz.isFinite())
        require(resetAfterGapMs > 0L)
    }

    fun update(points: List<HandPoint>, timestampMs: Long): List<HandPoint> {
        if (points.isEmpty()) {
            reset()
            return emptyList()
        }
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) {
            reset()
            return points
        }

        val previousTime = previousTimestampMs
        val state = axes
        val values = flatten(points)
        if (previousTime == null || state == null || state.size != values.size ||
            timestampMs <= previousTime || timestampMs - previousTime > resetAfterGapMs
        ) {
            axes = Array(values.size) { index -> AxisState(values[index], 0f) }
            previousTimestampMs = timestampMs
            return points.toList()
        }

        val dtSeconds = ((timestampMs - previousTime).toFloat() / 1000f)
            .coerceIn(MIN_DT_SECONDS, MAX_DT_SECONDS)
        val derivativeAlpha = smoothingAlpha(derivativeCutoffHz, dtSeconds)
        val filtered = FloatArray(values.size)

        for (index in values.indices) {
            val axis = state[index]
            val rawDerivative = (values[index] - axis.value) / dtSeconds
            axis.derivative += derivativeAlpha * (rawDerivative - axis.derivative)
            val cutoff = minCutoffHz + beta * abs(axis.derivative)
            val valueAlpha = smoothingAlpha(cutoff, dtSeconds)
            axis.value += valueAlpha * (values[index] - axis.value)
            filtered[index] = axis.value
        }

        previousTimestampMs = timestampMs
        return unflatten(filtered, points.size)
    }

    fun reset() {
        previousTimestampMs = null
        axes = null
    }

    private fun flatten(points: List<HandPoint>): FloatArray {
        val values = FloatArray(points.size * 3)
        points.forEachIndexed { index, point ->
            values[index * 3] = point.x
            values[index * 3 + 1] = point.y
            values[index * 3 + 2] = point.z
        }
        return values
    }

    private fun unflatten(values: FloatArray, pointCount: Int): List<HandPoint> =
        List(pointCount) { index ->
            HandPoint(values[index * 3], values[index * 3 + 1], values[index * 3 + 2])
        }

    private fun smoothingAlpha(cutoffHz: Float, dtSeconds: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dtSeconds)
    }

    private companion object {
        const val MIN_DT_SECONDS = 0.001f
        const val MAX_DT_SECONDS = 0.1f
    }
}
