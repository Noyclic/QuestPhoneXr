package com.noyclic.questphonexr.sandbox

import com.noyclic.questphonexr.input.GamepadButtonCodes
import com.noyclic.questphonexr.input.PhysicalControllerSnapshot
import com.noyclic.questphonexr.tracking.HandSide
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.tracking.TrackedHand
import kotlin.math.sqrt

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
    val cameraTracked: Boolean = false
)

data class SandboxSceneSnapshot(
    val objects: List<SandboxObjectState>,
    val leftHand: SandboxHandState,
    val rightHand: SandboxHandState,
    val grabbedObjectCount: Int,
    val score: Int = 0
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
        val cameraTracked: Boolean
    )

    private val objects = mutableListOf<MutableObject>()
    private val leftCursorSmoother = SandboxCursorSmoother()
    private val rightCursorSmoother = SandboxCursorSmoother()
    private var leftCursor = DEFAULT_LEFT_CURSOR
    private var rightCursor = DEFAULT_RIGHT_CURSOR
    private var leftGrabbing = false
    private var rightGrabbing = false
    private var leftConnected = false
    private var rightConnected = false
    private var leftCameraTracked = false
    private var rightCameraTracked = false
    private var nextObjectId = 1
    private var score = 0

    init {
        reset()
    }

    @Synchronized
    fun reset() {
        objects.clear()
        nextObjectId = 1
        score = 0
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
        leftCursorSmoother.reset()
        rightCursorSmoother.reset()
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
        deltaSeconds: Float
    ) {
        val dt = deltaSeconds.takeIf { it.isFinite() }?.coerceIn(MIN_STEP_SECONDS, MAX_STEP_SECONDS)
            ?: MIN_STEP_SECONDS
        val handsBySide = handFrame.hands
            .filter { it.side != HandSide.UNKNOWN }
            .associateBy { it.side }
        val leftRaw = controllerFor(HandSide.LEFT, controllers, handsBySide[HandSide.LEFT])
        val rightRaw = controllerFor(HandSide.RIGHT, controllers, handsBySide[HandSide.RIGHT])
        val left = if (leftRaw.cameraTracked) {
            leftRaw.copy(cursor = leftCursorSmoother.update(leftRaw.cursor))
        } else {
            leftCursorSmoother.reset()
            leftRaw
        }
        val right = if (rightRaw.cameraTracked) {
            rightRaw.copy(cursor = rightCursorSmoother.update(rightRaw.cursor))
        } else {
            rightCursorSmoother.reset()
            rightRaw
        }

        leftCursor = left.cursor
        rightCursor = right.cursor
        leftConnected = left.connected
        rightConnected = right.connected
        leftCameraTracked = left.cameraTracked
        rightCameraTracked = right.cameraTracked

        updateGrab(HandSide.LEFT, left.grabbing, left.cursor, dt)
        updateGrab(HandSide.RIGHT, right.grabbing, right.cursor, dt)
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
                leftCameraTracked
            ),
            rightHand = SandboxHandState(
                HandSide.RIGHT,
                rightCursor,
                rightGrabbing,
                rightConnected,
                rightCameraTracked
            ),
            grabbedObjectCount = objects.count { it.heldBy != null },
            score = score
        )
    }

    private fun createObject(type: SandboxObjectType, position: SandboxVector3) =
        MutableObject(nextObjectId++, type, position)

    private fun controllerFor(
        hand: HandSide,
        controllers: List<PhysicalControllerSnapshot>,
        trackedHand: TrackedHand?
    ): ControllerState {
        val physical = controllers.firstOrNull { it.hand == hand }
        val wrist = trackedHand?.points?.getOrNull(0)
        val cameraCursor = wrist?.let {
            SandboxVector3(
                x = ((it.x - 0.5f) * CAMERA_CURSOR_WIDTH).coerceIn(-1.65f, 1.65f),
                y = ((0.5f - it.y) * CAMERA_CURSOR_HEIGHT).coerceIn(-1.15f, 1.15f),
                z = CAMERA_CURSOR_DEPTH
            )
        }
        val baseX = if (hand == HandSide.LEFT) -0.65f else 0.65f
        val gamepadCursor = physical?.let {
            SandboxVector3(
                x = (baseX + it.stickX * 0.9f).coerceIn(-1.65f, 1.65f),
                y = (-0.1f - it.stickY * 0.85f).coerceIn(-1.15f, 1.15f),
                z = GAMEPAD_CURSOR_DEPTH
            )
        }
        val fallbackCursor = if (hand == HandSide.LEFT) DEFAULT_LEFT_CURSOR else DEFAULT_RIGHT_CURSOR
        val cursor = cameraCursor ?: gamepadCursor ?: fallbackCursor
        val digitalGrab = physical?.pressedKeyCodes?.any {
            it == GamepadButtonCodes.BUTTON_L1 ||
                it == GamepadButtonCodes.BUTTON_R1 ||
                it == GamepadButtonCodes.BUTTON_L2 ||
                it == GamepadButtonCodes.BUTTON_R2
        } ?: false
        val analogGrab = physical?.let {
            it.leftTrigger > GRAB_THRESHOLD || it.rightTrigger > GRAB_THRESHOLD
        } ?: false
        val grabbing = trackedHand?.isPinching == true || digitalGrab || analogGrab
        return ControllerState(
            cursor = cursor,
            grabbing = grabbing,
            connected = trackedHand != null || physical != null,
            cameraTracked = trackedHand != null
        )
    }

    private fun updateGrab(
        hand: HandSide,
        isPressed: Boolean,
        cursor: SandboxVector3,
        deltaSeconds: Float
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
            currentlyHeld.velocity = (cursor - currentlyHeld.position)
                .times(1f / deltaSeconds)
                .clamped(MAX_THROW_SPEED)
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
        private const val CAMERA_CURSOR_WIDTH = 3.0f
        private const val CAMERA_CURSOR_HEIGHT = 2.15f
        private const val CAMERA_CURSOR_DEPTH = -2.55f
        private const val GAMEPAD_CURSOR_DEPTH = -2.35f
        private const val GRAB_THRESHOLD = 0.45f
        private const val MAX_GRAB_DISTANCE = 1.2f
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
