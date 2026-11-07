package com.noyclic.questphonexr.sandbox

import com.noyclic.questphonexr.input.GamepadButtonCodes
import com.noyclic.questphonexr.input.PhysicalControllerSnapshot
import com.noyclic.questphonexr.tracking.HandPoint
import com.noyclic.questphonexr.tracking.HandSide
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.tracking.TrackedHand
import kotlin.math.sqrt

/**
 * Keep the last valid hand pose alive for a few inference intervals.
 * Phone CPU inference can be slower than the nominal camera frame rate; a fixed
 * 500 ms timeout drops otherwise valid hands between MediaPipe callbacks.
 */
internal fun sandboxHandFrameStaleTimeoutMs(measuredFps: Float): Long {
    if (!measuredFps.isFinite() || measuredFps <= 0f) return 1_200L
    return (2_500f / measuredFps).toLong().coerceIn(900L, 2_200L)
}

internal fun isSandboxHandFrameStale(
    nowUptimeMs: Long,
    receivedAtUptimeMs: Long,
    timeoutMs: Long
): Boolean {
    if (receivedAtUptimeMs <= 0L || timeoutMs < 0L) return true
    return (nowUptimeMs - receivedAtUptimeMs).coerceAtLeast(0L) > timeoutMs
}


/**
 * Small jitter-aware presentation buffer. Stable inference cadence uses a shorter delay;
 * irregular callback spacing grows the buffer temporarily so interpolation has a usable
 * pair of samples. This affects rendered landmarks only, never pinch/grip input.
 */
internal class HandPresentationDelayEstimator {
    private var previousIntervalMs = 0f
    private var smoothedIntervalMs = 0f
    private var smoothedJitterMs = 0f
    private var hasSample = false

    fun observe(intervalMs: Long) {
        val interval = intervalMs.toFloat().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        if (!hasSample) {
            previousIntervalMs = interval
            smoothedIntervalMs = interval
            smoothedJitterMs = 0f
            hasSample = true
            return
        }
        val deviation = kotlin.math.abs(interval - previousIntervalMs)
        smoothedJitterMs += JITTER_ALPHA * (deviation - smoothedJitterMs)
        smoothedIntervalMs += INTERVAL_ALPHA * (interval - smoothedIntervalMs)
        previousIntervalMs = interval
    }

    fun delayMs(fallbackIntervalMs: Long): Long {
        val interval = if (hasSample) smoothedIntervalMs else
            fallbackIntervalMs.toFloat().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        val target = interval * BASE_DELAY_RATIO + smoothedJitterMs * JITTER_MULTIPLIER
        return target.toLong().coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
    }

    fun reset() {
        previousIntervalMs = 0f
        smoothedIntervalMs = 0f
        smoothedJitterMs = 0f
        hasSample = false
    }

    private companion object {
        const val MIN_INTERVAL_MS = 16f
        const val MAX_INTERVAL_MS = 220f
        const val INTERVAL_ALPHA = 0.20f
        const val JITTER_ALPHA = 0.25f
        const val BASE_DELAY_RATIO = 0.30f
        const val JITTER_MULTIPLIER = 1.25f
        const val MIN_DELAY_MS = 18L
        const val MAX_DELAY_MS = 64L
    }
}

/**
 * Produces a render-time hand pose between MediaPipe observations.
 *
 * Gesture state is copied from the newest observation and is never interpolated.
 * Only landmark positions are interpolated, with a small adaptive presentation delay
 * and tightly bounded forward prediction to bridge the tail of a camera-sample interval.
 */
internal class SandboxHandPoseInterpolator {
    private data class Sample(val timestampMs: Long, val hand: TrackedHand)

    private val presentationDelay = HandPresentationDelayEstimator()
    private var previous: Sample? = null
    private var current: Sample? = null
    private var lastFrameTimestampMs = Long.MIN_VALUE

