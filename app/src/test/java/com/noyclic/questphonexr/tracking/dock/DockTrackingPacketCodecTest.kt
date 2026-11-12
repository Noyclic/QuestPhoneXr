package com.noyclic.questphonexr.tracking.dock

import org.junit.Assert.assertArrayEquals
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DockTrackingPacketCodecTest {
    @Test
    fun roundTripsPacketWithTwoHandsAndAllMetadata() {
        val original = packet(
            sequence = 42L,
            timestampNanos = 9_876_543_210L,
            hands = listOf(hand(11L, DockHandSide.LEFT), hand(22L, DockHandSide.RIGHT))
        ).copy(
            frameWidthPx = 640,
            frameHeightPx = 480,
            sensorRotationDegrees = 270,
            mirrored = true
        )

        val encoded = DockTrackingPacketCodec.encode(original)
        val decoded = DockTrackingPacketCodec.decode(encoded)

        assertTrue(decoded is DockPacketDecodeResult.Success)
        assertEquals(original, (decoded as DockPacketDecodeResult.Success).packet)
        assertTrue("landmark payload should remain compact", encoded.size < 700)
    }

    @Test
    fun lengthPrefixedStreamFrameRoundTripsWithoutClosingTheStream() {
        val original = packet(9L, 900L)
        val output = ByteArrayOutputStream()

        DockTrackingPacketCodec.writeFrame(output, original)
        val decoded = DockTrackingPacketCodec.readFrame(ByteArrayInputStream(output.toByteArray()))

        assertEquals(original, (decoded as DockPacketDecodeResult.Success).packet)
    }

    @Test
    fun streamReaderRejectsInvalidLengthBeforeAllocating() {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).writeInt(2_049)

        val decoded = DockTrackingPacketCodec.readFrame(ByteArrayInputStream(output.toByteArray()))

        assertEquals(
            DockPacketDecodeFailure.PAYLOAD_TOO_LARGE,
            (decoded as DockPacketDecodeResult.Rejected).reason
        )
    }

    @Test
    fun streamReaderRejectsTruncatedLengthPrefixedPayload() {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).apply {
            writeInt(8)
            write(byteArrayOf(1, 2, 3))
        }

        val decoded = DockTrackingPacketCodec.readFrame(ByteArrayInputStream(output.toByteArray()))

        assertEquals(
            DockPacketDecodeFailure.TRUNCATED_PAYLOAD,
            (decoded as DockPacketDecodeResult.Rejected).reason
        )
    }

    @Test
    fun roundTripsAnEmptyHandFrame() {
        val original = packet(sequence = 1L, timestampNanos = 100L, hands = emptyList())

        val decoded = DockTrackingPacketCodec.decode(DockTrackingPacketCodec.encode(original))

        assertEquals(original, (decoded as DockPacketDecodeResult.Success).packet)
    }

    @Test
    fun rejectsEmptyOversizedAndBadMagicPayloads() {
        assertEquals(
            DockPacketDecodeFailure.EMPTY_PAYLOAD,
            (DockTrackingPacketCodec.decode(byteArrayOf()) as DockPacketDecodeResult.Rejected).reason
        )
        assertEquals(
            DockPacketDecodeFailure.PAYLOAD_TOO_LARGE,
            (DockTrackingPacketCodec.decode(ByteArray(2_049)) as DockPacketDecodeResult.Rejected).reason
        )

        val badMagic = DockTrackingPacketCodec.encode(packet(1L, 100L)).also {
            it[0] = 0
        }
        assertEquals(
            DockPacketDecodeFailure.INVALID_MAGIC,
            (DockTrackingPacketCodec.decode(badMagic) as DockPacketDecodeResult.Rejected).reason
        )
    }

    @Test
    fun rejectsTruncatedAndTrailingPayloadBytes() {
        val encoded = DockTrackingPacketCodec.encode(packet(1L, 100L))
        val truncated = encoded.copyOf(encoded.size - 1)
        val trailing = encoded + byteArrayOf(0x55)

        assertEquals(
            DockPacketDecodeFailure.TRUNCATED_PAYLOAD,
            (DockTrackingPacketCodec.decode(truncated) as DockPacketDecodeResult.Rejected).reason
        )
        assertEquals(
            DockPacketDecodeFailure.TRAILING_DATA,
            (DockTrackingPacketCodec.decode(trailing) as DockPacketDecodeResult.Rejected).reason
        )
    }

    @Test
    fun rejectsInvalidPacketsBeforeEncoding() {
        val invalid = packet(1L, 100L).copy(
            hands = listOf(hand(1L, DockHandSide.LEFT).copy(sideConfidence = Float.NaN))
        )

        val failure = runCatching { DockTrackingPacketCodec.encode(invalid) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun decodeRejectsUnknownSideCode() {
        val encoded = DockTrackingPacketCodec.encode(packet(1L, 100L))
        // Header: magic/version/session/sequence/timestamp/geometry/rotation/mirror/count.
        // The first hand starts after the length-prefixed session and fixed packet metadata.
        val handOffset = 4 + 4 + 1 + SESSION_ID.length + 8 + 8 + 4 + 4 + 4 + 1 + 1
        encoded[handOffset + 8] = 99

        assertEquals(
            DockPacketDecodeFailure.INVALID_HAND_SIDE,
            (DockTrackingPacketCodec.decode(encoded) as DockPacketDecodeResult.Rejected).reason
        )
    }

    @Test
    fun encodingIsDeterministicForTheSamePacket() {
        val packet = packet(8L, 800L)

        assertArrayEquals(
            DockTrackingPacketCodec.encode(packet),
            DockTrackingPacketCodec.encode(packet)
        )
    }

    @Test
    fun sequenceGuardRejectsDecodedReplay() {
        val packet = packet(5L, 500L)
        val decoded = DockTrackingPacketCodec.decode(DockTrackingPacketCodec.encode(packet))
            as DockPacketDecodeResult.Success
        val guard = DockTrackingSequenceGuard(SESSION_ID)

        assertNull(guard.accept(decoded.packet))
        assertEquals(DockPacketRejection.STALE_SEQUENCE, guard.accept(decoded.packet))
    }

    private fun packet(
        sequence: Long,
        timestampNanos: Long,
        hands: List<DockHandSample> = listOf(hand(11L, DockHandSide.LEFT))
    ) = DockTrackingPacket(
        sessionId = SESSION_ID,
        sequence = sequence,
        captureTimestampNanos = timestampNanos,
        frameWidthPx = 320,
        frameHeightPx = 240,
        sensorRotationDegrees = 0,
        mirrored = false,
        hands = hands
    )

    private fun hand(id: Long, side: DockHandSide) = DockHandSample(
        trackId = id,
        side = side,
        sideConfidence = 0.96f,
        landmarks = List(DockTrackingPacket.LANDMARK_COUNT) { index ->
            DockLandmark(x = 0.2f + index * 0.005f, y = 0.4f, z = -0.03f)
        }
    )

    private companion object {
        const val SESSION_ID = "dock-session-01"
    }
}
