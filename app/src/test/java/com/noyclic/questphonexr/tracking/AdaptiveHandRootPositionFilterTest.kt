package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveHandRootPositionFilterTest {
    @Test
    fun suppressesSmallCameraPlaneJitter() {
        val filter = AdaptiveHandRootPositionFilter()
        val first = filter.update(0.50f, 0.50f, 3L, 100L)!!
        val jittered = filter.update(0.504f, 0.497f, 3L, 116L)!!

        assertTrue(kotlin.math.abs(jittered.x - first.x) < 0.004f)
        assertTrue(kotlin.math.abs(jittered.y - first.y) < 0.003f)
    }

    @Test
    fun increasesResponseForFastDeliberateMovement() {
        val filter = AdaptiveHandRootPositionFilter()
        filter.update(0.50f, 0.50f, 3L, 100L)
        val slow = filter.update(0.504f, 0.50f, 3L, 116L)!!
        val fast = filter.update(0.80f, 0.50f, 3L, 132L)!!

        assertTrue("large movement should not be frozen", fast.x - slow.x > 0.05f)
        assertTrue(fast.x < 0.80f)
    }

    @Test
    fun resetsAcrossTrackingIdentityAndLongGap() {
        val filter = AdaptiveHandRootPositionFilter()
        filter.update(0.50f, 0.50f, 3L, 100L)
        filter.update(0.60f, 0.50f, 3L, 116L)

        val changedIdentity = filter.update(0.20f, 0.70f, 4L, 132L)!!
        assertEquals(0.20f, changedIdentity.x, 0.0001f)
        assertEquals(0.70f, changedIdentity.y, 0.0001f)

        filter.update(0.30f, 0.60f, 4L, 148L)
        val afterGap = filter.update(0.75f, 0.25f, 4L, 500L)!!
        assertEquals(0.75f, afterGap.x, 0.0001f)
        assertEquals(0.25f, afterGap.y, 0.0001f)
    }

    @Test
    fun clampsLargeCameraPlaneJumpsByWorldSpaceSpeed() {
        val filter = AdaptiveHandRootPositionFilter()
        val first = filter.update(0.50f, 0.50f, 3L, 1_000L, metersPerNormalizedUnit = 6f)!!
        val jumped = filter.update(0.90f, 0.50f, 3L, 1_033L, metersPerNormalizedUnit = 6f)!!
        val movedMeters = (jumped.x - first.x) * 6f
        assertTrue("a jump should still begin moving", movedMeters > 0f)
        assertTrue("moved $movedMeters m in 33 ms", movedMeters <= 8f * 0.033f + 0.002f)
    }

    @Test
    fun ignoresDuplicateTimestampInsteadOfReprocessingDifferentCoordinates() {
        val filter = AdaptiveHandRootPositionFilter()
        val first = filter.update(0.50f, 0.50f, 3L, 1_000L)!!
        val duplicate = filter.update(0.90f, 0.10f, 3L, 1_000L)!!
        assertEquals(first, duplicate)
    }

    @Test
    fun rejectsInvalidInputAndClearsHistory() {
        val filter = AdaptiveHandRootPositionFilter()
        filter.update(0.50f, 0.50f, 3L, 100L)
        assertNull(filter.update(Float.NaN, 0.50f, 3L, 116L))
        val recovered = filter.update(0.25f, 0.75f, 3L, 132L)!!
        assertEquals(0.25f, recovered.x, 0.0001f)
        assertEquals(0.75f, recovered.y, 0.0001f)
    }
}
