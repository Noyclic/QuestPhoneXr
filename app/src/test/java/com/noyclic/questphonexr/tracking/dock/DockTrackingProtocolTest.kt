package com.noyclic.questphonexr.tracking.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DockTrackingProtocolTest {
    @Test
    fun acceptsAWellFormedLatestStatePacket() {
        val packet = packet(sequence = 1L, timestampNanos = 1_000L)

        assertNull(DockTrackingPacketValidator.validate(packet))
        assertNull(DockTrackingSequenceGuard(SESSION_ID).accept(packet))
    }

    @Test
    fun rejectsUnsupportedProtocolVersion() {
        val result = DockTrackingPacketValidator.validate(
            packet(sequence = 1L, timestampNanos = 1_000L).copy(protocolVersion = 99)
        )

        assertEquals(DockPacketRejection.UNSUPPORTED_VERSION, result)
    }

    @Test
    fun rejectsMalformedFrameGeometryAndRotation() {
        val valid = packet(sequence = 1L, timestampNanos = 1_000L)

        assertEquals(
            DockPacketRejection.INVALID_FRAME_SIZE,
            DockTrackingPacketValidator.validate(valid.copy(frameWidthPx = 0))
        )
        assertEquals(
            DockPacketRejection.INVALID_FRAME_SIZE,
            DockTrackingPacketValidator.validate(valid.copy(frameWidthPx = 1_920, frameHeightPx = 1_920))
        )
        assertEquals(
            DockPacketRejection.INVALID_SENSOR_ROTATION,
            DockTrackingPacketValidator.validate(valid.copy(sensorRotationDegrees = 45))
        )
    }

    @Test
    fun rejectsTooManyHandsAndDuplicateTrackIds() {
        val hand = hand(trackId = 7L)
        val tooMany = packet(1L, 1_000L).copy(hands = listOf(hand, hand.copy(trackId = 8L), hand.copy(trackId = 9L)))
        val duplicateIds = packet(1L, 1_000L).copy(hands = listOf(hand, hand))

        assertEquals(
            DockPacketRejection.TOO_MANY_HANDS,
            DockTrackingPacketValidator.validate(tooMany)
        )
        assertEquals(
            DockPacketRejection.DUPLICATE_TRACK_ID,
            DockTrackingPacketValidator.validate(duplicateIds)
        )
    }

    @Test
    fun rejectsInvalidLandmarkCountAndNonFiniteCoordinates() {
        val valid = packet(1L, 1_000L)
        val shortHand = hand(7L).copy(landmarks = List(20) { DockLandmark(0.5f, 0.5f, 0f) })
        val nonFiniteHand = hand(7L).copy(
            landmarks = landmarks().toMutableList().also {
                it[8] = DockLandmark(Float.NaN, 0.5f, 0f)
            }
        )

        assertEquals(
            DockPacketRejection.INVALID_LANDMARK_COUNT,
            DockTrackingPacketValidator.validate(valid.copy(hands = listOf(shortHand)))
        )
        assertEquals(
            DockPacketRejection.INVALID_LANDMARK,
            DockTrackingPacketValidator.validate(valid.copy(hands = listOf(nonFiniteHand)))
        )
    }

    @Test
    fun rejectsInvalidConfidenceAndOutOfRangeLandmarks() {
        val valid = packet(1L, 1_000L)
        val invalidConfidence = hand(7L).copy(sideConfidence = 1.1f)
        val invalidCoordinate = hand(7L).copy(
            landmarks = landmarks().toMutableList().also {
                it[0] = DockLandmark(2f, 0.5f, 0f)
            }
        )

        assertEquals(
            DockPacketRejection.INVALID_HANDEDNESS_CONFIDENCE,
            DockTrackingPacketValidator.validate(valid.copy(hands = listOf(invalidConfidence)))
        )
        assertEquals(
            DockPacketRejection.INVALID_LANDMARK,
            DockTrackingPacketValidator.validate(valid.copy(hands = listOf(invalidCoordinate)))
        )
    }

    @Test
    fun sequenceGuardRejectsDuplicatesAndOutOfOrderPackets() {
        val guard = DockTrackingSequenceGuard(SESSION_ID)

        assertNull(guard.accept(packet(1L, 1_000L)))
        assertEquals(
            DockPacketRejection.STALE_SEQUENCE,
            guard.accept(packet(1L, 1_100L))
        )
        assertNull(guard.accept(packet(2L, 1_200L)))
        assertEquals(
            DockPacketRejection.STALE_SEQUENCE,
            guard.accept(packet(1L, 1_300L))
        )
    }

    @Test
    fun sequenceGuardRejectsNonMonotonicCaptureTimestamp() {
        val guard = DockTrackingSequenceGuard(SESSION_ID)

        assertNull(guard.accept(packet(1L, 2_000L)))
        assertEquals(
            DockPacketRejection.NON_MONOTONIC_TIMESTAMP,
            guard.accept(packet(2L, 1_999L))
        )
        // A rejected sample must not advance the accepted sequence watermark.
        assertNull(guard.accept(packet(2L, 2_100L)))
    }

    @Test
    fun sequenceGuardRejectsPacketsFromAnotherPairingSession() {
        val guard = DockTrackingSequenceGuard(SESSION_ID)

        assertEquals(
            DockPacketRejection.WRONG_SESSION,
            guard.accept(packet(1L, 1_000L).copy(sessionId = "another-session"))
        )
        assertNull(guard.accept(packet(1L, 1_100L)))
    }

    @Test
    fun resetStartsANewSequenceWindowForTheSamePairedSession() {
        val guard = DockTrackingSequenceGuard(SESSION_ID)

        assertNull(guard.accept(packet(50L, 5_000L)))
        guard.reset()
        assertNull(guard.accept(packet(1L, 1_000L)))
    }

    private fun packet(sequence: Long, timestampNanos: Long) = DockTrackingPacket(
        sessionId = SESSION_ID,
        sequence = sequence,
        captureTimestampNanos = timestampNanos,
        frameWidthPx = 640,
        frameHeightPx = 480,
        sensorRotationDegrees = 0,
        mirrored = false,
        hands = listOf(hand(trackId = 7L))
    )

    private fun hand(trackId: Long) = DockHandSample(
        trackId = trackId,
        side = DockHandSide.LEFT,
        sideConfidence = 0.98f,
        landmarks = landmarks()
    )

    private fun landmarks() = List(DockTrackingPacket.LANDMARK_COUNT) { index ->
        DockLandmark(x = 0.3f + index * 0.001f, y = 0.5f, z = 0f)
    }

    private companion object {
        const val SESSION_ID = "dock-session-01"
    }
}
