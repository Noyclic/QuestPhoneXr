package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandTrackManagerTest {
    @Test
    fun persistentIdsFollowHandsWhenMediaPipeReordersItsResultList() {
        val manager = HandTrackManager()
        val first = manager.update(
            listOf(
                observation(HandSide.LEFT, 0.22f),
                observation(HandSide.RIGHT, 0.78f)
            ),
            1_000L
        )
        val leftId = first.single { it.side == HandSide.LEFT }.trackingId
        val rightId = first.single { it.side == HandSide.RIGHT }.trackingId

        val reordered = manager.update(
            listOf(
                observation(HandSide.RIGHT, 0.80f),
                observation(HandSide.LEFT, 0.20f)
            ),
            1_016L
        )

        assertEquals(leftId, reordered.single { it.side == HandSide.LEFT }.trackingId)
        assertEquals(rightId, reordered.single { it.side == HandSide.RIGHT }.trackingId)
    }

    @Test
    fun handednessPreventsIdentitySwapWhenHandsCross() {
        val manager = HandTrackManager()
        val first = manager.update(
            listOf(
                observation(HandSide.LEFT, 0.25f),
                observation(HandSide.RIGHT, 0.75f)
            ),
            1_000L
        )
        val leftId = first.single { it.side == HandSide.LEFT }.trackingId
        val rightId = first.single { it.side == HandSide.RIGHT }.trackingId

        manager.update(
            listOf(
                observation(HandSide.RIGHT, 0.62f),
                observation(HandSide.LEFT, 0.38f)
            ),
            1_032L
        )
        val crossed = manager.update(
            listOf(
                observation(HandSide.RIGHT, 0.42f),
                observation(HandSide.LEFT, 0.58f)
            ),
            1_064L
        )

        assertEquals(leftId, crossed.single { it.side == HandSide.LEFT }.trackingId)
        assertEquals(rightId, crossed.single { it.side == HandSide.RIGHT }.trackingId)
    }

    @Test
    fun pinchStateAndLandmarkFilterStayWithHandAfterResultReordering() {
        val manager = HandTrackManager()
        manager.update(
            listOf(
                observation(HandSide.LEFT, 0.22f, pinching = true),
                observation(HandSide.RIGHT, 0.78f, pinching = false)
            ),
            1_000L
        )
        val second = manager.update(
            listOf(
                observation(HandSide.LEFT, 0.22f, pinching = true),
                observation(HandSide.RIGHT, 0.78f, pinching = false)
            ),
            1_016L
        )
        assertTrue(second.single { it.side == HandSide.LEFT }.isPinching)
        assertFalse(second.single { it.side == HandSide.RIGHT }.isPinching)

        val reordered = manager.update(
            listOf(
                observation(HandSide.RIGHT, 0.78f, pinching = false),
                observation(HandSide.LEFT, 0.22f, pinching = true)
            ),
            1_032L
        )

        assertTrue(reordered.single { it.side == HandSide.LEFT }.isPinching)
        assertFalse(reordered.single { it.side == HandSide.RIGHT }.isPinching)
    }

    @Test
    fun pinchUsesFreshTipsEvenWhileRenderedLandmarksAreStillFiltered() {
        val manager = HandTrackManager()
        manager.update(listOf(observation(HandSide.LEFT, 0.4f, pinching = false)), 1_000L)
        manager.update(listOf(observation(HandSide.LEFT, 0.4f, pinching = false)), 1_016L)

        manager.update(listOf(observation(HandSide.LEFT, 0.4f, pinching = true)), 1_032L)
        val afterSecondPinchSample = manager.update(
            listOf(observation(HandSide.LEFT, 0.4f, pinching = true)),
            1_048L
        ).single()

        assertTrue("fresh pinch measurements should activate without waiting for the render filter",
            afterSecondPinchSample.isPinching)
        assertEquals(0f, afterSecondPinchSample.pinchDistanceRatio, 0f)
        val renderedTipGap = kotlin.math.sqrt(
            (afterSecondPinchSample.points[4].x - afterSecondPinchSample.points[8].x) *
                (afterSecondPinchSample.points[4].x - afterSecondPinchSample.points[8].x) +
                (afterSecondPinchSample.points[4].y - afterSecondPinchSample.points[8].y) *
                (afterSecondPinchSample.points[4].y - afterSecondPinchSample.points[8].y)
        )
        assertTrue("the visible mesh should remain independently smoothed", renderedTipGap > 0.05f)
    }

    @Test
    fun handTrackManagerUsesWorldGeometryForEdgeOnPinch() {
        val manager = HandTrackManager()
        val imagePoints = observation(HandSide.LEFT, 0.4f).points.toMutableList().also {
            it[5] = HandPoint(0.51f, 0.50f, 0f)
            it[17] = HandPoint(0.49f, 0.50f, 0f)
            it[4] = HandPoint(0.491f, 0.50f, 0f)
            it[8] = HandPoint(0.509f, 0.50f, 0f)
        }
        val worldPoints = List(21) { HandPoint(0f, 0f, 0f) }.toMutableList().also {
            it[5] = HandPoint(0.04f, 0f, 0f)
            it[17] = HandPoint(-0.04f, 0f, 0f)
            it[4] = HandPoint(-0.008f, 0f, 0f)
            it[8] = HandPoint(0.008f, 0f, 0f)
        }
        val edgeOnHand = observation(HandSide.LEFT, 0.4f).copy(
            points = imagePoints,
            worldPoints = worldPoints
        )

        val tracked = manager.update(listOf(edgeOnHand), 1_000L).single()

        assertTrue(tracked.isPinching)
        assertTrue(tracked.pinchUsesWorldLandmarks)
        assertTrue(tracked.pinchDistanceRatio < 0.25f)
    }

    @Test
    fun rawWorldPinchActivatesEvenWhenDisplayedWorldTipsAreRateLimited() {
        val manager = HandTrackManager()
        val image = observation(HandSide.LEFT, 0.4f).points
        val openWorld = worldPinchPoints(pinching = false)
        val closedWorld = worldPinchPoints(pinching = true)

        manager.update(
            listOf(observation(HandSide.LEFT, 0.4f).copy(
                points = image,
                worldPoints = openWorld
            )),
            1_000L
        )
        val pinched = manager.update(
            listOf(observation(HandSide.LEFT, 0.4f).copy(
                points = image,
                worldPoints = closedWorld
            )),
            1_016L
        ).single()

        val displayedGap = kotlin.math.sqrt(
            (pinched.worldPoints[4].x - pinched.worldPoints[8].x) *
                (pinched.worldPoints[4].x - pinched.worldPoints[8].x) +
                (pinched.worldPoints[4].y - pinched.worldPoints[8].y) *
                (pinched.worldPoints[4].y - pinched.worldPoints[8].y) +
                (pinched.worldPoints[4].z - pinched.worldPoints[8].z) *
                (pinched.worldPoints[4].z - pinched.worldPoints[8].z)
        )

        assertTrue("gesture input must use the fresh raw world landmarks", pinched.isPinching)
        assertTrue(pinched.pinchUsesWorldLandmarks)
        assertTrue("display world pose should remain independently filtered", displayedGap > 0.02f)
        assertEquals(0f, pinched.pinchDistanceRatio, 0f)
    }

    @Test
    fun shortOcclusionUsesBoundedDecayingMotionPrediction() {
        val manager = HandTrackManager()
        manager.update(listOf(observation(HandSide.LEFT, 0.4f)), 1_000L)
        val latest = manager.update(listOf(observation(HandSide.LEFT, 0.5f)), 1_016L).single()

        val duringGap = manager.update(emptyList(), 1_040L).single()

        assertTrue("brief gap should continue the last motion direction", duringGap.points[0].x > latest.points[0].x)
        assertTrue("prediction must stay tightly bounded", duringGap.points[0].x - latest.points[0].x <= 0.08f)
        assertEquals(latest.worldPoints, duringGap.worldPoints)
        assertEquals(latest.isPinching, duringGap.isPinching)
    }

    @Test
    fun palmScaleContinuityDisambiguatesAmbiguousUnknownHandReacquisition() {
        val manager = HandTrackManager()
        val initial = manager.update(
            listOf(
                observation(HandSide.UNKNOWN, 0.35f, palmScale = 0.65f),
                observation(HandSide.UNKNOWN, 0.65f, palmScale = 1.35f)
            ),
            1_000L
        )
        val smallHandId = initial.minBy { palmWidth(it.points) }.trackingId
        val largeHandId = initial.maxBy { palmWidth(it.points) }.trackingId

        val reacquired = manager.update(
            listOf(
                observation(HandSide.UNKNOWN, 0.5f, palmScale = 1.35f),
                observation(HandSide.UNKNOWN, 0.5f, palmScale = 0.65f)
            ),
            1_016L
        )

        assertEquals(smallHandId, reacquired.minBy { palmWidth(it.points) }.trackingId)
        assertEquals(largeHandId, reacquired.maxBy { palmWidth(it.points) }.trackingId)
    }

    @Test
    fun briefSingleFrameGapHoldsLastPoseThenHidesItAndKeepsIdentity() {
        val manager = HandTrackManager()
        val first = manager.update(listOf(observation(HandSide.LEFT, 0.4f)), 1_000L).single()

        val oneFrameGap = manager.update(emptyList(), 1_050L).single()
        assertEquals(first.trackingId, oneFrameGap.trackingId)
        assertEquals(first.points, oneFrameGap.points)

        assertTrue(manager.update(emptyList(), 1_090L).isEmpty())
        val recovered = manager.update(listOf(observation(HandSide.LEFT, 0.42f)), 1_100L).single()
        assertEquals(first.trackingId, recovered.trackingId)
    }

    @Test
    fun longDetectionGapReleasesPinchAndResetsMotionFilter() {
        val manager = HandTrackManager()
        manager.update(listOf(observation(HandSide.LEFT, 0.4f, pinching = true)), 1_000L)
        val pinched = manager.update(
            listOf(observation(HandSide.LEFT, 0.4f, pinching = true)),
            1_016L
        ).single()
        assertTrue(pinched.isPinching)

        assertTrue(manager.update(emptyList(), 1_200L).isEmpty())
        val recovered = manager.update(
            listOf(observation(HandSide.LEFT, 0.9f, pinching = false)),
            1_216L
        ).single()

        assertEquals(pinched.trackingId, recovered.trackingId)
        assertFalse(recovered.isPinching)
        assertTrue(recovered.pinchDistanceRatio.isFinite())
        assertEquals(0.9f, recovered.points[0].x, 0.0001f)
    }

    @Test
    fun mediumDropoutKeepsVisualFilterWarmButResetsMotionAndPinch() {
        val manager = HandTrackManager()
        manager.update(listOf(observation(HandSide.LEFT, 0.4f, pinching = false)), 1_000L)
        manager.update(listOf(observation(HandSide.LEFT, 0.4f, pinching = false)), 1_016L)

        val recovered = manager.update(
            listOf(observation(HandSide.LEFT, 0.9f, pinching = false)),
            1_176L
        ).single()

        assertTrue("the display filter should blend a medium-gap reacquisition",
            recovered.points[0].x > 0.4f && recovered.points[0].x < 0.9f)
        assertFalse("pinch state must not survive the medium dropout", recovered.isPinching)
    }

    @Test
    fun shortDetectionLossReleasesPinchAndMarksDistanceUnavailable() {
        val manager = HandTrackManager(pinchReleaseDelayMs = 30L)
        manager.update(listOf(observation(HandSide.LEFT, 0.4f, pinching = true)), 1_000L)
        manager.update(
            listOf(observation(HandSide.LEFT, 0.4f, pinching = true)),
            1_016L
        )

        val heldDuringLoss = manager.update(emptyList(), 1_048L).single()

        assertFalse(heldDuringLoss.isPinching)
        assertEquals(0f, heldDuringLoss.pinchStrength, 0f)
        assertTrue(heldDuringLoss.pinchDistanceRatio.isInfinite())
    }

    @Test
    fun expiredTrackGetsANewIdentity() {
        val manager = HandTrackManager()
        val first = manager.update(listOf(observation(HandSide.LEFT, 0.4f)), 1_000L).single()

        val recovered = manager.update(
            listOf(observation(HandSide.LEFT, 0.4f)),
            1_400L
        ).single()

        assertNotEquals(first.trackingId, recovered.trackingId)
    }

    @Test
    fun malformedLandmarksAreIgnoredWithoutCrashing() {
        val manager = HandTrackManager()
        val malformed = observation(HandSide.LEFT, 0.4f).copy(points = List(4) { HandPoint(0f, 0f, 0f) })

        assertTrue(manager.update(listOf(malformed), 1_000L).isEmpty())
    }

    @Test
    fun nonMonotonicTimestampResetsTrackingState() {
        val manager = HandTrackManager()
        val first = manager.update(listOf(observation(HandSide.LEFT, 0.4f)), 1_000L).single()

        val reset = manager.update(listOf(observation(HandSide.LEFT, 0.4f)), 900L).single()

        assertNotEquals(first.trackingId, reset.trackingId)
    }

    private fun observation(
        side: HandSide,
        centerX: Float,
        pinching: Boolean = false,
        palmScale: Float = 1f
    ): HandObservation {
        val points = MutableList(21) { HandPoint(centerX, 0.5f, 0f) }
        points[0] = HandPoint(centerX, 0.62f, 0f)
        points[5] = HandPoint(centerX + 0.10f, 0.44f, 0f)
        points[9] = HandPoint(centerX, 0.40f, 0f)
        points[13] = HandPoint(centerX - 0.05f, 0.43f, 0f)
        points[17] = HandPoint(centerX - 0.10f, 0.44f, 0f)
        if (pinching) {
            points[4] = HandPoint(centerX - 0.02f, 0.50f, 0f)
            points[8] = HandPoint(centerX - 0.02f, 0.50f, 0f)
        } else {
            points[4] = HandPoint(centerX - 0.08f, 0.52f, 0f)
            points[8] = HandPoint(centerX + 0.08f, 0.48f, 0f)
        }
        val scaledPoints = if (palmScale == 1f) points else points.map { point ->
            HandPoint(
                centerX + (point.x - centerX) * palmScale,
                0.5f + (point.y - 0.5f) * palmScale,
                point.z
            )
        }
        return HandObservation(
            points = scaledPoints,
            worldPoints = emptyList(),
            observedSide = side,
            handednessConfidence = if (side == HandSide.UNKNOWN) 0f else 0.99f
        )
    }

    private fun worldPinchPoints(pinching: Boolean): List<HandPoint> {
        return List(21) { HandPoint(0f, 0f, 0f) }.toMutableList().also {
            it[5] = HandPoint(0.04f, 0f, 0f)
            it[17] = HandPoint(-0.04f, 0f, 0f)
            if (pinching) {
                it[4] = HandPoint(0f, 0f, 0f)
                it[8] = HandPoint(0f, 0f, 0f)
            } else {
                it[4] = HandPoint(-0.09f, 0f, 0f)
                it[8] = HandPoint(0.09f, 0f, 0f)
            }
        }
    }

    private fun palmWidth(points: List<HandPoint>): Float {
        val dx = points[5].x - points[17].x
        val dy = points[5].y - points[17].y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}


class HandTrackingPresetRegressionTest {
    @org.junit.Test
    fun singleHandFastPresetUsesOneHandAndNoArtificialThrottle() {
        val preset = HandTrackingPreset.SINGLE_HAND_FAST
        org.junit.Assert.assertEquals(1, preset.maxTrackedHands)
        org.junit.Assert.assertEquals(640, preset.cameraWidthPx)
        org.junit.Assert.assertEquals(480, preset.cameraHeightPx)
        org.junit.Assert.assertEquals(0L, preset.minimumInferenceIntervalMs)
    }

    @org.junit.Test
    fun allPresetsStayWithinSupportedTrackingLimits() {
        HandTrackingPreset.entries.forEach { preset ->
            org.junit.Assert.assertTrue(preset.maxTrackedHands in 1..2)
            org.junit.Assert.assertTrue(preset.cameraWidthPx >= 160)
            org.junit.Assert.assertTrue(preset.cameraHeightPx >= 120)
            org.junit.Assert.assertTrue(preset.minimumInferenceIntervalMs in 0L..1_000L)
        }
    }

    @org.junit.Test
    fun storedFastPresetCanBeRestored() {
        org.junit.Assert.assertEquals(
            HandTrackingPreset.SINGLE_HAND_FAST,
            HandTrackingPreset.fromStoredName(HandTrackingPreset.SINGLE_HAND_FAST.name)
        )
    }
}
