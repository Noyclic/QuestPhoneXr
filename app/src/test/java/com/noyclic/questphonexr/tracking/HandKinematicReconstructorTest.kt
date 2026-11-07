package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandKinematicReconstructorTest {
    @Test
    fun reconstructsTwentySixJointsFromTwentyOneLandmarks() {
        val reconstructor = HandKinematicReconstructor()
        val result = reconstructor.reconstruct(sampleHand(), trackingId = 7L, timestampMs = 100L)

        assertEquals(26, result?.size)
        assertEquals(-0.00625f, result?.get(0)?.x ?: Float.NaN, 0.0001f)
        assertEquals(0.058f, result?.get(6)?.y ?: Float.NaN, 0.0001f)
        assertEquals(0.0696f, result?.get(11)?.y ?: Float.NaN, 0.0001f)
    }

    @Test
    fun filtersSmallJitterButRespondsToFastMovement() {
        val reconstructor = HandKinematicReconstructor()
        val first = sampleHand()
        val second = first.map { it.copy(x = it.x + 0.004f) }
        val third = first.map { it.copy(x = it.x + 0.20f) }

        val initial = reconstructor.reconstruct(first, 1L, 100L)!!
        val settled = reconstructor.reconstruct(second, 1L, 116L)!!
        val fast = reconstructor.reconstruct(third, 1L, 132L)!!

        val smallRawDelta = second[8].x - first[8].x
        val smallFilteredDelta = settled[9].x - initial[9].x
        assertTrue(kotlin.math.abs(smallFilteredDelta) < kotlin.math.abs(smallRawDelta))
        assertTrue(fast[9].x - settled[9].x > 0.10f)
    }

    @Test
    fun resetsOnTrackingIdentityChangeAndLongGap() {
        val reconstructor = HandKinematicReconstructor()
        val first = sampleHand()
        reconstructor.reconstruct(first, 1L, 100L)
        val moved = first.map { it.copy(x = it.x + 0.10f) }

        val changedIdentity = reconstructor.reconstruct(moved, 2L, 116L)!!
        assertEquals(moved[8].x, changedIdentity[9].x, 0.0001f)

        val afterGap = reconstructor.reconstruct(first, 2L, 500L)!!
        assertEquals(first[8].x, afterGap[9].x, 0.0001f)
    }

    @Test
    fun rejectsIncompleteOrNonFiniteLandmarksAndClearsHistory() {
        val reconstructor = HandKinematicReconstructor()
        assertNull(reconstructor.reconstruct(emptyList(), 1L, 100L))
        val invalid = sampleHand().toMutableList().also {
            it[8] = HandPoint(Float.NaN, 0f, 0f)
        }
        assertNull(reconstructor.reconstruct(invalid, 1L, 116L))
        val recovered = reconstructor.reconstruct(sampleHand(), 1L, 132L)
        assertTrue(recovered != null)
    }

    private fun sampleHand(): List<HandPoint> = List(21) { index ->
        when (index) {
            0 -> HandPoint(0f, 0f, 0f)
            5 -> HandPoint(0.04f, 0.10f, 0f)
            9 -> HandPoint(0f, 0.12f, 0f)
            13 -> HandPoint(-0.025f, 0.10f, 0f)
            17 -> HandPoint(-0.04f, 0.08f, 0f)
            else -> HandPoint(index * 0.001f, index * 0.002f, 0f)
        }
    }
}
