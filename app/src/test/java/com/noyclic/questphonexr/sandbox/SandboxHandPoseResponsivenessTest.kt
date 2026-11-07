package com.noyclic.questphonexr.sandbox

import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxHandPoseResponsivenessTest {
    @Test
    fun defaultPoseSmootherDoesNotAddStrongSecondaryLag() {
        val smoother = SandboxHandPoseSmoother()
        val first = List(21) { SandboxVector3(0f, 0f, -2.5f) }
        val second = List(21) { SandboxVector3(1f, 0f, -2.5f) }

        smoother.update(first)
        val next = smoother.update(second)

        assertTrue("Expected minimal secondary lag, got ${next[0].x}", next[0].x > 0.85f)
    }
}
