package com.noyclic.questphonexr.tracking.dock

/**
 * Versioned, camera-local tracking contract for a paired secondary Android phone.
 *
 * This is deliberately a data contract only: it does not start a socket, camera, or
 * foreground service. The receiver must authenticate/pair the station before accepting
 * packets and must calibrate the two camera coordinate systems before fusing positions.
 */
enum class DockHandSide {
    LEFT,
    RIGHT,
    UNKNOWN
}

/** Normalized landmark in the station camera's upright image coordinate frame. */
data class DockLandmark(
    val x: Float,
    val y: Float,
    val z: Float
)

/** One MediaPipe-compatible hand observation from the secondary phone. */
data class DockHandSample(
    /** Stable only within one station session; never use as a global hand ID. */
    val trackId: Long,
    val side: DockHandSide,
    val sideConfidence: Float,
    /** Exactly 21 points, in MediaPipe Hand Landmarker landmark order. */
    val landmarks: List<DockLandmark>
)

/**
 * Latest-state packet sent from Dock Station to the headset phone.
 *
 * captureTimestampNanos uses the sender's monotonic clock. It is only comparable with
 * timestamps from the same session/device; the receiver must estimate clock offset before
 * comparing it with the headset's clock. Frame dimensions describe the upright image.
 */
data class DockTrackingPacket(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val sessionId: String,
    val sequence: Long,
    val captureTimestampNanos: Long,
    val frameWidthPx: Int,
    val frameHeightPx: Int,
    val sensorRotationDegrees: Int,
    val mirrored: Boolean,
    val hands: List<DockHandSample>
) {
    companion object {
        const val CURRENT_PROTOCOL_VERSION = 1
        const val LANDMARK_COUNT = 21
        const val MAX_HANDS = 2
        const val MAX_FRAME_DIMENSION_PX = 1_920
        const val MAX_FRAME_AREA_PX = 1_920 * 1_080
    }
}

enum class DockPacketRejection {
    UNSUPPORTED_VERSION,
    INVALID_SESSION_ID,
    INVALID_SEQUENCE,
    INVALID_CAPTURE_TIMESTAMP,
    INVALID_FRAME_SIZE,
    INVALID_SENSOR_ROTATION,
    TOO_MANY_HANDS,
    INVALID_TRACK_ID,
    DUPLICATE_TRACK_ID,
    INVALID_HANDEDNESS_CONFIDENCE,
    INVALID_LANDMARK_COUNT,
    INVALID_LANDMARK,
    WRONG_SESSION,
    STALE_SEQUENCE,
    NON_MONOTONIC_TIMESTAMP
}

/** Cheap structural validation performed before a packet reaches tracking fusion. */
object DockTrackingPacketValidator {
    fun validate(packet: DockTrackingPacket): DockPacketRejection? {
        if (packet.protocolVersion != DockTrackingPacket.CURRENT_PROTOCOL_VERSION) {
            return DockPacketRejection.UNSUPPORTED_VERSION
        }
        if (!isValidSessionId(packet.sessionId)) return DockPacketRejection.INVALID_SESSION_ID
        if (packet.sequence < 0L) return DockPacketRejection.INVALID_SEQUENCE
        if (packet.captureTimestampNanos <= 0L) {
            return DockPacketRejection.INVALID_CAPTURE_TIMESTAMP
        }
        if (packet.frameWidthPx !in 1..DockTrackingPacket.MAX_FRAME_DIMENSION_PX ||
            packet.frameHeightPx !in 1..DockTrackingPacket.MAX_FRAME_DIMENSION_PX ||
            packet.frameWidthPx.toLong() * packet.frameHeightPx.toLong() >
                DockTrackingPacket.MAX_FRAME_AREA_PX.toLong()
        ) return DockPacketRejection.INVALID_FRAME_SIZE

        if (packet.sensorRotationDegrees !in VALID_ROTATIONS) {
            return DockPacketRejection.INVALID_SENSOR_ROTATION
        }
        if (packet.hands.size > DockTrackingPacket.MAX_HANDS) {
            return DockPacketRejection.TOO_MANY_HANDS
        }

        for (index in packet.hands.indices) {
            val hand = packet.hands[index]
            if (hand.trackId <= 0L) return DockPacketRejection.INVALID_TRACK_ID
            for (previousIndex in 0 until index) {
                if (packet.hands[previousIndex].trackId == hand.trackId) {
                    return DockPacketRejection.DUPLICATE_TRACK_ID
                }
            }
            if (!hand.sideConfidence.isFinite() || hand.sideConfidence !in 0f..1f) {
                return DockPacketRejection.INVALID_HANDEDNESS_CONFIDENCE
            }
            if (hand.landmarks.size != DockTrackingPacket.LANDMARK_COUNT) {
                return DockPacketRejection.INVALID_LANDMARK_COUNT
            }
            for (landmark in hand.landmarks) {
                if (!landmark.x.isFinite() || !landmark.y.isFinite() || !landmark.z.isFinite() ||
                    landmark.x !in MIN_NORMALIZED_COORDINATE..MAX_NORMALIZED_COORDINATE ||
                    landmark.y !in MIN_NORMALIZED_COORDINATE..MAX_NORMALIZED_COORDINATE ||
                    landmark.z !in MIN_NORMALIZED_DEPTH..MAX_NORMALIZED_DEPTH
                ) return DockPacketRejection.INVALID_LANDMARK
            }
        }
        return null
    }

    private fun isValidSessionId(value: String): Boolean =
        value.length in MIN_SESSION_ID_LENGTH..MAX_SESSION_ID_LENGTH &&
            value.all {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
            }

    private val VALID_ROTATIONS = setOf(0, 90, 180, 270)

    // Slightly out-of-frame landmarks are retained for edge-of-frame recovery. Large values
    // are rejected as corrupt input instead of being allowed to poison the fused pose.
    private const val MIN_NORMALIZED_COORDINATE = -0.5f
    private const val MAX_NORMALIZED_COORDINATE = 1.5f
    private const val MIN_NORMALIZED_DEPTH = -2.0f
    private const val MAX_NORMALIZED_DEPTH = 2.0f
    private const val MIN_SESSION_ID_LENGTH = 8
    private const val MAX_SESSION_ID_LENGTH = 64
}

/**
 * Enforces the ordering contract for one already-paired station session.
 *
 * A new guard should be created for each successful pairing/reconnection. Packets from
 * another session are rejected; a reconnect must not silently reset sequence numbers.
 */
class DockTrackingSequenceGuard(
    private val expectedSessionId: String
) {
    private var lastSequence = -1L
    private var lastCaptureTimestampNanos = -1L

    init {
        require(expectedSessionId.length in 8..64) { "Invalid Dock Station session ID." }
    }

    @Synchronized
    fun accept(packet: DockTrackingPacket): DockPacketRejection? {
        val validationError = DockTrackingPacketValidator.validate(packet)
        if (validationError != null) return validationError
        if (packet.sessionId != expectedSessionId) return DockPacketRejection.WRONG_SESSION
        if (packet.sequence <= lastSequence) return DockPacketRejection.STALE_SEQUENCE
        if (packet.captureTimestampNanos <= lastCaptureTimestampNanos) {
            return DockPacketRejection.NON_MONOTONIC_TIMESTAMP
        }

        lastSequence = packet.sequence
        lastCaptureTimestampNanos = packet.captureTimestampNanos
        return null
    }

    @Synchronized
    fun reset() {
        lastSequence = -1L
        lastCaptureTimestampNanos = -1L
    }
}
