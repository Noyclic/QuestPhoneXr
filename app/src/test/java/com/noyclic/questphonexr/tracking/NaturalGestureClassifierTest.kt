package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalGestureClassifierTest {
    @Test
    fun recognizesOpenPalmPointVictoryAndThumbsUp() {
        assertGesture(NaturalHandGesture.OPEN_PALM, hand(setOf(0, 1, 2, 3), thumbExtended = true))
        assertGesture(NaturalHandGesture.POINT, hand(setOf(0), thumbExtended = false))
        assertGesture(NaturalHandGesture.VICTORY, hand(setOf(0, 1), thumbExtended = false))
        assertGesture(NaturalHandGesture.THUMBS_UP, hand(emptySet(), thumbExtended = true))
    }

    @Test
    fun usesWorldLandmarksFirstAndFallsBackToImageCoordinates() {
        val world = hand(setOf(0), thumbExtended = false)
        val worldResult = stableResult(world)
        assertTrue(worldResult.usedWorldLandmarks)

        val imageOnly = world.copy(worldPoints = emptyList(), rawWorldPoints = emptyList())
        val imageResult = stableResult(imageOnly)
        assertFalse(imageResult.usedWorldLandmarks)
        assertEquals(NaturalHandGesture.POINT, imageResult.gesture)
    }

    @Test
    fun readsRawLandmarksInsteadOfTheSmoothedRenderMesh() {
        val rawPoint = hand(setOf(0), thumbExtended = false)
        val misleadingMesh = hand(emptySet(), thumbExtended = false).points
        val sample = rawPoint.copy(points = misleadingMesh, worldPoints = emptyList())
        assertEquals(NaturalHandGesture.POINT, stableResult(sample).gesture)
    }

    @Test
    fun requiresTwoDistinctObservationsAndIgnoresDuplicateHeldFrames() {
        val classifier = NaturalGestureClassifier()
        val sample = hand(setOf(0), thumbExtended = false, trackingId = 3L)
        assertEquals(NaturalHandGesture.NONE, classifier.update(sample.copy(rawTimestampMs = 100L), 100L).gesture)
        assertEquals(NaturalHandGesture.NONE, classifier.update(sample.copy(rawTimestampMs = 100L), 133L).gesture)
        assertEquals(NaturalHandGesture.POINT, classifier.update(sample.copy(rawTimestampMs = 133L), 166L).gesture)
    }

    @Test
    fun clearsOnTrackingIdentityChangeLossAndLongOrReversedTimestamps() {
        val classifier = NaturalGestureClassifier()
        val open = hand(setOf(0, 1, 2, 3), thumbExtended = true, trackingId = 1L)
        classifier.update(open, 100L)
        assertEquals(NaturalHandGesture.OPEN_PALM, classifier.update(open, 133L).gesture)

        val newIdentity = open.copy(trackingId = 2L)
        assertEquals(NaturalHandGesture.NONE, classifier.update(newIdentity, 150L).gesture)
        assertEquals(NaturalHandGesture.OPEN_PALM, classifier.update(newIdentity, 183L).gesture)

        assertEquals(NaturalHandGesture.NONE, classifier.update(null, 200L).gesture)
        assertEquals(NaturalHandGesture.NONE, classifier.update(open, 220L).gesture)
        assertEquals(NaturalHandGesture.OPEN_PALM, classifier.update(open, 253L).gesture)

        val point = hand(setOf(0), thumbExtended = false, trackingId = 1L)
        assertEquals(NaturalHandGesture.NONE, classifier.update(point, 600L).gesture)
        assertEquals(NaturalHandGesture.POINT, classifier.update(point, 633L).gesture)
        assertEquals(NaturalHandGesture.NONE, classifier.update(point, 620L).gesture)
    }

    @Test
    fun ambiguousIncompleteAndNonFiniteHandsDoNotCreateGestures() {
        val classifier = NaturalGestureClassifier()
        val point = hand(setOf(0), thumbExtended = false)
        val incomplete = point.copy(rawPoints = point.rawPoints.dropLast(1), rawWorldPoints = emptyList())
        assertEquals(NaturalHandGesture.NONE, classifier.update(incomplete, 100L).gesture)

        val invalid = point.copy(rawWorldPoints = point.rawWorldPoints.toMutableList().also {
            it[8] = HandPoint(Float.NaN, 0f, 0f)
        })
        assertEquals(NaturalHandGesture.NONE, classifier.update(invalid, 133L).gesture)

        val ambiguousPoints = point.rawWorldPoints.toMutableList().also {
            // Bend the index DIP and tip toward the palm: neither reliably extended nor curled.
            it[7] = HandPoint(it[7].x + 0.018f, it[7].y - 0.012f, it[7].z)
            it[8] = HandPoint(it[8].x + 0.020f, it[8].y - 0.024f, it[8].z)
        }
        val ambiguous = point.copy(rawWorldPoints = ambiguousPoints)
        assertEquals(NaturalHandGesture.NONE, classifier.update(ambiguous, 166L).gesture)
        assertEquals(NaturalHandGesture.NONE, classifier.update(ambiguous.copy(rawTimestampMs = 199L), 199L).gesture)
    }

    private fun assertGesture(expected: NaturalHandGesture, sample: TrackedHand) {
        assertEquals(expected, stableResult(sample).gesture)
    }

    private fun stableResult(sample: TrackedHand): NaturalGestureResult {
        val classifier = NaturalGestureClassifier()
        classifier.update(sample.copy(rawTimestampMs = 100L), 100L)
        return classifier.update(sample.copy(rawTimestampMs = 133L), 133L)
    }

    private fun hand(
        extendedFingers: Set<Int>,
        thumbExtended: Boolean,
        trackingId: Long = 1L
    ): TrackedHand {
        val points = MutableList(21) { HandPoint(0f, 0f, 0f) }
        points[0] = HandPoint(0f, 0f, 0f)

        val thumb = if (thumbExtended) {
            listOf(
                HandPoint(-0.035f, 0.020f, 0f),
                HandPoint(-0.060f, 0.040f, 0f),
                HandPoint(-0.085f, 0.060f, 0f),
                HandPoint(-0.110f, 0.080f, 0f)
            )
        } else {
            listOf(
                HandPoint(0.025f, 0.020f, 0f),
                HandPoint(0.015f, 0.040f, 0f),
                HandPoint(0.005f, 0.025f, 0f),
                HandPoint(0.015f, 0.012f, 0f)
            )
        }
        for (index in 1..4) points[index] = thumb[index - 1]

        val mcps = intArrayOf(5, 9, 13, 17)
        val bases = listOf(
            HandPoint(0.040f, 0.040f, 0f),
            HandPoint(0.012f, 0.052f, 0f),
            HandPoint(-0.018f, 0.045f, 0f),
            HandPoint(-0.045f, 0.030f, 0f)
        )
        for (finger in mcps.indices) {
            val mcp = bases[finger]
            val mcpIndex = mcps[finger]
            points[mcpIndex] = mcp
            if (finger in extendedFingers) {
                points[mcpIndex + 1] = HandPoint(mcp.x, mcp.y + 0.035f, 0f)
                points[mcpIndex + 2] = HandPoint(mcp.x, mcp.y + 0.070f, 0f)
                points[mcpIndex + 3] = HandPoint(mcp.x, mcp.y + 0.105f, 0f)
            } else {
                points[mcpIndex + 1] = HandPoint(mcp.x, mcp.y + 0.025f, 0f)
                points[mcpIndex + 2] = HandPoint(mcp.x + 0.018f, mcp.y + 0.025f, 0f)
                points[mcpIndex + 3] = HandPoint(mcp.x + 0.028f, mcp.y + 0.005f, 0f)
            }
        }

        val image = points.map { point ->
            HandPoint(0.5f + point.x * 3f, 0.8f - point.y * 3f, 0f)
        }
        return TrackedHand(
            points = image,
            worldPoints = points,
            trackingId = trackingId,
            rawPoints = image,
            rawWorldPoints = points
        )
    }
}