    fun sample(
        hand: TrackedHand?,
        frameTimestampMs: Long,
        receivedAtUptimeMs: Long,
        renderTimeMs: Long
    ): TrackedHand? {
        if (frameTimestampMs != lastFrameTimestampMs) {
            lastFrameTimestampMs = frameTimestampMs
            if (hand == null) {
                previous = null
                current = null
                presentationDelay.reset()
                return null
            }

            val arrivalTime = receivedAtUptimeMs.takeIf { it > 0L } ?: frameTimestampMs
            val sampleTime = maxOf(arrivalTime, (current?.timestampMs ?: arrivalTime - 1L) + 1L)
            val oldCurrent = current
            if (oldCurrent != null &&
                sampleTime > oldCurrent.timestampMs &&
                sampleTime - oldCurrent.timestampMs <= MAX_SAMPLE_GAP_MS &&
                sameTrack(oldCurrent.hand, hand) &&
                oldCurrent.hand.points.size == hand.points.size
            ) {
                previous = oldCurrent
                presentationDelay.observe(sampleTime - oldCurrent.timestampMs)
            } else {
                previous = null
                presentationDelay.reset()
            }
            current = Sample(sampleTime, hand)
        }

        val newest = current ?: return null
        val older = previous ?: return newest.hand
        val sampleDurationMs = (newest.timestampMs - older.timestampMs).coerceAtLeast(1L)
        val presentationDelayMs = presentationDelay.delayMs(sampleDurationMs)
        val presentationTimeMs = renderTimeMs - presentationDelayMs

        val interpolationAmount = if (presentationTimeMs <= newest.timestampMs) {
            ((presentationTimeMs - older.timestampMs).toFloat() / sampleDurationMs.toFloat())
                .coerceIn(0f, 1f)
        } else {
            val predictionLimitMs = (sampleDurationMs * MAX_PREDICTION_RATIO)
                .toLong()
                .coerceAtMost(MAX_PREDICTION_MS)
            val predictionMs = (presentationTimeMs - newest.timestampMs)
                .coerceIn(0L, predictionLimitMs)
            1f + predictionMs.toFloat() / sampleDurationMs.toFloat()
        }

        return interpolate(older.hand, newest.hand, interpolationAmount)
    }

    fun reset() {
        previous = null
        current = null
        presentationDelay.reset()
        lastFrameTimestampMs = Long.MIN_VALUE
    }

    private fun sameTrack(first: TrackedHand, second: TrackedHand): Boolean =
        first.side == second.side && first.trackingId == second.trackingId

    private fun interpolate(
        from: TrackedHand,
        to: TrackedHand,
        amount: Float
    ): TrackedHand {
        if (amount == 1f) return to
        val points = interpolatePoints(from.points, to.points, amount)
        val worldPoints = if (from.worldPoints.size == to.worldPoints.size) {
            interpolatePoints(from.worldPoints, to.worldPoints, amount)
        } else {
            to.worldPoints
        }
        // Pinch/grip flags and strength remain authoritative from the newest camera result.
        return to.copy(points = points, worldPoints = worldPoints)
    }

    private fun interpolatePoints(
        from: List<HandPoint>,
        to: List<HandPoint>,
        amount: Float
    ): List<HandPoint> {
        if (from.size != to.size) return to
        return List(to.size) { index ->
            val start = from[index]
            val end = to[index]
            HandPoint(
                x = start.x + (end.x - start.x) * amount,
                y = start.y + (end.y - start.y) * amount,
                z = start.z + (end.z - start.z) * amount
            )
        }
    }

    private companion object {
        const val MAX_SAMPLE_GAP_MS = 220L
        const val MAX_PREDICTION_MS = 26L
        const val MAX_PREDICTION_RATIO = 0.35f
    }
}

enum class SandboxObjectType { CUBE, BALL, TARGET }

data class SandboxVector3(
    val x: Float,
    val y: Float,
    val z: Float
) {
    operator fun plus(other: SandboxVector3) =
        SandboxVector3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: SandboxVector3) =
        SandboxVector3(x - other.x, y - other.y, z - other.z)

    operator fun times(scale: Float) =
        SandboxVector3(x * scale, y * scale, z * scale)

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun clamped(maxLength: Float): SandboxVector3 {
        val length = length()
        return if (length > maxLength && length > 0f) this * (maxLength / length) else this
    }
}

