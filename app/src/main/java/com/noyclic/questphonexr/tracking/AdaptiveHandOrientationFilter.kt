package com.noyclic.questphonexr.tracking

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Low-allocation adaptive quaternion smoother for camera-derived palm orientation.
 *
 * It follows fast rotations more quickly than stationary camera jitter, handles
 * q/-q equivalence, and resets across hand identity changes or tracking gaps.
 * This is a presentation/input stabilizer, not a 6DoF sensor or pose predictor.
 */
internal class AdaptiveHandOrientationFilter(
    private val minimumResponsePerSecond: Float = DEFAULT_MIN_RESPONSE,
    private val maximumResponsePerSecond: Float = DEFAULT_MAX_RESPONSE,
    private val angularVelocityGain: Float = DEFAULT_ANGULAR_VELOCITY_GAIN,
    private val angularSpeedDeadzone: Float = DEFAULT_ANGULAR_SPEED_DEADZONE
) {
    private var previousRaw: FloatArray? = null
    private var filtered: FloatArray? = null
    private var previousTimestampMs = Long.MIN_VALUE
    private var previousTrackingId = Long.MIN_VALUE
    private var responsePerSecond = minimumResponsePerSecond

    init {
        require(minimumResponsePerSecond > 0f && minimumResponsePerSecond.isFinite())
        require(maximumResponsePerSecond >= minimumResponsePerSecond &&
            maximumResponsePerSecond.isFinite())
        require(angularVelocityGain >= 0f && angularVelocityGain.isFinite())
        require(angularSpeedDeadzone >= 0f && angularSpeedDeadzone.isFinite())
    }

    @Synchronized
    fun update(
        measured: FloatArray,
        trackingId: Long,
        timestampMs: Long
    ): FloatArray? {
        val normalized = normalize(measured) ?: run {
            reset()
            return null
        }

        val previousInput = previousRaw
        val previousOutput = filtered
        val elapsedMs = timestampMs - previousTimestampMs
        if (previousInput == null || previousOutput == null ||
            trackingId != previousTrackingId ||
            elapsedMs !in 1L..MAX_FILTER_GAP_MS
        ) {
            previousRaw = normalized
            filtered = normalized.copyOf()
            previousTimestampMs = timestampMs
            previousTrackingId = trackingId
            responsePerSecond = minimumResponsePerSecond
            return normalized
        }

        val dt = (elapsedMs / 1_000f).coerceIn(MIN_DT_SECONDS, MAX_DT_SECONDS)
        val dot = abs(dot(previousInput, normalized)).coerceIn(0f, 1f)
        val angularSpeed = (2f * acos(dot)) / dt
        val usefulAngularSpeed = (angularSpeed - angularSpeedDeadzone).coerceAtLeast(0f)
        val desiredResponse = (
            minimumResponsePerSecond + usefulAngularSpeed * angularVelocityGain
        ).coerceIn(minimumResponsePerSecond, maximumResponsePerSecond)
        val adaptation = 1f - exp(-dt / RESPONSE_ADAPTATION_SECONDS)
        responsePerSecond += (desiredResponse - responsePerSecond) * adaptation

        val alpha = (1f - exp(-responsePerSecond * dt)).coerceIn(0f, 1f)
        val next = slerp(previousOutput, normalized, alpha) ?: run {
            reset()
            return null
        }

        previousRaw = normalized
        filtered = next
        previousTimestampMs = timestampMs
        previousTrackingId = trackingId
        return next.copyOf()
    }

    @Synchronized
    fun reset() {
        previousRaw = null
        filtered = null
        previousTimestampMs = Long.MIN_VALUE
        previousTrackingId = Long.MIN_VALUE
        responsePerSecond = minimumResponsePerSecond
    }

    private fun normalize(value: FloatArray): FloatArray? {
        if (value.size != QUATERNION_SIZE || value.any { !it.isFinite() }) return null
        var lengthSquared = 0f
        for (component in value) lengthSquared += component * component
        if (!lengthSquared.isFinite() || lengthSquared < MIN_QUATERNION_LENGTH_SQUARED) {
            return null
        }
        val inverseLength = 1f / sqrt(lengthSquared)
        return FloatArray(QUATERNION_SIZE) { value[it] * inverseLength }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]

    private fun slerp(from: FloatArray, to: FloatArray, amount: Float): FloatArray? {
        var targetX = to[0]
        var targetY = to[1]
        var targetZ = to[2]
        var targetW = to[3]
        var cosine = dot(from, to)

        // Choose the shortest quaternion arc; q and -q describe the same rotation.
        if (cosine < 0f) {
            cosine = -cosine
            targetX = -targetX
            targetY = -targetY
            targetZ = -targetZ
            targetW = -targetW
        }
        cosine = cosine.coerceIn(-1f, 1f)

        val result = if (cosine > LINEAR_SLERP_THRESHOLD) {
            FloatArray(QUATERNION_SIZE) { index ->
                from[index] + (when (index) {
                    0 -> targetX
                    1 -> targetY
                    2 -> targetZ
                    else -> targetW
                } - from[index]) * amount
            }
        } else {
            val angle = acos(cosine)
            val sine = sin(angle)
            if (!sine.isFinite() || abs(sine) < MIN_SLERP_SINE) return null
            val fromWeight = sin((1f - amount) * angle) / sine
            val toWeight = sin(amount * angle) / sine
            FloatArray(QUATERNION_SIZE) { index ->
                from[index] * fromWeight + when (index) {
                    0 -> targetX
                    1 -> targetY
                    2 -> targetZ
                    else -> targetW
                } * toWeight
            }
        }
        return normalize(result)
    }

    private companion object {
        const val QUATERNION_SIZE = 4
        const val MIN_QUATERNION_LENGTH_SQUARED = 1e-8f
        const val MIN_SLERP_SINE = 1e-6f
        const val LINEAR_SLERP_THRESHOLD = 0.9995f
        const val MIN_DT_SECONDS = 1f / 240f
        const val MAX_DT_SECONDS = 0.1f
        const val MAX_FILTER_GAP_MS = 220L
        const val DEFAULT_MIN_RESPONSE = 18f
        const val DEFAULT_MAX_RESPONSE = 56f
        const val DEFAULT_ANGULAR_VELOCITY_GAIN = 7f
        const val DEFAULT_ANGULAR_SPEED_DEADZONE = 0.10f
        const val RESPONSE_ADAPTATION_SECONDS = 0.04f
    }
}
