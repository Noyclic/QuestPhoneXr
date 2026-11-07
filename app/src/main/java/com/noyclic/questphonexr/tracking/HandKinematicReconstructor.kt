package com.noyclic.questphonexr.tracking

import kotlin.math.sqrt

/**
 * Builds OpenXR's 26-point hand skeleton from MediaPipe's 21 world landmarks.
 *
 * The five added points are the palm center and four estimated metacarpal
 * bases. A per-joint adaptive temporal filter suppresses camera jitter while
 * increasing its response as a joint moves faster. This is reconstruction,
 * not an additional sensor measurement.
 */
internal class HandKinematicReconstructor {
    private var previousRaw: List<HandPoint>? = null
    private var previousFiltered: List<HandPoint>? = null
    private var previousTimestampMs: Long = Long.MIN_VALUE
    private var previousTrackingId: Long = Long.MIN_VALUE

    @Synchronized
    fun reconstruct(
        landmarks: List<HandPoint>,
        trackingId: Long,
        timestampMs: Long
    ): List<HandPoint>? {
        if (landmarks.size < LANDMARK_COUNT ||
            landmarks.take(LANDMARK_COUNT).any {
                !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite()
            }
        ) {
            reset()
            return null
        }

        val wrist = landmarks[0]
        fun between(target: HandPoint, amount: Float) = HandPoint(
            wrist.x + (target.x - wrist.x) * amount,
            wrist.y + (target.y - wrist.y) * amount,
            wrist.z + (target.z - wrist.z) * amount
        )
        val palmBases = intArrayOf(5, 9, 13, 17)
        val palm = HandPoint(
            palmBases.sumOf { landmarks[it].x.toDouble() }.div(4.0).toFloat(),
            palmBases.sumOf { landmarks[it].y.toDouble() }.div(4.0).toFloat(),
            palmBases.sumOf { landmarks[it].z.toDouble() }.div(4.0).toFloat()
        )
        val raw = listOf(
            palm, wrist,
            landmarks[1], landmarks[2], landmarks[3], landmarks[4],
            between(landmarks[5], METACARPAL_BASE_RATIO), landmarks[5],
            landmarks[6], landmarks[7], landmarks[8],
            between(landmarks[9], METACARPAL_BASE_RATIO), landmarks[9],
            landmarks[10], landmarks[11], landmarks[12],
            between(landmarks[13], METACARPAL_BASE_RATIO), landmarks[13],
            landmarks[14], landmarks[15], landmarks[16],
            between(landmarks[17], METACARPAL_BASE_RATIO), landmarks[17],
            landmarks[18], landmarks[19], landmarks[20]
        )

        val previous = previousFiltered
        val previousInput = previousRaw
        val elapsed = timestampMs - previousTimestampMs
        val mustReset = previous == null || previousInput == null ||
            trackingId != previousTrackingId || elapsed !in 1L..MAX_FILTER_GAP_MS
        val filtered = if (mustReset) {
            raw
        } else {
            val dtSeconds = elapsed / 1000f
            raw.indices.map { index ->
                val oldRaw = previousInput[index]
                val oldFiltered = previous[index]
                val current = raw[index]
                val speed = distance(oldRaw, current) / dtSeconds
                val alpha = (BASE_ALPHA + (speed / FAST_MOTION_SCALE) * FAST_ALPHA_GAIN)
                    .coerceIn(BASE_ALPHA, MAX_ALPHA)
                HandPoint(
                    oldFiltered.x + (current.x - oldFiltered.x) * alpha,
                    oldFiltered.y + (current.y - oldFiltered.y) * alpha,
                    oldFiltered.z + (current.z - oldFiltered.z) * alpha
                )
            }
        }

        previousRaw = raw
        previousFiltered = filtered
        previousTimestampMs = timestampMs
        previousTrackingId = trackingId
        return filtered
    }

    @Synchronized
    fun reset() {
        previousRaw = null
        previousFiltered = null
        previousTimestampMs = Long.MIN_VALUE
        previousTrackingId = Long.MIN_VALUE
    }

    private fun distance(a: HandPoint, b: HandPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private companion object {
        const val LANDMARK_COUNT = 21
        const val METACARPAL_BASE_RATIO = 0.58f
        const val BASE_ALPHA = 0.36f
        const val FAST_ALPHA_GAIN = 0.58f
        const val FAST_MOTION_SCALE = 1.4f
        const val MAX_ALPHA = 0.94f
        const val MAX_FILTER_GAP_MS = 220L
    }
}
