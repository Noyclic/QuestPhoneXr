package com.noyclic.questphonexr.performance

/**
 * A small hysteretic governor that reacts to measured runtime load instead of device specs alone.
 *
 * It only changes render cadence and the minimum interval between MediaPipe submissions.
 * It never disables hand tracking, changes a user's camera dimensions, or raises the selected FPS cap.
 */
data class AdaptivePerformanceState(
    val renderFpsCap: Int,
    val minimumInferenceIntervalMs: Long,
    val level: Int
) {
    val label: String
        get() = when (level) {
            0 -> "Автоадаптация: штатный режим"
            1 -> "Автоадаптация: экономия"
            else -> "Автоадаптация: усиленная экономия"
        }
}

class AdaptivePerformanceController(
    preset: DevicePerformancePreset,
    userRequestedFps: Int,
    private val evaluationIntervalMs: Long = DEFAULT_EVALUATION_INTERVAL_MS,
    private val overloadWindowsToDownshift: Int = DEFAULT_OVERLOAD_WINDOWS,
    private val healthyWindowsToRecover: Int = DEFAULT_HEALTHY_WINDOWS
) {
    private val baseRenderFpsCap =
        DevicePerformancePolicy.effectiveRenderFps(userRequestedFps, preset)
    private val baseInferenceIntervalMs = preset.minimumInferenceIntervalMs
    private var level = 0
    private var lastEvaluationAtMs: Long? = null
    private var overloadStreak = 0
    private var healthyStreak = 0

    init {
        require(evaluationIntervalMs > 0L)
        require(overloadWindowsToDownshift > 0)
        require(healthyWindowsToRecover > 0)
    }

    @Synchronized
    fun currentState(): AdaptivePerformanceState = stateFor(level)

    /**
     * Feed one recent telemetry snapshot. Calls more frequent than the evaluation window are
     * ignored, preventing frame-by-frame oscillation and needless configuration churn.
     */
    @Synchronized
    fun observe(
        nowMs: Long,
        renderFps: Float,
        handTrackingFps: Float,
        pipelineLatencyMs: Long
    ): AdaptivePerformanceState {
        require(nowMs >= 0L)
        val previousEvaluation = lastEvaluationAtMs
        if (previousEvaluation != null && nowMs >= previousEvaluation &&
            nowMs - previousEvaluation < evaluationIntervalMs
        ) {
            return stateFor(level)
        }
        lastEvaluationAtMs = nowMs

        val current = stateFor(level)
        val expectedHandFps = 1000f / current.minimumInferenceIntervalMs.toFloat()
        val validRenderFps = renderFps.takeIf { it.isFinite() && it > 0f }
        val validHandFps = handTrackingFps.takeIf { it.isFinite() && it > 0f }
        val latencyKnown = pipelineLatencyMs > 0L

        val overloaded =
            (validRenderFps != null && validRenderFps < current.renderFpsCap * RENDER_OVERLOAD_RATIO) ||
                (latencyKnown && pipelineLatencyMs >= OVERLOAD_LATENCY_MS) ||
                (validHandFps != null && validHandFps < expectedHandFps * HAND_OVERLOAD_RATIO)

        val healthy =
            (validRenderFps == null || validRenderFps >= current.renderFpsCap * RENDER_HEALTHY_RATIO) &&
                (!latencyKnown || pipelineLatencyMs <= HEALTHY_LATENCY_MS) &&
                (validHandFps == null || validHandFps >= expectedHandFps * HAND_HEALTHY_RATIO)

        when {
            overloaded -> {
                overloadStreak += 1
                healthyStreak = 0
                if (overloadStreak >= overloadWindowsToDownshift && level < MAX_LEVEL) {
                    level += 1
                    overloadStreak = 0
                }
            }
            healthy -> {
                healthyStreak += 1
                overloadStreak = 0
                if (healthyStreak >= healthyWindowsToRecover && level > 0) {
                    level -= 1
                    healthyStreak = 0
                }
            }
            else -> {
                overloadStreak = 0
                healthyStreak = 0
            }
        }
        return stateFor(level)
    }

    private fun stateFor(level: Int): AdaptivePerformanceState {
        val safeLevel = level.coerceIn(0, MAX_LEVEL)
        return AdaptivePerformanceState(
            renderFpsCap = (baseRenderFpsCap - safeLevel * RENDER_FPS_STEP)
                .coerceAtLeast(MIN_RENDER_FPS),
            minimumInferenceIntervalMs = baseInferenceIntervalMs +
                safeLevel * INFERENCE_INTERVAL_STEP_MS,
            level = safeLevel
        )
    }

    private companion object {
        const val DEFAULT_EVALUATION_INTERVAL_MS = 3_000L
        const val DEFAULT_OVERLOAD_WINDOWS = 2
        const val DEFAULT_HEALTHY_WINDOWS = 5
        const val MAX_LEVEL = 2
        const val RENDER_FPS_STEP = 5
        const val MIN_RENDER_FPS = 15
        const val INFERENCE_INTERVAL_STEP_MS = 30L
        const val RENDER_OVERLOAD_RATIO = 0.82f
        const val RENDER_HEALTHY_RATIO = 0.93f
        const val HAND_OVERLOAD_RATIO = 0.55f
        const val HAND_HEALTHY_RATIO = 0.70f
        const val OVERLOAD_LATENCY_MS = 220L
        const val HEALTHY_LATENCY_MS = 170L
    }
}