data class SandboxObjectState(
    val id: Int,
    val type: SandboxObjectType,
    val position: SandboxVector3,
    val velocity: SandboxVector3,
    val heldBy: HandSide? = null
)

data class SandboxHandState(
    val hand: HandSide,
    val cursor: SandboxVector3,
    val grabbing: Boolean,
    val connected: Boolean,
    val cameraTracked: Boolean = false,
    val landmarks: List<SandboxVector3> = emptyList()
)

data class SandboxSceneSnapshot(
    val objects: List<SandboxObjectState>,
    val leftHand: SandboxHandState,
    val rightHand: SandboxHandState,
    val grabbedObjectCount: Int,
    val score: Int = 0,
    /** World-space player position controlled by the left Joy-Con stick. */
    val playerPosition: SandboxVector3 = SandboxVector3(0f, 0f, 0f)
)

/**
 * Lightweight deterministic sandbox simulation for phone-VR input testing.
 *
 * Camera wrist landmarks and Joy-Con sticks both map to a 2D reach plane.
 * This is deliberately not presented as calibrated 6DoF hand/controller tracking.
 */
class SandboxSimulation {
    private data class MutableObject(
        val id: Int,
        val type: SandboxObjectType,
        var position: SandboxVector3,
        val spawnPosition: SandboxVector3 = position,
        var velocity: SandboxVector3 = ZERO,
        var heldBy: HandSide? = null
    ) {
        fun snapshot() = SandboxObjectState(id, type, position, velocity, heldBy)
    }

    private data class ControllerState(
        val cursor: SandboxVector3,
        val grabbing: Boolean,
        val connected: Boolean,
        val cameraTracked: Boolean,
        val trackingLost: Boolean = false,
        val landmarks: List<SandboxVector3> = emptyList(),
        val contactLandmarks: List<SandboxVector3> = emptyList()
    )

    private val objects = mutableListOf<MutableObject>()
    private val leftCursorSmoother = SandboxCursorSmoother()
    private val rightCursorSmoother = SandboxCursorSmoother()
    private val leftDepthEstimator = MonocularHandDepthEstimator()
    private val rightDepthEstimator = MonocularHandDepthEstimator()
    private val leftPoseInterpolator = SandboxHandPoseInterpolator()
    private val rightPoseInterpolator = SandboxHandPoseInterpolator()
    // These smooth only the mapped 3D display landmarks. Interaction cursors remain raw.
    private val leftWorldPoseSmoother = SandboxWorldHandPoseSmoother()
    private val rightWorldPoseSmoother = SandboxWorldHandPoseSmoother()
    private var leftCursor = DEFAULT_LEFT_CURSOR
    private var rightCursor = DEFAULT_RIGHT_CURSOR
    private var leftGrabbing = false
    private var rightGrabbing = false
    private var leftConnected = false
    private var rightConnected = false
    private var leftCameraTracked = false
    private var rightCameraTracked = false
    private var leftLandmarks: List<SandboxVector3> = emptyList()
    private var rightLandmarks: List<SandboxVector3> = emptyList()
    private var handCalibration = SandboxHandCalibration.DEFAULT
    private var nextObjectId = 1
    private var score = 0
    private var playerPosition = ZERO

    init {
        reset()
    }

    @Synchronized
    fun reset() {
        objects.clear()
        nextObjectId = 1
        score = 0
        playerPosition = ZERO
        objects += createObject(SandboxObjectType.CUBE, SandboxVector3(-0.9f, -0.25f, -3.0f))
        objects += createObject(SandboxObjectType.BALL, SandboxVector3(0f, 0.25f, -3.15f))
        objects += createObject(SandboxObjectType.CUBE, SandboxVector3(0.9f, -0.25f, -3.0f))
        objects += createObject(SandboxObjectType.TARGET, TARGET_POSITION)
        leftCursor = DEFAULT_LEFT_CURSOR
        rightCursor = DEFAULT_RIGHT_CURSOR
        leftGrabbing = false
        rightGrabbing = false
        leftConnected = false
        rightConnected = false
        leftCameraTracked = false
        rightCameraTracked = false
        leftLandmarks = emptyList()
        rightLandmarks = emptyList()
        leftCursorSmoother.reset()
        rightCursorSmoother.reset()
        leftPoseInterpolator.reset()
        rightPoseInterpolator.reset()
        leftWorldPoseSmoother.reset()
        rightWorldPoseSmoother.reset()
    }

