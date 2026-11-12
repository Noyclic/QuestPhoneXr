package com.noyclic.questphonexr.tracking.dock

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

enum class DockPacketDecodeFailure {
    EMPTY_PAYLOAD,
    PAYLOAD_TOO_LARGE,
    INVALID_FRAME_LENGTH,
    INVALID_MAGIC,
    UNSUPPORTED_VERSION,
    INVALID_SESSION_LENGTH,
    INVALID_MIRROR_FLAG,
    INVALID_HAND_COUNT,
    INVALID_HAND_SIDE,
    TRUNCATED_PAYLOAD,
    TRAILING_DATA,
    INVALID_PACKET
}

sealed class DockPacketDecodeResult {
    data class Success(val packet: DockTrackingPacket) : DockPacketDecodeResult()

    data class Rejected(
        val reason: DockPacketDecodeFailure,
        val validationError: DockPacketRejection? = null
    ) : DockPacketDecodeResult()
}

/**
 * Small, bounded, big-endian wire codec for Dock Station latest-state packets.
 *
 * This codec provides framing and structural validation only. It does not provide transport
 * encryption or peer authentication; those must be supplied by the pairing/connection layer.
 */
object DockTrackingPacketCodec {
    private const val MAGIC = 0x47565844 // "GVXD"
    private const val MAX_PAYLOAD_BYTES = 2_048
    private const val MIN_SESSION_ID_LENGTH = 8
    private const val MAX_SESSION_ID_LENGTH = 64
    private const val MIRRORED_FALSE = 0
    private const val MIRRORED_TRUE = 1

    fun encode(packet: DockTrackingPacket): ByteArray {
        val validationError = DockTrackingPacketValidator.validate(packet)
        require(validationError == null) {
            "Cannot encode invalid Dock Station packet: $validationError"
        }

        val sessionBytes = packet.sessionId.toByteArray(StandardCharsets.US_ASCII)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(packet.protocolVersion)
            data.writeByte(sessionBytes.size)
            data.write(sessionBytes)
            data.writeLong(packet.sequence)
            data.writeLong(packet.captureTimestampNanos)
            data.writeInt(packet.frameWidthPx)
            data.writeInt(packet.frameHeightPx)
            data.writeInt(packet.sensorRotationDegrees)
            data.writeByte(if (packet.mirrored) MIRRORED_TRUE else MIRRORED_FALSE)
            data.writeByte(packet.hands.size)

            for (hand in packet.hands) {
                data.writeLong(hand.trackId)
                data.writeByte(hand.side.toWireCode())
                data.writeFloat(hand.sideConfidence)
                for (landmark in hand.landmarks) {
                    data.writeFloat(landmark.x)
                    data.writeFloat(landmark.y)
                    data.writeFloat(landmark.z)
                }
            }
        }

