package com.noyclic.questphonexr.sandbox

import com.noyclic.questphonexr.tracking.HandSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxWorldHandPoseSmootherTest {
    @Test
    fun firstPoseIsImmediateAndLaterUpdatesAreCriticallyDamped() {
        val smoother = SandboxWorldHandPoseSmoother()
        val first = handPose(0f, -2.5f)
        val target = handPose(0.8f, -3.0f)

        assertEquals(first, smoother.update(first, 1f / 30f, HandSide.LEFT, 1L))

        var previousX = 0f
        repeat(8) {
            val rendered = smoother.update(target, 1f / 30f, HandSide.LEFT, 1L)
            val currentX = rendered[0].x
            assertTrue("hand should keep moving toward the target", currentX >= previousX)
            assertTrue("critical damping must not overshoot", currentX <= target[0].x)
            previousX = currentX
        }
        assertTrue("rendered 3D pose should converge toward its target",
            previousX > target[0].x - 0.03f)
    }

    @Test
    fun fastPalmMotionGetsAQuickerVisualResponseThanSlowMotion() {
        val slow = SandboxWorldHandPoseSmoother()
        val fast = SandboxWorldHandPoseSmoother()
        val origin = handPose(0f)
        slow.update(origin, 1f / 30f, HandSide.RIGHT, 7L)
        fast.update(origin, 1f / 30f, HandSide.RIGHT, 7L)

        val slowTarget = handPose(0.04f)
        // Keep the fast test trajectory below the independent 8 m/s outlier
        // limiter so this assertion measures adaptive spring response itself.
        val fastTarget = handPose(0.18f)
        val slowOutput = slow.update(slowTarget, 1f / 30f, HandSide.RIGHT, 7L)
        val fastOutput = fast.update(fastTarget, 1f / 30f, HandSide.RIGHT, 7L)

        val slowFraction = slowOutput[0].x / slowTarget[0].x
        val fastFraction = fastOutput[0].x / fastTarget[0].x
        assertTrue("fast movement should be followed with less relative lag",
            fastFraction > slowFraction)
    }

    @Test
    fun allLandmarksUseOneSharedResponseAndKeepTheHandShapeCoherent() {
        val smoother = SandboxWorldHandPoseSmoother()
        val first = handPose(0f)
        smoother.update(first, 1f / 30f, HandSide.LEFT, 2L)
        val target = first.mapIndexed { index, point ->
            point.copy(
                x = point.x + 0.3f + index * 0.002f,
                y = point.y + index * 0.003f,
                z = point.z - 0.2f
            )
        }

        val output = smoother.update(target, 1f / 30f, HandSide.LEFT, 2L)

        val initialRelativeX = first[8].x - first[0].x
        val targetRelativeX = target[8].x - target[0].x
        val outputRelativeX = output[8].x - output[0].x
        assertTrue(outputRelativeX > initialRelativeX)
        assertTrue(outputRelativeX < targetRelativeX)
        assertTrue(output.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
    }

    @Test
    fun depthAndLateralMotionAreSmoothedTogetherInWorldSpace() {
        val smoother = SandboxWorldHandPoseSmoother()
        val first = handPose(0f, -2f)
        smoother.update(first, 1f / 30f, HandSide.LEFT, 8L)
        val target = handPose(0.5f, -3.5f)

        val rendered = smoother.update(target, 1f / 30f, HandSide.LEFT, 8L)

        assertTrue(rendered[0].x > first[0].x)
        assertTrue(rendered[0].x < target[0].x)
        assertTrue("the rendered palm should approach the new depth smoothly",
            rendered[0].z < first[0].z && rendered[0].z > target[0].z)
    }

    @Test
    fun newTrackingIdentityResetsWithoutDraggingThePreviousHand() {
        val smoother = SandboxWorldHandPoseSmoother()
        smoother.update(handPose(0f), 1f / 30f, HandSide.LEFT, 10L)
        smoother.update(handPose(0.8f), 1f / 30f, HandSide.LEFT, 10L)

        val replacement = handPose(-0.7f)
        val rendered = smoother.update(replacement, 1f / 30f, HandSide.LEFT, 11L)

        assertEquals(replacement, rendered)
    }

    @Test
    fun missingPoseResetsTheSmootherAndNextHandStartsCleanly() {
        val smoother = SandboxWorldHandPoseSmoother()
        smoother.update(handPose(0f), 1f / 30f, HandSide.RIGHT, 3L)
        smoother.update(handPose(0.7f), 1f / 30f, HandSide.RIGHT, 3L)

        assertTrue(smoother.update(emptyList(), 1f / 30f, HandSide.RIGHT, 3L).isEmpty())
        val replacement = handPose(-0.5f)
        assertEquals(replacement,
            smoother.update(replacement, 1f / 30f, HandSide.RIGHT, 3L))
    }

    @Test
    fun invalidJointHoldsItsLastRenderedPositionWithoutPoisoningThePose() {
        val smoother = SandboxWorldHandPoseSmoother()
        val first = smoother.update(handPose(0f), 1f / 30f, HandSide.LEFT, 4L)
        val invalid = handPose(0.5f).toMutableList().also {
            it[8] = it[8].copy(z = Float.NaN)
        }
        val held = smoother.update(invalid, 1f / 30f, HandSide.LEFT, 4L)
        assertEquals(first[8], held[8])
        assertTrue(held.all { it.hasFiniteCoordinates() })
        val valid = smoother.update(handPose(0.2f), 1f / 30f, HandSide.LEFT, 4L)
        assertTrue(valid.all { it.hasFiniteCoordinates() })
        assertTrue(valid[8].x > held[8].x)
    }

    @Test
    fun rejectsMalformedFirstPoseUntilAValidTrackArrives() {
        val smoother = SandboxWorldHandPoseSmoother()
        val invalid = handPose(0.5f).toMutableList().also {
            it[3] = it[3].copy(x = Float.POSITIVE_INFINITY)
        }
        assertTrue(smoother.update(invalid, 1f / 30f, HandSide.LEFT, 7L).isEmpty())
        val valid = handPose(0.1f)
        assertEquals(valid, smoother.update(valid, 1f / 30f, HandSide.LEFT, 7L))
    }

    @Test
    fun renderPoseDisplacementIsBoundedAtDifferentFrameRates() {
        val smoother = SandboxWorldHandPoseSmoother()
        val first = smoother.update(handPose(0f), 1f / 30f, HandSide.LEFT, 8L)
        val next = smoother.update(handPose(5f), 1f / 30f, HandSide.LEFT, 8L)
        val moved = (next[8] - first[8]).length()
        assertTrue("pose should begin converging", moved > 0f)
        assertTrue("moved $moved m in one 30 FPS step", moved <= 8f / 30f + 0.001f)
    }

    @Test
    fun subMillimeterStationaryJitterIsDampedWithoutDelayingInteractionInput() {
        val smoother = SandboxWorldHandPoseSmoother()
        val origin = handPose(0f)
        smoother.update(origin, 1f / 30f, HandSide.RIGHT, 12L)

        var maximumRenderedOffset = 0f
        repeat(40) { index ->
            val jitter = if (index % 2 == 0) 0.0005f else -0.0005f
            val rendered = smoother.update(
                handPose(jitter),
                1f / 30f,
                HandSide.RIGHT,
                12L
            )
            maximumRenderedOffset = maxOf(
                maximumRenderedOffset,
                kotlin.math.abs(rendered[0].x)
            )
        }

        assertTrue(
            "sub-millimeter camera jitter should be attenuated in the rendered hand",
            maximumRenderedOffset < 0.0005f
        )
    }

    private fun handPose(x: Float, depth: Float = -2.5f): List<SandboxVector3> =
        List(21) { index ->
            SandboxVector3(
                x = x + (index % 5) * 0.02f,
                y = (index / 5) * 0.03f,
                z = depth + (index % 3) * 0.01f
            )
        }
}
