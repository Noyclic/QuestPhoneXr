package com.noyclic.questphonexr.tracking.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DockLatestPacketBufferTest {
    @Test
    fun newestPacketReplacesOldSampleWithoutQueueing() {
        val buffer = DockLatestPacketBuffer(SESSION_ID, maxAgeMs = 250L)
        val first = packet(sequence = 1L, captureTimestampNanos = 1_000L)
        val second = packet(sequence = 2L, captureTimestampNanos = 2_000L)

        assertNull(buffer.offer(first, receivedAtElapsedRealtimeNanos = 10_000_000L))
        assertNull(buffer.offer(second, receivedAtElapsedRealtimeNanos = 20_000_000L))

        assertEquals(second, buffer.latestFresh(nowElapsedRealtimeNanos = 20_000_000L))
    }

    @Test
    fun sampleRemainsReadableUntilItExpiresThenIsCleared() {
        val buffer = DockLatestPacketBuffer(SESSION_ID, maxAgeMs = 100L)
        val sample = packet(sequence = 1L, captureTimestampNanos = 1_000L)

        assertNull(buffer.offer(sample, receivedAtElapsedRealtimeNanos = 1_000_000_000L))
        assertEquals(sample, buffer.latestFresh(nowElapsedRealtimeNanos = 1_100_000_000L))
        assertNull(buffer.latestFresh(nowElapsedRealtimeNanos = 1_101_000_001L))
        assertNull(buffer.latestFresh(nowElapsedRealtimeNanos = 1_102_000_000L))
    }

    @Test
    fun rejectsOutOfOrderPacketsWithoutReplacingFreshSample() {
        val buffer = DockLatestPacketBuffer(SESSION_ID)
        val latest = packet(sequence = 4L, captureTimestampNanos = 4_000L)
        val replay = packet(sequence = 3L, captureTimestampNanos = 5_000L)

        assertNull(buffer.offer(latest, 10_000_000L))
        assertEquals(
            DockPacketRejection.STALE_SEQUENCE,
            buffer.offer(replay, 11_000_000L)
        )
        assertEquals(latest, buffer.latestFresh(12_000_000L))
    }

    @Test
    fun rejectsBadReceiveTimestampAndDoesNotStorePacket() {
        val buffer = DockLatestPacketBuffer(SESSION_ID)
        val sample = packet(1L, 1_000L)

        assertEquals(
            DockPacketRejection.INVALID_RECEIVE_TIMESTAMP,
            buffer.offer(sample, 0L)
        )
        assertNull(buffer.latestFresh(1L))
    }

    @Test
    fun aFutureReceiveTimestampIsNotExposedToAnEarlierClockRead() {
        val buffer = DockLatestPacketBuffer(SESSION_ID)
        val sample = packet(1L, 1_000L)

        assertNull(buffer.offer(sample, 50_000_000L))
        assertNull(buffer.latestFresh(49_000_000L))
        assertEquals(sample, buffer.latestFresh(50_000_000L))
    }

    @Test
    fun clearLatestKeepsReplayProtectionForThePairedSession() {
        val buffer = DockLatestPacketBuffer(SESSION_ID)
        val sample = packet(7L, 7_000L)

        assertNull(buffer.offer(sample, 10_000_000L))
        buffer.clearLatest()
        assertNull(buffer.latestFresh(11_000_000L))
        assertEquals(DockPacketRejection.STALE_SEQUENCE, buffer.offer(sample, 12_000_000L))
    }

    @Test
    fun acceptsAnEmptyFrameSoTheReceiverCanReleaseLostHands() {
        val buffer = DockLatestPacketBuffer(SESSION_ID)
        val handsDetected = packet(1L, 1_000L)
        val noHands = packet(2L, 2_000L).copy(hands = emptyList())

        assertNull(buffer.offer(handsDetected, 10_000_000L))
        assertNull(buffer.offer(noHands, 20_000_000L))
        assertTrue(buffer.latestFresh(21_000_000L)?.hands?.isEmpty() == true)
    }

    private fun packet(sequence: Long, captureTimestampNanos: Long) = DockTrackingPacket(
        sessionId = SESSION_ID,
        sequence = sequence,
        captureTimestampNanos = captureTimestampNanos,
        frameWidthPx = 320,
        frameHeightPx = 240,
        sensorRotationDegrees = 0,
        mirrored = false,
        hands = listOf(
            DockHandSample(
                trackId = 10L,
                side = DockHandSide.RIGHT,
                sideConfidence = 0.95f,
                landmarks = List(DockTrackingPacket.LANDMARK_COUNT) { index ->
                    DockLandmark(x = 0.25f + index * 0.002f, y = 0.5f, z = 0f)
                }
            )
        )
    )

    private companion object {
        const val SESSION_ID = "dock-session-01"
    }
}