        val payload = output.toByteArray()
        check(payload.size <= MAX_PAYLOAD_BYTES) {
            "Encoded Dock Station packet exceeds the maximum payload size."
        }
        return payload
    }

    /**
     * Writes one length-prefixed frame for a byte-stream transport such as TLS/TCP.
     * The caller owns the stream and is responsible for closing it.
     */
    fun writeFrame(output: OutputStream, packet: DockTrackingPacket) {
        val payload = encode(packet)
        val data = DataOutputStream(output)
        data.writeInt(payload.size)
        data.write(payload)
        data.flush()
    }

    /**
     * Reads exactly one length-prefixed frame without closing the caller's stream.
     * Invalid lengths are rejected before allocating the payload buffer.
     */
    fun readFrame(input: InputStream): DockPacketDecodeResult {
        val data = DataInputStream(input)
        val length = try {
            data.readInt()
        } catch (_: IOException) {
            return DockPacketDecodeResult.Rejected(DockPacketDecodeFailure.TRUNCATED_PAYLOAD)
        }
        if (length <= 0) {
            return DockPacketDecodeResult.Rejected(DockPacketDecodeFailure.INVALID_FRAME_LENGTH)
        }
        if (length > MAX_PAYLOAD_BYTES) {
            return DockPacketDecodeResult.Rejected(DockPacketDecodeFailure.PAYLOAD_TOO_LARGE)
        }
        val payload = ByteArray(length)
        try {
            data.readFully(payload)
        } catch (_: IOException) {
            return DockPacketDecodeResult.Rejected(DockPacketDecodeFailure.TRUNCATED_PAYLOAD)
        }
        return decode(payload)
    }

    fun decode(payload: ByteArray): DockPacketDecodeResult {
        if (payload.isEmpty()) {
            return DockPacketDecodeResult.Rejected(DockPacketDecodeFailure.EMPTY_PAYLOAD)
        }
        if (payload.size > MAX_PAYLOAD_BYTES) {
            return DockPacketDecodeResult.Rejected(DockPacketDecodeFailure.PAYLOAD_TOO_LARGE)
        }

        try {
            DataInputStream(ByteArrayInputStream(payload)).use { data ->
                if (data.readInt() != MAGIC) {
                    return DockPacketDecodeResult.Rejected(DockPacketDecodeFailure.INVALID_MAGIC)
                }

                val version = data.readInt()
                if (version != DockTrackingPacket.CURRENT_PROTOCOL_VERSION) {
                    return DockPacketDecodeResult.Rejected(DockPacketDecodeFailure.UNSUPPORTED_VERSION)
                }

                val sessionLength = data.readUnsignedByte()
                if (sessionLength !in MIN_SESSION_ID_LENGTH..MAX_SESSION_ID_LENGTH) {
                    return DockPacketDecodeResult.Rejected(
                        DockPacketDecodeFailure.INVALID_SESSION_LENGTH
                    )
                }
                val sessionBytes = ByteArray(sessionLength)
                data.readFully(sessionBytes)
                val sessionId = String(sessionBytes, StandardCharsets.US_ASCII)

                val sequence = data.readLong()
                val captureTimestampNanos = data.readLong()
                val width = data.readInt()
                val height = data.readInt()
                val rotation = data.readInt()

                val mirrored = when (data.readUnsignedByte()) {
                    MIRRORED_FALSE -> false
                    MIRRORED_TRUE -> true
                    else -> return DockPacketDecodeResult.Rejected(
                        DockPacketDecodeFailure.INVALID_MIRROR_FLAG
                    )
                }

                val handCount = data.readUnsignedByte()
                if (handCount > DockTrackingPacket.MAX_HANDS) {
                    return DockPacketDecodeResult.Rejected(
                        DockPacketDecodeFailure.INVALID_HAND_COUNT
                    )
                }

                val hands = ArrayList<DockHandSample>(handCount)
                repeat(handCount) {
                    val trackId = data.readLong()
                    val side = DockHandSide.fromWireCode(data.readUnsignedByte())
                        ?: return DockPacketDecodeResult.Rejected(
                            DockPacketDecodeFailure.INVALID_HAND_SIDE
                        )
                    val confidence = data.readFloat()
                    val landmarks = ArrayList<DockLandmark>(DockTrackingPacket.LANDMARK_COUNT)
                    repeat(DockTrackingPacket.LANDMARK_COUNT) {
                        landmarks.add(
                            DockLandmark(
                                x = data.readFloat(),
                                y = data.readFloat(),
                                z = data.readFloat()
                            )
                        )
                    }
                    hands.add(
                        DockHandSample(
                            trackId = trackId,
                            side = side,
                            sideConfidence = confidence,
                            landmarks = landmarks
                        )
                    )
                }

                if (data.available() != 0) {
                    return DockPacketDecodeResult.Rejected(DockPacketDecodeFailure.TRAILING_DATA)
                }

                val packet = DockTrackingPacket(
                    protocolVersion = version,
                    sessionId = sessionId,
                    sequence = sequence,
                    captureTimestampNanos = captureTimestampNanos,
                    frameWidthPx = width,
                    frameHeightPx = height,
                    sensorRotationDegrees = rotation,
                    mirrored = mirrored,
                    hands = hands
                )
                val validationError = DockTrackingPacketValidator.validate(packet)
                if (validationError != null) {
                    return DockPacketDecodeResult.Rejected(
                        DockPacketDecodeFailure.INVALID_PACKET,
                        validationError
                    )
                }
                return DockPacketDecodeResult.Success(packet)
            }
        } catch (_: EOFException) {
            return DockPacketDecodeResult.Rejected(DockPacketDecodeFailure.TRUNCATED_PAYLOAD)
        } catch (_: IOException) {
            return DockPacketDecodeResult.Rejected(DockPacketDecodeFailure.TRUNCATED_PAYLOAD)
        } catch (_: RuntimeException) {
            return DockPacketDecodeResult.Rejected(DockPacketDecodeFailure.INVALID_PACKET)
        }
    }

    private fun DockHandSide.toWireCode(): Int = when (this) {
        DockHandSide.LEFT -> 0
        DockHandSide.RIGHT -> 1
        DockHandSide.UNKNOWN -> 2
    }

}

