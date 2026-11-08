package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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

        // OpenXR joint 10 is the index fingertip (MediaPipe landmark 8).
        val smallRawDelta = second[8].x - first[8].x
        val smallFilteredDelta = settled[10].x - initial[10].x
        assertTrue(kotlin.math.abs(smallFilteredDelta) < kotlin.math.abs(smallRawDelta))
        assertTrue(fast[10].x - settled[10].x > 0.10f)
    }

    @Test
    fun softBoneLengthConstraintReducesSingleFrameFingerStretch() {
        val reconstructor = HandKinematicReconstructor()
        val first = sampleHand()
        val initial = reconstructor.reconstruct(first, 1L, 100L)!!

        // OpenXR index MCP/PIP correspond to MediaPipe landmarks 5 and 6.
        // A one-frame depth jump on landmark 6 should not stretch that segment
        // as much in the reconstructed joint buffer.
        val noisy = first.toMutableList().also { point ->
            point[6] = point[6].copy(y = point[6].y + 0.08f)
        }
        val reconstructed = reconstructor.reconstruct(noisy, 1L, 116L)!!

        val referenceLength = distance(initial[7], initial[8])
        val rawObservedLength = distance(noisy[5], noisy[6])
        val stabilizedLength = distance(reconstructed[7], reconstructed[8])

        assertTrue(referenceLength > 0.01f)
        assertTrue(
            kotlin.math.abs(stabilizedLength - referenceLength) <
                kotlin.math.abs(rawObservedLength - referenceLength)
        )
    }

    @Test
    fun palmConstraintSoftensSingleFrameKnuckleSpacingOutlier() {
        val reconstructor = HandKinematicReconstructor()
        val first = sampleHand()
        val initial = reconstructor.reconstruct(first, 1L, 100L)!!

        // MediaPipe landmark 9 is the middle-finger MCP. A one-frame lateral
        // jump should not make the middle knuckle detach from its neighbors.
        val noisy = first.toMutableList().also { points ->
            points[9] = points[9].copy(x = points[9].x - 0.12f)
        }
        val reconstructed = reconstructor.reconstruct(noisy, 1L, 116L)!!

        val referenceGap = distance(initial[7], initial[12])
        val rawGap = distance(noisy[5], noisy[9])
        val stabilizedGap = distance(reconstructed[7], reconstructed[12])

        assertTrue(referenceGap > 0.002f)
        assertTrue(abs(stabilizedGap - referenceGap) < abs(rawGap - referenceGap))
    }

    @Test
    fun palmConstraintAdaptsToSustainedFingerSplay() {
        val reconstructor = HandKinematicReconstructor()
        val first = sampleHand()
        val initial = reconstructor.reconstruct(first, 1L, 100L)!!
        val initialGap = distance(initial[7], initial[12])
        var latest = initial

        // Hold a deliberately wider middle-finger MCP position over time.
        // The spacing reference must follow this sustained pose rather than
        // permanently forcing the hand back to its first observed shape.
        val splayed = first.toMutableList().also { points ->
            points[9] = points[9].copy(x = -0.06f)
        }
        for (frame in 1..60) {
            latest = reconstructor.reconstruct(splayed, 1L, 100L + frame * 16L)!!
        }

        val adaptedGap = distance(latest[7], latest[12])
        assertTrue(adaptedGap > initialGap * 1.5f)
    }

    @Test
    fun softConstraintStabilizesEstimatedMetacarpalToMcpConnection() {
        val reconstructor = HandKinematicReconstructor()
        val first = sampleHand()
        val initial = reconstructor.reconstruct(first, 1L, 100L)!!

        val noisy = first.toMutableList().also { point ->
            point[5] = point[5].copy(x = point[5].x + 0.10f)
        }
        val reconstructed = reconstructor.reconstruct(noisy, 1L, 116L)!!

        val referenceLength = distance(initial[6], initial[7])
        val wrist = noisy[0]
        val estimatedBase = HandPoint(
            wrist.x + (noisy[5].x - wrist.x) * 0.58f,
            wrist.y + (noisy[5].y - wrist.y) * 0.58f,
            wrist.z + (noisy[5].z - wrist.z) * 0.58f
        )
        val rawLength = distance(estimatedBase, noisy[5])
        val stabilizedLength = distance(reconstructed[6], reconstructed[7])

        assertTrue(referenceLength > 0.001f)
        assertTrue(abs(stabilizedLength - referenceLength) < abs(rawLength - referenceLength))
    }

    @Test
    fun resetsOnTrackingIdentityChangeAndLongGap() {
        val reconstructor = HandKinematicReconstructor()
        val first = sampleHand()
        reconstructor.reconstruct(first, 1L, 100L)
        val moved = first.map { it.copy(x = it.x + 0.10f) }

        val changedIdentity = reconstructor.reconstruct(moved, 2L, 116L)!!
        assertEquals(moved[8].x, changedIdentity[10].x, 0.0001f)

        val afterGap = reconstructor.reconstruct(first, 2L, 500L)!!
        assertEquals(first[8].x, afterGap[10].x, 0.0001f)
    }

    @Test
    fun resetsOnTimestampRollback() {
        val reconstructor = HandKinematicReconstructor()
        val first = sampleHand()
        reconstructor.reconstruct(first, 1L, 100L)
        val moved = first.map { it.copy(x = it.x + 0.10f) }
        reconstructor.reconstruct(moved, 1L, 116L)

        val afterRollback = reconstructor.reconstruct(first, 1L, 110L)!!
        assertEquals(first[8].x, afterRollback[10].x, 0.0001f)
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

    private fun distance(a: HandPoint, b: HandPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
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
