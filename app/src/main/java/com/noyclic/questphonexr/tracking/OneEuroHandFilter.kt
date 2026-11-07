package com.noyclic.questphonexr.tracking

import kotlin.math.PI
import kotlin.math.abs

/**
 * Allocation-light One Euro filter for a fixed MediaPipe landmark set.
 *
 * Velocity is calculated from the previous raw sample (not the filtered value), which is
 * important: measuring velocity against the filtered pose underestimates motion and creates
 * unnecessary lag. Axis state is allocated once and reused for every subsequent frame.
 */
internal class OneEuroHandFilter(
    private val minCutoffHz: Float = DEFAULT_MIN_CUTOFF_HZ,
    private val beta: Float = DEFAULT_BETA,
    private val derivativeCutoffHz: Float = DEFAULT_DERIVATIVE_CUTOFF_HZ,
    private val resetAfterGapMs: Long = DEFAULT_RESET_AFTER_GAP_MS,
    /** Optional landmark indices that should react faster in the visible hand mesh. */
    private val responsiveLandmarkA: Int = -1,
    private val responsiveLandmarkB: Int = -1,
    private val responsiveMinCutoffHz: Float = minCutoffHz * RESPONSIVE_CUTOFF_MULTIPLIER,
    private val responsiveBeta: Float = beta * RESPONSIVE_BETA_MULTIPLIER
) {
    private data class AxisState(
        var previousRaw: Float,
        var filtered: Float,
        var filteredDerivative: Float
    )

    private var previousTimestampMs: Long? = null
    private var axes: Array<AxisState>? = null

    init {
        require(minCutoffHz > 0f && minCutoffHz.isFinite())
        require(beta >= 0f && beta.isFinite())
        require(derivativeCutoffHz > 0f && derivativeCutoffHz.isFinite())
        require(resetAfterGapMs > 0L)
        require(responsiveLandmarkA >= -1 && responsiveLandmarkB >= -1)
        require(responsiveMinCutoffHz > 0f && responsiveMinCutoffHz.isFinite())
        require(responsiveBeta >= 0f && responsiveBeta.isFinite())
    }

    fun update(points: List<HandPoint>, timestampMs: Long): List<HandPoint> {
        if (points.isEmpty()) {
            reset()
            return emptyList()
        }

        for (point in points) {
            if (!point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite()) {
                reset()
                return points
            }
        }

        val previousTime = previousTimestampMs
        val state = axes
        if (previousTime == null || state == null || state.size != points.size * AXES_PER_POINT ||
            timestampMs <= previousTime || timestampMs - previousTime > resetAfterGapMs
        ) {
            axes = createInitialState(points)
            previousTimestampMs = timestampMs
            return points
        }

        val activeState = checkNotNull(state)
        val dtSeconds = ((timestampMs - previousTime).toFloat() / 1_000f)
            .coerceIn(MIN_DT_SECONDS, MAX_DT_SECONDS)
        val derivativeAlpha = smoothingAlpha(derivativeCutoffHz, dtSeconds)
        val output = ArrayList<HandPoint>(points.size)

        for (index in points.indices) {
            val point = points[index]
            val offset = index * AXES_PER_POINT
            val responsive = index == responsiveLandmarkA || index == responsiveLandmarkB
            val activeMinCutoff = if (responsive) responsiveMinCutoffHz else minCutoffHz
            val activeBeta = if (responsive) responsiveBeta else beta
            val x = filterAxis(
                activeState[offset], point.x, dtSeconds, derivativeAlpha,
                activeMinCutoff, activeBeta
            )
            val y = filterAxis(
                activeState[offset + 1], point.y, dtSeconds, derivativeAlpha,
                activeMinCutoff, activeBeta
            )
            val z = filterAxis(
                activeState[offset + 2], point.z, dtSeconds, derivativeAlpha,
                activeMinCutoff, activeBeta
            )
            output.add(HandPoint(x, y, z))
        }

        previousTimestampMs = timestampMs
        return output
    }

    fun reset() {
        previousTimestampMs = null
        axes = null
    }

    private fun createInitialState(points: List<HandPoint>): Array<AxisState> {
        return Array(points.size * AXES_PER_POINT) { axisIndex ->
            val point = points[axisIndex / AXES_PER_POINT]
            val raw = when (axisIndex % AXES_PER_POINT) {
                0 -> point.x
                1 -> point.y
                else -> point.z
            }
            AxisState(raw, raw, 0f)
        }
    }

    private fun filterAxis(
        state: AxisState,
        rawValue: Float,
        dtSeconds: Float,
        derivativeAlpha: Float,
        activeMinCutoffHz: Float,
        activeBeta: Float
    ): Float {
        val rawDerivative = (rawValue - state.previousRaw) / dtSeconds
        state.filteredDerivative += derivativeAlpha *
            (rawDerivative - state.filteredDerivative)

        val adaptiveCutoff = activeMinCutoffHz + activeBeta * abs(state.filteredDerivative)
        val valueAlpha = smoothingAlpha(adaptiveCutoff, dtSeconds)
        state.filtered += valueAlpha * (rawValue - state.filtered)
        state.previousRaw = rawValue
        return state.filtered
    }

    private fun smoothingAlpha(cutoffHz: Float, dtSeconds: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dtSeconds)
    }

    private companion object {
        const val AXES_PER_POINT = 3
        // Lower idle cutoff damps camera/landmark jitter. A moderate beta still
        // raises the cutoff while the hand moves, avoiding the old rubber-band lag.
        const val DEFAULT_MIN_CUTOFF_HZ = 1.8f
        const val DEFAULT_BETA = 0.25f
        const val DEFAULT_DERIVATIVE_CUTOFF_HZ = 1.0f
        const val DEFAULT_RESET_AFTER_GAP_MS = 250L
        const val RESPONSIVE_CUTOFF_MULTIPLIER = 1.75f
        const val RESPONSIVE_BETA_MULTIPLIER = 2.4f
        const val MIN_DT_SECONDS = 0.001f
        const val MAX_DT_SECONDS = 0.1f
    }
}
