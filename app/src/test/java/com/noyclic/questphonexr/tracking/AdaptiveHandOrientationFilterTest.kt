package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class AdaptiveHandOrientationFilterTest {
    @Test
    fun suppressesSmallPalmRotationJitter() {
        val filter = AdaptiveHandOrientationFilter()
        val initial = quaternionZ(0f)
        val measured = quaternionZ(0.008f)

        val first = filter.update(initial, trackingId = 4L, timestampMs = 100L)!!
        val output = filter.update(measured, trackingId = 4L, timestampMs = 116L)!!

        assertTrue(rotationDistance(first, output) < rotationDistance(initial, measured))
        assertEquals(1f, norm(output), 0.0001f)
    }

    @Test
    fun respondsProportionallyFasterToFastPalmRotation() {
        val slowFilter = AdaptiveHandOrientationFilter()
        val fastFilter = AdaptiveHandOrientationFilter()
        slowFilter.update(quaternionZ(0f), 1L, 100L)
        fastFilter.update(quaternionZ(0f), 1L, 100L)

        val smallTarget = quaternionZ(0.008f)
        val fastTarget = quaternionZ(0.5f)
        val slowOutput = slowFilter.update(smallTarget, 1L, 116L)!!
        val fastOutput = fastFilter.update(fastTarget, 1L, 116L)!!

        val slowFraction = rotationDistance(quaternionZ(0f), slowOutput) / 0.008f
        val fastFraction = rotationDistance(quaternionZ(0f), fastOutput) / 0.5f
        assertTrue(fastFraction > slowFraction)
    }

    @Test
    fun treatsNegatedQuaternionAsTheSameRotation() {
        val filter = AdaptiveHandOrientationFilter()
        filter.update(quaternionZ(0f), 1L, 100L)
        val equivalent = floatArrayOf(0f, 0f, 0f, -1f)

        val output = filter.update(equivalent, 1L, 116L)!!

        assertEquals(0f, rotationDistance(quaternionZ(0f), output), 0.0001f)
        assertEquals(1f, norm(output), 0.0001f)
    }

    @Test
    fun resetsForIdentityChangesAndLongTrackingGaps() {
        val filter = AdaptiveHandOrientationFilter()
        filter.update(quaternionZ(0f), 1L, 100L)
        filter.update(quaternionZ(0.4f), 1L, 116L)

        val changedHand = quaternionZ(-0.2f)
        val afterIdentityChange = filter.update(changedHand, 2L, 132L)!!
        assertEquals(0f, rotationDistance(changedHand, afterIdentityChange), 0.0001f)

        filter.update(quaternionZ(0.3f), 2L, 148L)
        val afterGap = quaternionZ(-0.1f)
        val gapOutput = filter.update(afterGap, 2L, 500L)!!
        assertEquals(0f, rotationDistance(afterGap, gapOutput), 0.0001f)
    }

    @Test
    fun rejectsInvalidQuaternionAndClearsHistory() {
        val filter = AdaptiveHandOrientationFilter()
        filter.update(quaternionZ(0f), 1L, 100L)

        assertNull(filter.update(floatArrayOf(0f, 0f, 0f, 0f), 1L, 116L))
        val next = quaternionZ(0.25f)
        val recovered = filter.update(next, 1L, 132L)!!
        assertEquals(0f, rotationDistance(next, recovered), 0.0001f)
    }

    private fun quaternionZ(angleRadians: Float): FloatArray = floatArrayOf(
        0f, 0f, sin(angleRadians / 2f), cos(angleRadians / 2f)
    )

    private fun norm(q: FloatArray): Float =
        kotlin.math.sqrt(q.sumOf { (it * it).toDouble() }).toFloat()

    private fun rotationDistance(a: FloatArray, b: FloatArray): Float {
        val dot = abs(a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat())
            .coerceIn(0f, 1f)
        return 2f * acos(dot)
    }
}