/**
 * One-slot receiver buffer: a newly accepted packet replaces the previous one immediately.
 *
 * receivedAtElapsedRealtimeNanos is sampled by the receiving phone. It is the only clock used
 * for freshness; sender capture timestamps are used only for ordering within the sender session.
 */
class DockLatestPacketBuffer(
    expectedSessionId: String,
    maxAgeMs: Long = DEFAULT_MAX_AGE_MS
) {
    private data class ReceivedPacket(
        val packet: DockTrackingPacket,
        val receivedAtElapsedRealtimeNanos: Long
    )

    private val sequenceGuard = DockTrackingSequenceGuard(expectedSessionId)
    private val maxAgeNanos: Long

    @Volatile
    private var latest: ReceivedPacket? = null

    init {
        require(maxAgeMs in 1L..MAX_CONFIGURABLE_MAX_AGE_MS) {
            "Dock Station packet max age must be between 1 and $MAX_CONFIGURABLE_MAX_AGE_MS ms."
        }
        maxAgeNanos = maxAgeMs * NANOS_PER_MILLISECOND
    }

    /** Returns null when accepted, otherwise the precise rejection reason. */
    @Synchronized
    fun offer(
        packet: DockTrackingPacket,
        receivedAtElapsedRealtimeNanos: Long
    ): DockPacketRejection? {
        if (receivedAtElapsedRealtimeNanos <= 0L) {
            return DockPacketRejection.INVALID_RECEIVE_TIMESTAMP
        }
        val rejection = sequenceGuard.accept(packet)
        if (rejection != null) return rejection

        // Latest-state semantics: replace, never enqueue behind an older frame.
        latest = ReceivedPacket(packet, receivedAtElapsedRealtimeNanos)
        return null
    }

    /**
     * Returns the latest packet while fresh. Repeated reads are allowed until it expires;
     * expiry clears it so stale input cannot keep a pinch/grip active.
     */
    @Synchronized
    fun latestFresh(nowElapsedRealtimeNanos: Long): DockTrackingPacket? {
        if (nowElapsedRealtimeNanos <= 0L) return null
        val received = latest ?: return null
        if (nowElapsedRealtimeNanos < received.receivedAtElapsedRealtimeNanos) return null

        val ageNanos = nowElapsedRealtimeNanos - received.receivedAtElapsedRealtimeNanos
        if (ageNanos > maxAgeNanos) {
            latest = null
            return null
        }
        return received.packet
    }

    /** Clears the visible sample but retains the sequence watermark for this paired session. */
    @Synchronized
    fun clearLatest() {
        latest = null
    }

    private companion object {
        const val DEFAULT_MAX_AGE_MS = 250L
        const val MAX_CONFIGURABLE_MAX_AGE_MS = 5_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