    @Synchronized
    fun setHandCalibration(calibration: SandboxHandCalibration) {
        handCalibration = calibration.normalized()
        leftCursorSmoother.reset()
        rightCursorSmoother.reset()
        leftPoseInterpolator.reset()
        rightPoseInterpolator.reset()
        leftWorldPoseSmoother.reset()
        rightWorldPoseSmoother.reset()
        leftLandmarks = emptyList()
        rightLandmarks = emptyList()
    }

    @Synchronized
    fun spawnCube() {
        val offset = ((nextObjectId % 5) - 2) * 0.28f
        objects += createObject(
            SandboxObjectType.CUBE,
            SandboxVector3(offset, 0.75f, -3.2f - (nextObjectId % 3) * 0.18f)
        )
    }

    @Synchronized
    fun update(controllers: List<PhysicalControllerSnapshot>, deltaSeconds: Float) {
        update(controllers, HandTrackingFrame(0L, emptyList()), deltaSeconds)
    }

    @Synchronized
    fun update(
        controllers: List<PhysicalControllerSnapshot>,
        handFrame: HandTrackingFrame,
        deltaSeconds: Float,
        cameraToWorldRotation: FloatArray? = null,
        handFrameReceivedAtMs: Long = handFrame.timestampMs,
        renderTimeMs: Long = handFrameReceivedAtMs
    ) {
        val dt = deltaSeconds.takeIf { it.isFinite() }?.coerceIn(MIN_STEP_SECONDS, MAX_STEP_SECONDS)
            ?: MIN_STEP_SECONDS
        movePlayer(controllers, dt)
        var leftTrackedHand: TrackedHand? = null
        var rightTrackedHand: TrackedHand? = null
        for (trackedHand in handFrame.hands) {
            when (trackedHand.side) {
                HandSide.LEFT -> leftTrackedHand = trackedHand
                HandSide.RIGHT -> rightTrackedHand = trackedHand
                HandSide.UNKNOWN -> Unit
            }
        }
        val leftDisplayHand = leftPoseInterpolator.sample(
            leftTrackedHand, handFrame.timestampMs, handFrameReceivedAtMs, renderTimeMs
        )
        val rightDisplayHand = rightPoseInterpolator.sample(
            rightTrackedHand, handFrame.timestampMs, handFrameReceivedAtMs, renderTimeMs
        )
        val leftRaw = controllerFor(
            HandSide.LEFT, controllers, leftTrackedHand, leftDisplayHand, cameraToWorldRotation, dt
        )
        val rightRaw = controllerFor(
            HandSide.RIGHT, controllers, rightTrackedHand, rightDisplayHand, cameraToWorldRotation, dt
        )
        val leftTrackingLost = leftCameraTracked && !leftRaw.cameraTracked &&
            leftGrabbing && !leftRaw.grabbing
        val rightTrackingLost = rightCameraTracked && !rightRaw.cameraTracked &&
            rightGrabbing && !rightRaw.grabbing
        // Rendering uses interpolated landmarks, but interaction cursors below are based
        // on the newest MediaPipe result so pinch/grab movement is not delayed.
        val left = if (leftRaw.cameraTracked) {
            leftRaw.copy(trackingLost = false)
        } else {
            leftCursorSmoother.reset()
            leftRaw.copy(trackingLost = leftTrackingLost)
        }
        val right = if (rightRaw.cameraTracked) {
            rightRaw.copy(trackingLost = false)
        } else {
            rightCursorSmoother.reset()
            rightRaw.copy(trackingLost = rightTrackingLost)
        }

        val leftWorld = left.withWorldOffset(playerPosition)
        val rightWorld = right.withWorldOffset(playerPosition)
        leftCursor = leftWorld.cursor
        rightCursor = rightWorld.cursor
        leftConnected = left.connected
        rightConnected = right.connected
        leftCameraTracked = left.cameraTracked
        rightCameraTracked = right.cameraTracked
        leftLandmarks = leftWorld.landmarks
        rightLandmarks = rightWorld.landmarks

        updateGrab(HandSide.LEFT, left.grabbing, leftWorld.cursor, dt, left.trackingLost)
        updateGrab(HandSide.RIGHT, right.grabbing, rightWorld.cursor, dt, right.trackingLost)
        applyFingerContacts(leftWorld, dt)
        applyFingerContacts(rightWorld, dt)
        updatePhysics(dt)
    }

