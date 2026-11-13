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
        assertEquals(TrackingProviderAvailability.PLANNED, states[TrackingProviderId.LITERT_CUSTOM])
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

        assertFalse(manager.selectProvider(TrackingProviderId.LITERT_CUSTOM))
        assertNull(
            manager.publishFrame(
                TrackingProviderId.LITERT_CUSTOM,
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
                TrackingProviderId.LITERT_CUSTOM,
                TrackingProviderAvailability.EXPERIMENTAL
            )
        )
        assertTrue(manager.selectProvider(TrackingProviderId.LITERT_CUSTOM))

        val frame = HandTrackingFrame(timestampMs = 20L, hands = emptyList())
        val routed = manager.publishFrame(
            TrackingProviderId.LITERT_CUSTOM,
            frame,
            qualityScore = 0.8f,
            receivedAtUptimeMs = 200L
        )

        assertEquals(TrackingProviderId.LITERT_CUSTOM, routed?.providerId)
        assertEquals(TrackingSelectionMode.MANUAL, manager.snapshot(200L).mode)
    }

    @Test
    fun manualModeDoesNotSilentlyFallBackToMediaPipe() {
        val manager = TrackingProviderManager()
        manager.updateAvailability(
            TrackingProviderId.LITERT_CUSTOM,
            TrackingProviderAvailability.READY
        )
        assertTrue(manager.selectProvider(TrackingProviderId.LITERT_CUSTOM))

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
            TrackingProviderId.LITERT_CUSTOM,
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
            TrackingProviderId.LITERT_CUSTOM,
            tfliteFrame,
            qualityScore = 1f,
            receivedAtUptimeMs = 105L
        )

        assertEquals(TrackingProviderId.LITERT_CUSTOM, routed?.providerId)
        assertEquals(TrackingProviderId.LITERT_CUSTOM, manager.snapshot(105L).activeProvider)
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
            TrackingProviderId.LITERT_CUSTOM,
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
            TrackingProviderId.LITERT_CUSTOM,
            fasterFrame,
            qualityScore = 0.9f,
            receivedAtUptimeMs = 105L
        )

        assertEquals(TrackingProviderId.LITERT_CUSTOM, routed?.providerId)
        assertEquals(25L, manager.snapshot(105L)
            .healthByProvider[TrackingProviderId.LITERT_CUSTOM]?.latestPipelineLatencyMs)
    }

    @Test
    fun automaticSelectionUsesHysteresisForNearEqualProviderScores() {
        val manager = TrackingProviderManager()
        manager.updateAvailability(
            TrackingProviderId.LITERT_CUSTOM,
            TrackingProviderAvailability.READY
        )
        val mediaPipe = HandTrackingFrame(
            timestampMs = 10L,
            hands = emptyList(),
            pipelineLatencyMs = 60L
        )
        assertEquals(
            TrackingProviderId.MEDIAPIPE,
            manager.publishFrame(
                TrackingProviderId.MEDIAPIPE,
                mediaPipe,
                qualityScore = 0.5f,
                receivedAtUptimeMs = 100L
            )?.providerId
        )

        // A marginally better score should not make active input bounce between providers.
        val nearTie = HandTrackingFrame(
            timestampMs = 11L,
            hands = emptyList(),
            pipelineLatencyMs = 60L
        )
        assertNull(
            manager.publishFrame(
                TrackingProviderId.LITERT_CUSTOM,
                nearTie,
                qualityScore = 0.52f,
                receivedAtUptimeMs = 105L
            )
        )
        assertEquals(TrackingProviderId.MEDIAPIPE, manager.snapshot(105L).activeProvider)

        // A clear measured improvement exceeds the switch margin and takes over.
        val better = HandTrackingFrame(
            timestampMs = 12L,
            hands = emptyList(),
            pipelineLatencyMs = 10L
        )
        val switched = manager.publishFrame(
            TrackingProviderId.LITERT_CUSTOM,
            better,
            qualityScore = 0.9f,
            receivedAtUptimeMs = 110L
        )
        assertEquals(TrackingProviderId.LITERT_CUSTOM, switched?.providerId)
        assertEquals(TrackingProviderId.LITERT_CUSTOM, manager.snapshot(110L).activeProvider)
    }

    @Test
    fun missingQualityScoreUsesNeutralBaseline() {
        val manager = TrackingProviderManager()
        manager.publishFrame(
            TrackingProviderId.MEDIAPIPE,
            HandTrackingFrame(timestampMs = 19L, hands = emptyList()),
            receivedAtUptimeMs = 100L
        )

        val quality = manager.snapshot(100L)
            .healthByProvider.getValue(TrackingProviderId.MEDIAPIPE)
            .latestQualityScore
        assertEquals(0.5f, quality!!, 0.001f)
    }

    @Test
    fun providerHealthReportsMeasuredFrameCadence() {
        val manager = TrackingProviderManager()
        assertNotNull(
            manager.publishFrame(
                TrackingProviderId.MEDIAPIPE,
                HandTrackingFrame(timestampMs = 10L, hands = emptyList()),
                receivedAtUptimeMs = 100L
            )
        )
        assertNotNull(
            manager.publishFrame(
                TrackingProviderId.MEDIAPIPE,
                HandTrackingFrame(timestampMs = 20L, hands = emptyList()),
                receivedAtUptimeMs = 150L
            )
        )

        val health = manager.snapshot(150L)
            .healthByProvider.getValue(TrackingProviderId.MEDIAPIPE)
        assertEquals(50f, health.averageFrameIntervalMs!!, 0.001f)
        assertEquals(20f, health.estimatedFramesPerSecond!!, 0.001f)
    }

    @Test
    fun autoModePrefersSustainedCadenceWhenQualityAndLatencyAreComparable() {
        val manager = TrackingProviderManager()
        assertTrue(
            manager.updateAvailability(
                TrackingProviderId.LITERT_CUSTOM,
                TrackingProviderAvailability.READY
            )
        )
        fun publish(provider: TrackingProviderId, timestamp: Long, receivedAt: Long) =
            manager.publishFrame(
                provider,
                HandTrackingFrame(
                    timestampMs = timestamp,
                    hands = emptyList(),
                    pipelineLatencyMs = 25L
                ),
                qualityScore = 0.9f,
                receivedAtUptimeMs = receivedAt
            )

        assertEquals(TrackingProviderId.MEDIAPIPE, publish(TrackingProviderId.MEDIAPIPE, 10L, 100L)?.providerId)
        publish(TrackingProviderId.LITERT_CUSTOM, 10L, 100L)

        // LiteRT produces a steady ~30 FPS stream while MediaPipe only updates
        // every 100 ms. At the same comparison time, AUTO should prefer the
        // sustained stream instead of rewarding only its per-frame inference time.
        publish(TrackingProviderId.LITERT_CUSTOM, 20L, 133L)
        publish(TrackingProviderId.LITERT_CUSTOM, 30L, 166L)
        publish(TrackingProviderId.LITERT_CUSTOM, 40L, 199L)
        publish(TrackingProviderId.MEDIAPIPE, 20L, 200L)
        publish(TrackingProviderId.LITERT_CUSTOM, 50L, 232L)
        publish(TrackingProviderId.LITERT_CUSTOM, 60L, 265L)
        publish(TrackingProviderId.MEDIAPIPE, 30L, 300L)

        // The incoming MediaPipe frame is accepted, but AUTO keeps LiteRT active.
        assertEquals(TrackingProviderId.LITERT_CUSTOM, manager.snapshot(300L).activeProvider)
        val health = manager.snapshot(300L)
            .healthByProvider.getValue(TrackingProviderId.LITERT_CUSTOM)
        assertEquals(1_000f / 33f, health.estimatedFramesPerSecond!!, 0.001f)
    }

    @Test
    fun cadenceWindowDropsOldSamplesWhenFull() {
        val manager = TrackingProviderManager(staleAfterMs = 5_000L)
        var receivedAt = 100L
        for (index in 0..31) {
            if (index == 1) receivedAt += 1_000L
            else if (index > 1) receivedAt += 50L
            assertNotNull(
                manager.publishFrame(
                    TrackingProviderId.MEDIAPIPE,
                    HandTrackingFrame(timestampMs = 10L + index, hands = emptyList()),
                    receivedAtUptimeMs = receivedAt
                )
            )
        }

        val health = manager.snapshot(receivedAt)
            .healthByProvider.getValue(TrackingProviderId.MEDIAPIPE)
        // The one old 1000 ms interval is outside the 30-sample window.
        assertEquals(50f, health.averageFrameIntervalMs!!, 0.001f)
    }

    @Test
    fun longCameraPauseResetsCadenceWindowOnResume() {
        val manager = TrackingProviderManager(staleAfterMs = 300L)
        manager.publishFrame(
            TrackingProviderId.MEDIAPIPE,
            HandTrackingFrame(timestampMs = 10L, hands = emptyList()),
            receivedAtUptimeMs = 100L
        )
        manager.publishFrame(
            TrackingProviderId.MEDIAPIPE,
            HandTrackingFrame(timestampMs = 20L, hands = emptyList()),
            receivedAtUptimeMs = 150L
        )
        assertEquals(
            20f,
            manager.snapshot(150L).healthByProvider
                .getValue(TrackingProviderId.MEDIAPIPE).estimatedFramesPerSecond!!,
            0.001f
        )

        manager.publishFrame(
            TrackingProviderId.MEDIAPIPE,
            HandTrackingFrame(timestampMs = 30L, hands = emptyList()),
            receivedAtUptimeMs = 600L
        )
        val resumed = manager.snapshot(600L)
            .healthByProvider.getValue(TrackingProviderId.MEDIAPIPE)
        assertNull(resumed.averageFrameIntervalMs)
        assertNull(resumed.estimatedFramesPerSecond)

        manager.publishFrame(
            TrackingProviderId.MEDIAPIPE,
            HandTrackingFrame(timestampMs = 40L, hands = emptyList()),
            receivedAtUptimeMs = 650L
        )
        assertEquals(
            20f,
            manager.snapshot(650L).healthByProvider
                .getValue(TrackingProviderId.MEDIAPIPE).estimatedFramesPerSecond!!,
            0.001f
        )
    }

    @Test
    fun rejectsOutOfOrderReceiveTimeWithoutCorruptingCadence() {
        val manager = TrackingProviderManager()
        assertNotNull(
            manager.publishFrame(
                TrackingProviderId.MEDIAPIPE,
                HandTrackingFrame(timestampMs = 10L, hands = emptyList()),
                receivedAtUptimeMs = 100L
            )
        )
        assertNull(
            manager.publishFrame(
                TrackingProviderId.MEDIAPIPE,
                HandTrackingFrame(timestampMs = 20L, hands = emptyList()),
                receivedAtUptimeMs = 99L
            )
        )
        val health = manager.snapshot(100L)
            .healthByProvider.getValue(TrackingProviderId.MEDIAPIPE)
        assertEquals(1L, health.rejectedFrames)
        assertEquals(TrackingFrameRejection.OUT_OF_ORDER_RECEIVE_TIMESTAMP, health.lastRejection)
        assertNull(health.averageFrameIntervalMs)
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
