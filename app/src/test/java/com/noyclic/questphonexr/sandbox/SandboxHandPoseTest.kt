package com.noyclic.questphonexr.sandbox

import com.noyclic.questphonexr.tracking.HandPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxHandPoseTest {
    @Test
    fun mapsAllTwentyOneMediaPipePointsToTheReachPlane() {
        val points = MutableList(21) { HandPoint(0.5f, 0.5f, 0f) }
        points[0] = HandPoint(0.2f, 0.4f, 0f)
        points[8] = HandPoint(0.8f, 0.3f, -0.2f)

        val mapped = SandboxHandPoseMapper.map(points)

        assertEquals(21, mapped.size)
        assertEquals(-0.9f, mapped[0].x, 0.001f)
        assertEquals(0.215f, mapped[0].y, 0.001f)
        assertEquals(-2.55f, mapped[0].z, 0.001f)
        assertEquals(0.9f, mapped[8].x, 0.001f)
        assertTrue(mapped[8].z < mapped[0].z)
    }

    @Test
    fun ignoresLandmarksBeyondTheMediaPipeHandDefinition() {
        val mapped = SandboxHandPoseMapper.map(
            List(24) { HandPoint(0.5f, 0.5f, 0f) }
        )

        assertEquals(21, mapped.size)
    }

    @Test
    fun poseSmootherFiltersEveryJointAndCanReset() {
        val smoother = SandboxHandPoseSmoother(response = 0.5f)
        val first = List(21) { SandboxVector3(0f, 0f, -2.5f) }
        val second = List(21) { SandboxVector3(1f, 0.4f, -2.3f) }

        assertEquals(first, smoother.update(first))
        val filtered = smoother.update(second)
        assertEquals(0.5f, filtered[0].x, 0.001f)
        assertEquals(0.2f, filtered[8].y, 0.001f)
        assertEquals(-2.4f, filtered[20].z, 0.001f)

        smoother.reset()
        assertEquals(second, smoother.update(second))
    }

    @Test
    fun poseSmootherResetsWhenTrackingPointCountChanges() {
        val smoother = SandboxHandPoseSmoother(response = 0.5f)
        smoother.update(List(21) { SandboxVector3(0f, 0f, -2.5f) })
        val shortPose = listOf(SandboxVector3(1f, 0f, -2.5f))

        assertEquals(shortPose, smoother.update(shortPose))
    }
}