    @Synchronized
    fun snapshot(): SandboxSceneSnapshot {
        val snapshots = objects.map { it.snapshot() }
        return SandboxSceneSnapshot(
            objects = snapshots,
            leftHand = SandboxHandState(
                HandSide.LEFT,
                leftCursor,
                leftGrabbing,
                leftConnected,
                leftCameraTracked,
                leftLandmarks
            ),
            rightHand = SandboxHandState(
                HandSide.RIGHT,
                rightCursor,
                rightGrabbing,
                rightConnected,
                rightCameraTracked,
                rightLandmarks
            ),
            grabbedObjectCount = objects.count { it.heldBy != null },
            score = score,
            playerPosition = playerPosition
        )
    }

    private fun movePlayer(
        controllers: List<PhysicalControllerSnapshot>,
        deltaSeconds: Float
    ) {
        // The left Joy-Con is the locomotion stick. If only the right one is connected,
        // allow it to move the player too. Camera-tracked hands remain independent.
        val movement = controllers.firstOrNull { it.hand == HandSide.LEFT }
            ?: controllers.firstOrNull { it.hand == HandSide.RIGHT }
            ?: return
        val x = if (kotlin.math.abs(movement.stickX) > PLAYER_STICK_DEADZONE) movement.stickX else 0f
        val y = if (kotlin.math.abs(movement.stickY) > PLAYER_STICK_DEADZONE) movement.stickY else 0f
        if (x == 0f && y == 0f) return
        playerPosition = SandboxVector3(
            x = (playerPosition.x + x * PLAYER_MOVE_SPEED * deltaSeconds)
                .coerceIn(-PLAYER_X_LIMIT, PLAYER_X_LIMIT),
            y = 0f,
            z = (playerPosition.z + y * PLAYER_MOVE_SPEED * deltaSeconds)
                .coerceIn(PLAYER_MIN_Z, PLAYER_MAX_Z)
        )
    }

    private fun ControllerState.withWorldOffset(offset: SandboxVector3): ControllerState {
        if (offset.x == 0f && offset.y == 0f && offset.z == 0f) return this
        return copy(
            cursor = cursor + offset,
            landmarks = landmarks.map { it + offset },
            contactLandmarks = contactLandmarks.map { it + offset }
        )
    }

    private fun createObject(type: SandboxObjectType, position: SandboxVector3) =
        MutableObject(nextObjectId++, type, position)

