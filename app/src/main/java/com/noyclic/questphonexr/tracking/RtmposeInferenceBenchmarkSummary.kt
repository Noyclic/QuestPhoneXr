package com.noyclic.questphonexr.tracking

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Isolated model-inference timings. These values include GPU run + output readback, but exclude
 * CameraX, ROI extraction, image preprocessing, controller fusion and display rendering.
 */
data class RtmposeInferenceBenchmarkSummary(
    val sampleCount: Int,
    val p50Milliseconds: Float,
    val p95Milliseconds: Float,
    /** Reciprocal of p50; an isolated single-crop inference ceiling, not application FPS. */
    val estimatedSingleCropInferencesPerSecond: Float
) {
    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000.0

        fun fromSamplesNanos(samples: LongArray): RtmposeInferenceBenchmarkSummary {
            require(samples.isNotEmpty()) { "At least one timing sample is required." }
            require(samples.all { it > 0L }) { "Inference timing samples must be positive." }

            val sorted = samples.sortedArray()
            fun percentile(p: Double): Double {
                val position = (p.coerceIn(0.0, 1.0) * (sorted.size - 1))
                val lowerIndex = floor(position).toInt()
                val upperIndex = ceil(position).toInt()
                val fraction = position - lowerIndex
                val lower = sorted[lowerIndex].toDouble()
                val upper = sorted[upperIndex].toDouble()
                return lower + (upper - lower) * fraction
            }

            val p50Ms = (percentile(0.50) / NANOS_PER_MILLISECOND).toFloat()
            val p95Ms = (percentile(0.95) / NANOS_PER_MILLISECOND).toFloat()
            require(p50Ms > 0f && p95Ms >= p50Ms) {
                "Inference timings did not produce a valid percentile summary."
            }
            return RtmposeInferenceBenchmarkSummary(
                sampleCount = samples.size,
                p50Milliseconds = p50Ms,
                p95Milliseconds = p95Ms,
                estimatedSingleCropInferencesPerSecond = 1_000f / p50Ms
            )
        }
    }
}
