package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingProviderManagerTest {

    @Test
    fun mediaPipeIsTheOnlyInitiallyReadyProvider() {
        val manager = TrackingProviderManager()
        val states = manager.snapshot(100L).statusByProvider

        assertEquals(TrackingProviderAvailability.READY, states[TrackingProviderId.MEDIAPIPE])
        assertEquals(TrackingProviderAvailability.PLANNED, states[TrackingProviderId.TFLITE_CUSTOM])
        assertEquals(TrackingProviderAvailability.PLANNED, states[TrackingProviderId.GVXRS_DOCK])
    }

    @Test
    fun autoModeAcceptsMediaPipeAndRoutesItsFrame() {
        val manager = TrackingProviderManager()
        val frame = HandTrackingFrame(timestampMs = 10L, hands = emptyList())

        val routed = manager.publishFrame(
            TrackingProviderId.MEDIAPIPE,
            frame,
            qualityScore = 0.9f,
            receivedAtUptimeMs = 100L
        )

        assertNotNull(routed)
        assertEquals(TrackingProviderId.MEDIAPIPE, routed?.providerId)
        assertEquals(frame, routed?.frame)
        assertEquals(TrackingProviderId.MEDIAPIPE, manager.snapshot(100L).activeProvider)
    }

    @Test
    fun plannedProviderCannotPublishOrBeSelected() {
        val manager = TrackingProviderManager()
        val frame = HandTrackingFrame(timestampMs = 10L, hands = emptyList())

        assertFalse(manager.selectProvider(TrackingProviderId.TFLITE_CUSTOM))
        assertNull(
            manager.publishFrame(
                TrackingProviderId.TFLITE_CUSTOM,
                frame,
                receivedAtUptimeMs = 100L
            )
        )
    }

    @Test
    fun newlyActivatedProviderCanBeSelectedAndUsed() {
        val manager = TrackingProviderManager()
        assertTrue(
            manager.updateAvailability(
                TrackingProviderId.TFLITE_CUSTOM,
                TrackingProviderAvailability.EXPERIMENTAL
            )
        )
        assertTrue(manager.selectProvider(TrackingProviderId.TFLITE_CUSTOM))

        val frame = HandTrackingFrame(timestampMs = 20L, hands = emptyList())
        val routed = manager.publishFrame(
            TrackingProviderId.TFLITE_CUSTOM,
            frame,
            qualityScore = 0.8f,
            receivedAtUptimeMs = 200L
        )

        assertEquals(TrackingProviderId.TFLITE_CUSTOM, routed?.providerId)
        assertEquals(TrackingSelectionMode.MANUAL, manager.snapshot(200L).mode)
    }

    @Test
    fun manualModeDoesNotSilentlyFallBackToMediaPipe() {
        val manager = TrackingProviderManager()
        manager.updateAvailability(
            TrackingProviderId.TFLITE_CUSTOM,
            TrackingProviderAvailability.READY
        )
        assertTrue(manager.selectProvider(TrackingProviderId.TFLITE_CUSTOM))

        val mediaPipeFrame = HandTrackingFrame(timestampMs = 10L, hands = emptyList())
        assertNull(
            manager.publishFrame(
                TrackingProviderId.MEDIAPIPE,
                mediaPipeFrame,
                receivedAtUptimeMs = 100L
            )
        )
        assertNull(manager.activeFrame(100L))
    }

    @Test
    fun automaticModePrefersHigherQualityFreshProviderWhenPriorityIsClose() {
        val manager = TrackingProviderManager()
        manager.updateAvailability(
            TrackingProviderId.TFLITE_CUSTOM,
            TrackingProviderAvailability.READY
        )
        manager.publishFrame(
            TrackingProviderId.MEDIAPIPE,
            HandTrackingFrame(timestampMs = 10L, hands = emptyList()),
            qualityScore = 0.2f,
            receivedAtUptimeMs = 100L
        )
        val tfliteFrame = HandTrackingFrame(timestampMs = 11L, hands = emptyList())
        val routed = manager.publishFrame(
            TrackingProviderId.TFLITE_CUSTOM,
            tfliteFrame,
            qualityScore = 1f,
            receivedAtUptimeMs = 105L
        )

        assertEquals(TrackingProviderId.TFLITE_CUSTOM, routed?.providerId)
        assertEquals(TrackingProviderId.TFLITE_CUSTOM, manager.snapshot(105L).activeProvider)
    }

    @Test
    fun staleFramesAreNotExposed() {
        val manager = TrackingProviderManager(staleAfterMs = 100L)
        manager.publishFrame(
            TrackingProviderId.MEDIAPIPE,
            HandTrackingFrame(timestampMs = 10L, hands = emptyList()),
            receivedAtUptimeMs = 100L
        )

        assertNotNull(manager.activeFrame(200L))
        assertNull(manager.activeFrame(201L))
        assertEquals("no_ready_provider_has_a_fresh_frame", manager.snapshot(201L).reason)
    }

    @Test
    fun rejectsInvalidQualityAndOutOfOrderSamples() {
        val manager = TrackingProviderManager()
        val first = HandTrackingFrame(timestampMs = 20L, hands = emptyList())
        assertNotNull(
            manager.publishFrame(
                TrackingProviderId.MEDIAPIPE,
                first,
                qualityScore = 0.5f,
                receivedAtUptimeMs = 100L
            )
        )

        assertNull(
            manager.publishFrame(
                TrackingProviderId.MEDIAPIPE,
                HandTrackingFrame(timestampMs = 21L, hands = emptyList()),
                qualityScore = Float.NaN,
                receivedAtUptimeMs = 110L
            )
        )
        assertNull(
            manager.publishFrame(
                TrackingProviderId.MEDIAPIPE,
                HandTrackingFrame(timestampMs = 20L, hands = emptyList()),
                qualityScore = 0.8f,
                receivedAtUptimeMs = 120L
            )
        )
        assertEquals(20L, manager.activeFrame(120L)?.frame?.timestampMs)
    }

    @Test
    fun autoModePrefersLowerLatencyProviderWhenQualityIsComparable() {
        val manager = TrackingProviderManager()
        manager.updateAvailability(
            TrackingProviderId.TFLITE_CUSTOM,
            TrackingProviderAvailability.READY
        )
        manager.publishFrame(
            TrackingProviderId.MEDIAPIPE,
            HandTrackingFrame(
                timestampMs = 10L,
                hands = emptyList(),
                pipelineLatencyMs = 130L
            ),
            qualityScore = 0.9f,
            receivedAtUptimeMs = 100L
        )

        val fasterFrame = HandTrackingFrame(
            timestampMs = 11L,
            hands = emptyList(),
            pipelineLatencyMs = 25L
        )
        val routed = manager.publishFrame(
            TrackingProviderId.TFLITE_CUSTOM,
            fasterFrame,
            qualityScore = 0.9f,
            receivedAtUptimeMs = 105L
        )

        assertEquals(TrackingProviderId.TFLITE_CUSTOM, routed?.providerId)
        assertEquals(25L, manager.snapshot(105L)
            .healthByProvider[TrackingProviderId.TFLITE_CUSTOM]?.latestPipelineLatencyMs)
    }

    @Test
    fun providerHealthTracksAcceptedAndRejectedObservations() {
        val manager = TrackingProviderManager()
        val accepted = HandTrackingFrame(
            timestampMs = 20L,
            hands = emptyList(),
            pipelineLatencyMs = 42L
        )
        assertNotNull(
            manager.publishFrame(
                TrackingProviderId.MEDIAPIPE,
                accepted,
                qualityScore = 0.77f,
                receivedAtUptimeMs = 100L
            )
        )
        assertNull(
            manager.publishFrame(
                TrackingProviderId.MEDIAPIPE,
                HandTrackingFrame(timestampMs = 21L, hands = emptyList()),
                qualityScore = Float.NaN,
                receivedAtUptimeMs = 110L
            )
        )

        val health = manager.snapshot(110L)
            .healthByProvider.getValue(TrackingProviderId.MEDIAPIPE)
        assertEquals(1L, health.acceptedFrames)
        assertEquals(1L, health.rejectedFrames)
        assertEquals(42L, health.latestPipelineLatencyMs)
        assertEquals(42f, health.averagePipelineLatencyMs, 0.001f)
        assertEquals(0.77f, health.latestQualityScore!!, 0.001f)
        assertEquals(TrackingFrameRejection.INVALID_QUALITY_SCORE, health.lastRejection)
    }

    @Test
    fun rejectsHandsWithTooFewLandmarksAndRecordsReason() {
        val manager = TrackingProviderManager()
        val incompleteHand = TrackedHand(points = listOf(HandPoint(0.5f, 0.5f, 0f)))
        val result = manager.publishFrame(
            TrackingProviderId.MEDIAPIPE,
            HandTrackingFrame(timestampMs = 30L, hands = listOf(incompleteHand)),
            receivedAtUptimeMs = 100L
        )

        assertNull(result)
        val health = manager.snapshot(100L)
            .healthByProvider.getValue(TrackingProviderId.MEDIAPIPE)
        assertEquals(1L, health.rejectedFrames)
        assertEquals(TrackingFrameRejection.INSUFFICIENT_LANDMARKS, health.lastRejection)
    }

    @Test
    fun clearFramesKeepsSelectionButDropsAllSamples() {
        val manager = TrackingProviderManager()
        manager.publishFrame(
            TrackingProviderId.MEDIAPIPE,
            HandTrackingFrame(timestampMs = 30L, hands = emptyList()),
            receivedAtUptimeMs = 100L
        )

        manager.clearFrames()

        assertNull(manager.activeFrame(100L))
        assertEquals(TrackingSelectionMode.AUTO, manager.snapshot(100L).mode)
    }
}