    private fun controllerFor(
        hand: HandSide,
        controllers: List<PhysicalControllerSnapshot>,
        trackedHand: TrackedHand?,
        displayHand: TrackedHand?,
        cameraToWorldRotation: FloatArray?,
        renderDeltaSeconds: Float
    ): ControllerState {
        val physical = controllers.firstOrNull { it.hand == hand }
        val depthEstimator = if (hand == HandSide.LEFT) leftDepthEstimator else rightDepthEstimator
        val estimatedDistance = trackedHand?.let { depthEstimator.update(it.points) }
        val cameraLandmarks = trackedHand?.let { tracked ->
            SandboxHandPoseMapper.map(
                points = tracked.points,
                calibration = handCalibration,
                estimatedDistanceMeters = estimatedDistance,
                worldPoints = tracked.worldPoints,
                cameraToWorldRotation = cameraToWorldRotation
            )
        }.orEmpty()
        val displayLandmarks = displayHand?.let { display ->
            if (display === trackedHand) {
                cameraLandmarks
            } else {
                SandboxHandPoseMapper.map(
                    points = display.points,
                    calibration = handCalibration,
                    estimatedDistanceMeters = estimatedDistance,
                    worldPoints = display.worldPoints,
                    cameraToWorldRotation = cameraToWorldRotation
                )
            }
        }.orEmpty()
        if (trackedHand == null) depthEstimator.reset()
        val pinching = trackedHand?.isPinching == true
        val powerGripping = trackedHand?.let(::isPowerGrip) == true
        val cameraCursor = interactionCursor(cameraLandmarks, pinching, powerGripping)
        // A deflected Joy-Con stick takes temporary control of the virtual hand.
        // Releasing the stick returns control to MediaPipe (or the default hand pose).
        val gamepadCursor = physical
            ?.takeIf { kotlin.math.abs(it.stickX) > GAMEPAD_STICK_DEADZONE ||
                kotlin.math.abs(it.stickY) > GAMEPAD_STICK_DEADZONE }
            ?.let {
                SandboxVector3(
                    x = (it.stickX * GAMEPAD_REACH_WIDTH).coerceIn(-3.0f, 3.0f),
                    y = (-it.stickY * GAMEPAD_REACH_HEIGHT).coerceIn(-2.15f, 2.15f),
                    z = GAMEPAD_CURSOR_DEPTH
                )
            }
        val fallbackCursor = if (hand == HandSide.LEFT) DEFAULT_LEFT_CURSOR else DEFAULT_RIGHT_CURSOR
        val cursor = cameraCursor ?: gamepadCursor ?: fallbackCursor
        // Locomotion must never translate a camera-tracked hand. The gamepad cursor
        // remains available only as a fallback when MediaPipe is not tracking this hand.
        // Smooth after mapping into the actual Sandbox world. This stabilizes the 3D
        // hand's depth/position as well as its silhouette, without delaying the cursor,
        // pinch/grab state, or the controller pose sent to EndXR.
        val worldPoseSmoother = if (hand == HandSide.LEFT) {
            leftWorldPoseSmoother
        } else {
            rightWorldPoseSmoother
        }
        val renderedLandmarks = if (trackedHand != null && displayHand != null &&
            displayLandmarks.isNotEmpty()
        ) {
            worldPoseSmoother.update(
                target = displayLandmarks,
                deltaSeconds = renderDeltaSeconds,
                hand = hand,
                trackingId = displayHand.trackingId
            )
        } else {
            worldPoseSmoother.reset()
            emptyList()
        }
        val digitalGrab = physical?.pressedKeyCodes?.any {
            it == GamepadButtonCodes.BUTTON_L1 ||
                it == GamepadButtonCodes.BUTTON_R1 ||
                it == GamepadButtonCodes.BUTTON_L2 ||
                it == GamepadButtonCodes.BUTTON_R2
        } ?: false
        val analogGrab = physical?.let {
            it.leftTrigger > GRAB_THRESHOLD || it.rightTrigger > GRAB_THRESHOLD
        } ?: false
        // A closed three-or-more-finger power grip is a natural grab fallback.
        // Pinch remains supported as a precision grip, and physical controllers remain available.
        val grabbing = pinching || powerGripping || digitalGrab || analogGrab
        return ControllerState(
            cursor = cursor,
            grabbing = grabbing,
            connected = trackedHand != null || physical != null,
            cameraTracked = trackedHand != null,
            landmarks = renderedLandmarks,
            contactLandmarks = cameraLandmarks
        )
    }

    private fun isPowerGrip(hand: TrackedHand): Boolean {
        // MediaPipe world landmarks are preferred for rotation/scale robustness.
        // Fall back to normalized image landmarks if the 3D chain is incomplete.
        val worldGrip = curledFingerCount(hand.worldPoints)
        val curledFingers = worldGrip ?: curledFingerCount(hand.points) ?: return false
        return curledFingers >= POWER_GRIP_MIN_FINGERS
    }

