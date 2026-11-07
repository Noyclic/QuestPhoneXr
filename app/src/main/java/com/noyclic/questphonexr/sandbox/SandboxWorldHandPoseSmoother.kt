package com.noyclic.questphonexr.sandbox

import com.noyclic.questphonexr.tracking.HandSide
import kotlin.math.exp

/**
 * Render-only, critically damped 3D landmark smoother.
 *
 * It runs after camera-space landmarks have been mapped into the Sandbox world, so its
 * velocity and spring state are expressed in the same coordinates as the rendered hand.
 * Interaction cursors and pinch input deliberately bypass this class.
 *
 * A single response frequency is shared by all landmarks in a hand. This keeps the palm
 * and fingers temporally coherent instead of making individual joints lag by different
 * amounts. The response gets faster when the palm is moving quickly and stays softer
 * while the hand is nearly still.
 */
internal class SandboxWorldHandPoseSmoother(
    private val minimumResponseRadiansPerSecond: Float = DEFAULT_MIN_RESPONSE,
    private val maximumResponseRadiansPerSecond: Float = DEFAULT_MAX_RESPONSE,
    private val motionResponseGain: Float = DEFAULT_MOTION_RESPONSE_GAIN,
    private val responseAdaptationSeconds: Float = DEFAULT_RESPONSE_ADAPTATION_SECONDS,
    private val stationarySpeedDeadzoneMetersPerSecond: Float = DEFAULT_STATIONARY_SPEED_DEADZONE
) {
    private var activeHand = HandSide.UNKNOWN
    private var activeTrackingId = NO_TRACKING_ID
    private var positionX: FloatArray? = null
    private var positionY: FloatArray? = null
    private var positionZ: FloatArray? = null
    private var velocityX: FloatArray? = null
    private var velocityY: FloatArray? = null
    private var velocityZ: FloatArray? = null
    private var previousTargetPalmCenter: SandboxVector3? = null
    private var responseRadiansPerSecond = minimumResponseRadiansPerSecond

    init {
        require(minimumResponseRadiansPerSecond > 0f &&
            minimumResponseRadiansPerSecond.isFinite())
        require(maximumResponseRadiansPerSecond >= minimumResponseRadiansPerSecond &&
            maximumResponseRadiansPerSecond.isFinite())
        require(motionResponseGain >= 0f && motionResponseGain.isFinite())
        require(responseAdaptationSeconds > 0f && responseAdaptationSeconds.isFinite())
        require(stationarySpeedDeadzoneMetersPerSecond >= 0f &&
            stationarySpeedDeadzoneMetersPerSecond.isFinite())
    }

    fun update(
        target: List<SandboxVector3>,
        deltaSeconds: Float,
        hand: HandSide,
        trackingId: Long
    ): List<SandboxVector3> {
        if (target.isEmpty()) {
            reset()
            return emptyList()
        }
        if (target.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) {
            // Never feed invalid coordinates into persistent spring state.
            reset()
            return target
        }

        val x = positionX
        val y = positionY
        val z = positionZ
        if (x == null || y == null || z == null ||
            x.size != target.size || activeHand != hand || activeTrackingId != trackingId
        ) {
            initialize(target, hand, trackingId)
            return target
        }

        val vx = checkNotNull(velocityX)
        val vy = checkNotNull(velocityY)
        val vz = checkNotNull(velocityZ)
        val dt = deltaSeconds.takeIf { it.isFinite() }?.coerceIn(
            MIN_STEP_SECONDS,
            MAX_STEP_SECONDS
        ) ?: MIN_STEP_SECONDS

        val palmCenter = palmCenter(target)
        val previousCenter = previousTargetPalmCenter
        val measuredSpeed = if (previousCenter == null) {
            0f
        } else {
            (palmCenter - previousCenter).length() / dt
        }
        val usefulSpeed = if (measuredSpeed <= stationarySpeedDeadzoneMetersPerSecond) {
            0f
        } else {
            measuredSpeed - stationarySpeedDeadzoneMetersPerSecond
        }
        val desiredResponse = (
            minimumResponseRadiansPerSecond + usefulSpeed * motionResponseGain
        ).coerceIn(minimumResponseRadiansPerSecond, maximumResponseRadiansPerSecond)
        val responseAlpha = (1f - exp(-dt / responseAdaptationSeconds))
            .coerceIn(0f, 1f)
        responseRadiansPerSecond +=
            (desiredResponse - responseRadiansPerSecond) * responseAlpha

        // Exact critically damped spring integration for a stationary target over dt.
        // Unlike a fixed lerp factor, this behaves consistently at 20, 30 and 60 render FPS.
        val omega = responseRadiansPerSecond
        val decay = exp(-omega * dt)
        val output = ArrayList<SandboxVector3>(target.size)
        for (index in target.indices) {
            val goal = target[index]

            val offsetX = x[index] - goal.x
            val offsetY = y[index] - goal.y
            val offsetZ = z[index] - goal.z

            val helperX = vx[index] + omega * offsetX
            val helperY = vy[index] + omega * offsetY
            val helperZ = vz[index] + omega * offsetZ

            val nextX = goal.x + (offsetX + helperX * dt) * decay
            val nextY = goal.y + (offsetY + helperY * dt) * decay
            val nextZ = goal.z + (offsetZ + helperZ * dt) * decay

            vx[index] = (vx[index] - omega * helperX * dt) * decay
            vy[index] = (vy[index] - omega * helperY * dt) * decay
            vz[index] = (vz[index] - omega * helperZ * dt) * decay
            x[index] = nextX
            y[index] = nextY
            z[index] = nextZ
            output += SandboxVector3(nextX, nextY, nextZ)
        }
        previousTargetPalmCenter = palmCenter
        return output
    }

    fun reset() {
        activeHand = HandSide.UNKNOWN
        activeTrackingId = NO_TRACKING_ID
        positionX = null
        positionY = null
        positionZ = null
        velocityX = null
        velocityY = null
        velocityZ = null
        previousTargetPalmCenter = null
        responseRadiansPerSecond = minimumResponseRadiansPerSecond
    }

    private fun initialize(target: List<SandboxVector3>, hand: HandSide, trackingId: Long) {
        positionX = FloatArray(target.size) { target[it].x }
        positionY = FloatArray(target.size) { target[it].y }
        positionZ = FloatArray(target.size) { target[it].z }
        velocityX = FloatArray(target.size)
        velocityY = FloatArray(target.size)
        velocityZ = FloatArray(target.size)
        activeHand = hand
        activeTrackingId = trackingId
        previousTargetPalmCenter = palmCenter(target)
        responseRadiansPerSecond = minimumResponseRadiansPerSecond
    }

    private fun palmCenter(points: List<SandboxVector3>): SandboxVector3 {
        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f
        var count = 0
        for (index in PALM_CENTER_INDICES) {
            val point = points.getOrNull(index) ?: continue
            sumX += point.x
            sumY += point.y
            sumZ += point.z
            count++
        }
        if (count == 0) {
            val first = points.first()
            return first
        }
        return SandboxVector3(sumX / count, sumY / count, sumZ / count)
    }

    private companion object {
        const val NO_TRACKING_ID = Long.MIN_VALUE
        val PALM_CENTER_INDICES = intArrayOf(0, 5, 9, 13, 17)
        const val DEFAULT_MIN_RESPONSE = 40f
        const val DEFAULT_MAX_RESPONSE = 72f
        const val DEFAULT_MOTION_RESPONSE_GAIN = 8f
        const val DEFAULT_RESPONSE_ADAPTATION_SECONDS = 0.045f
        const val DEFAULT_STATIONARY_SPEED_DEADZONE = 0.06f
        const val MIN_STEP_SECONDS = 1f / 240f
        const val MAX_STEP_SECONDS = 0.05f
    }
}