    private fun curledFingerCount(points: List<HandPoint>): Int? {
        if (points.size < 21) return null
        val wrist = points[0]
        var completeFingers = 0
        var curledFingers = 0
        for (mcpIndex in FINGER_MCP_INDICES) {
            val mcp = points[mcpIndex]
            val pip = points[mcpIndex + 1]
            val dip = points[mcpIndex + 2]
            val tip = points[mcpIndex + 3]
            val palmLength = distance(wrist, mcp)
            if (!palmLength.isFinite() || palmLength < MIN_PALM_FINGER_LENGTH) continue

            val segments = floatArrayOf(
                distance(mcp, pip),
                distance(pip, dip),
                distance(dip, tip)
            )
            // Scale all validity checks to that hand's palm so distant hands
            // are not rejected by a fixed pixel/world-unit threshold.
            if (segments.any { !it.isFinite() || it / palmLength < MIN_FINGER_SEGMENT_RATIO }) {
                continue
            }
            completeFingers++
            val foldedLength = distance(mcp, tip)
            if (foldedLength.isFinite() &&
                foldedLength / palmLength < POWER_GRIP_FOLD_RATIO
            ) curledFingers++
        }
        // Too few complete chains is not sufficient evidence of a fist.
        return curledFingers.takeIf { completeFingers >= POWER_GRIP_MIN_FINGERS }
    }
    private fun distance(a: HandPoint, b: HandPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun interactionCursor(
        landmarks: List<SandboxVector3>,
        isPinching: Boolean,
        isPowerGripping: Boolean
    ): SandboxVector3? {
        val indexTip = landmarks.getOrNull(8) ?: landmarks.firstOrNull() ?: return null
        if (isPinching) {
            val thumbTip = landmarks.getOrNull(4) ?: return indexTip
            return (thumbTip + indexTip) * 0.5f
        }
        if (isPowerGripping) {
            val palmPoints = PALM_MCP_INDICES.mapNotNull(landmarks::getOrNull)
            if (palmPoints.isNotEmpty()) {
                return palmPoints.reduce { total, point -> total + point } *
                    (1f / palmPoints.size.toFloat())
            }
        }
        return indexTip
    }

    private fun applyFingerContacts(hand: ControllerState, deltaSeconds: Float) {
        if (!hand.cameraTracked || hand.grabbing) return
        val fingertips = FINGERTIP_INDICES.mapNotNull(hand.contactLandmarks::getOrNull)
        if (fingertips.isEmpty()) return
        for (item in objects) {
            if (item.heldBy != null || item.type == SandboxObjectType.TARGET) continue
            val contactRadius = if (item.type == SandboxObjectType.BALL) {
                BALL_CONTACT_RADIUS
            } else {
                CUBE_CONTACT_RADIUS
            }
            val contact = fingertips
                .map { tip -> tip to (item.position - tip) }
                .minByOrNull { it.second.length() } ?: continue
            val distance = contact.second.length()
            if (distance >= contactRadius) continue
            val normal = if (distance > 0.0001f) {
                contact.second * (1f / distance)
            } else {
                SandboxVector3(0f, 0.35f, -0.94f)
            }
            val penetration = (contactRadius - distance).coerceAtLeast(0f)
            item.velocity = item.velocity +
                normal * (FINGER_CONTACT_ACCELERATION * deltaSeconds + penetration * CONTACT_SEPARATION_GAIN)
        }
    }

    private fun updateGrab(
        hand: HandSide,
        isPressed: Boolean,
        cursor: SandboxVector3,
        deltaSeconds: Float,
        dropWithoutThrow: Boolean
    ) {
        val currentlyHeld = objects.firstOrNull { it.heldBy == hand }

        if (isPressed && currentlyHeld == null) {
            val nearest = objects
                .filter { it.heldBy == null && it.type != SandboxObjectType.TARGET }
                .minByOrNull { (it.position - cursor).length() }
            if (nearest != null && (nearest.position - cursor).length() <= MAX_GRAB_DISTANCE) {
                nearest.heldBy = hand
                nearest.velocity = ZERO
            }
        } else if (!isPressed && currentlyHeld != null) {
            currentlyHeld.heldBy = null
            currentlyHeld.velocity = if (dropWithoutThrow) {
                ZERO
            } else {
                (cursor - currentlyHeld.position)
                    .times(1f / deltaSeconds)
                    .clamped(MAX_THROW_SPEED)
            }
        }

        objects.firstOrNull { it.heldBy == hand }?.let { held ->
            held.velocity = (cursor - held.position).times(1f / deltaSeconds).clamped(MAX_THROW_SPEED)
            held.position = cursor
        }

        if (hand == HandSide.LEFT) leftGrabbing = isPressed else rightGrabbing = isPressed
    }

    private fun updatePhysics(deltaSeconds: Float) {
        objects.filter { it.heldBy == null && it.type != SandboxObjectType.TARGET }.forEach { item ->
            item.velocity = item.velocity + SandboxVector3(0f, -GRAVITY * deltaSeconds, 0f)
            item.position = item.position + item.velocity * deltaSeconds

            if (item.position.y < FLOOR_Y) {
                item.position = item.position.copy(y = FLOOR_Y)
                item.velocity = item.velocity.copy(
                    y = if (item.velocity.y < -MIN_BOUNCE_SPEED) {
                        -item.velocity.y * FLOOR_BOUNCE
                    } else {
                        0f
                    }
                )
                item.velocity = item.velocity.copy(
                    x = item.velocity.x * FLOOR_FRICTION,
                    z = item.velocity.z * FLOOR_FRICTION
                )
            }

            if ((item.position - TARGET_POSITION).length() <= TARGET_HIT_RADIUS) {
                score += 1
                item.position = item.spawnPosition
                item.velocity = ZERO
            }
        }
    }


    private companion object {
        private val ZERO = SandboxVector3(0f, 0f, 0f)
        private val DEFAULT_LEFT_CURSOR = SandboxVector3(-0.65f, -0.1f, -2.35f)
        private val DEFAULT_RIGHT_CURSOR = SandboxVector3(0.65f, -0.1f, -2.35f)
        private val TARGET_POSITION = SandboxVector3(0f, -0.85f, -2.65f)
        private const val TARGET_HIT_RADIUS = 0.48f
        private const val GAMEPAD_CURSOR_DEPTH = -2.35f
        private const val GAMEPAD_STICK_DEADZONE = 0.16f
        private const val PLAYER_STICK_DEADZONE = 0.16f
        private const val PLAYER_MOVE_SPEED = 2.4f
        // Keep Joy-Con locomotion inside the new 12 m × 12 m arena platform.
        private const val PLAYER_X_LIMIT = 5.0f
        private const val PLAYER_MIN_Z = -9.5f
        private const val PLAYER_MAX_Z = 1.2f
        private const val GAMEPAD_REACH_WIDTH = 3.0f
        private const val GAMEPAD_REACH_HEIGHT = 2.15f
        private const val GRAB_THRESHOLD = 0.45f
        private const val MAX_GRAB_DISTANCE = 1.2f
        private const val MIN_PALM_FINGER_LENGTH = 0.025f
        private const val MIN_FINGER_SEGMENT_RATIO = 0.055f
        private const val POWER_GRIP_FOLD_RATIO = 0.92f
        private const val POWER_GRIP_MIN_FINGERS = 3
        private const val FINGER_CONTACT_ACCELERATION = 5.5f
        private const val CONTACT_SEPARATION_GAIN = 7.0f
        private const val BALL_CONTACT_RADIUS = 0.25f
        private const val CUBE_CONTACT_RADIUS = 0.30f
        private val FINGER_MCP_INDICES = intArrayOf(5, 9, 13, 17)
        private val PALM_MCP_INDICES = intArrayOf(5, 9, 13, 17)
        private val FINGERTIP_INDICES = intArrayOf(4, 8, 12, 16, 20)
        private const val MAX_THROW_SPEED = 4.5f
        private const val GRAVITY = 3.2f
        private const val FLOOR_Y = -1.18f
        private const val FLOOR_BOUNCE = 0.28f
        private const val FLOOR_FRICTION = 0.82f
        private const val MIN_BOUNCE_SPEED = 0.22f
        private const val MIN_STEP_SECONDS = 0.001f
        private const val MAX_STEP_SECONDS = 0.05f
    }
}
