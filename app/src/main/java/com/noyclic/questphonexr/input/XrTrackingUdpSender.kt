package com.noyclic.questphonexr.input

import com.noyclic.questphonexr.tracking.HandPoint
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.tracking.HandSide
import com.noyclic.questphonexr.tracking.TrackedHand
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends phone head orientation and MediaPipe hand landmarks to a companion PC receiver.
 *
 * Packets are latest-state UDP snapshots: dropped packets are not retransmitted. The receiver
 * must discard stale data. This is tracking telemetry, not video streaming or an OpenXR driver.
 */
class XrTrackingUdpSender(
    private val onStatus: (String) -> Unit,
    private val port: Int = DEFAULT_PORT
) : Closeable {
    @Volatile private var latestFrame: HandTrackingFrame? = null
    @Volatile private var latestHeadRotation: FloatArray? = null
    @Volatile private var lastPublishNanos = 0L
    @Volatile private var targetHost: String? = null
    @Volatile private var targetAddress: InetAddress? = null
    @Volatile private var running = false
    private val sequence = AtomicLong(0L)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "QuestPhoneXR-TrackingUDP").apply { isDaemon = true }
    }
    private var task: ScheduledFuture<*>? = null
    private var socket: DatagramSocket? = null

    val isRunning: Boolean get() = running

    fun publish(frame: HandTrackingFrame) {
        latestFrame = frame.snapshot()
        lastPublishNanos = System.nanoTime()
    }

    fun updateHeadRotation(rotationMatrix: FloatArray) {
        if (rotationMatrix.size < ROTATION_MATRIX_SIZE) return
        latestHeadRotation = rotationMatrix.copyOfRange(0, ROTATION_MATRIX_SIZE)
    }

    @Synchronized
    fun start(host: String) {
        val normalized = host.trim()
        require(normalized.isNotEmpty()) { "Укажи IP-адрес компьютера." }
        require(normalized.length <= MAX_HOST_LENGTH && normalized.none { it.isWhitespace() }) {
            "Адрес компьютера содержит недопустимые символы."
        }
        require(port in 1..65535) { "UDP-порт вне допустимого диапазона." }

        stopLocked()
        targetHost = normalized
        running = true
        task = executor.scheduleAtFixedRate(
            ::sendLatest,
            0L,
            SEND_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
        onStatus("ПК: отправка XR-телеметрии → $normalized:$port")
    }

    @Synchronized
    fun stop() {
        stopLocked()
        onStatus("ПК: передача телеметрии остановлена.")
    }

    private fun sendLatest() {
        if (!running) return
        try {
            val publishedAt = lastPublishNanos
            if (publishedAt == 0L || System.nanoTime() - publishedAt > FRAME_STALE_NANOS) {
                return
            }
            val frame = latestFrame ?: return
            val host = targetHost ?: return
            val address = targetAddress ?: InetAddress.getByName(host).also { targetAddress = it }
            val payload = XrTrackingPacketCodec.encode(
                frame,
                latestHeadRotation,
                sequence.incrementAndGet()
            ).toByteArray(StandardCharsets.UTF_8)
            if (payload.size > MAX_DATAGRAM_BYTES) {
                throw IllegalStateException("XR-пакет превысил безопасный размер UDP.")
            }
            val datagram = DatagramPacket(payload, payload.size, address, port)
            val activeSocket = socket ?: DatagramSocket().also { socket = it }
            activeSocket.send(datagram)
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            synchronized(this) {
                stopLocked()
            }
            onStatus("ПК: ошибка UDP · $message")
        }
    }

    private fun stopLocked() {
        running = false
        task?.cancel(false)
        task = null
        socket?.close()
        socket = null
        targetHost = null
        targetAddress = null
    }

    override fun close() {
        synchronized(this) { stopLocked() }
        executor.shutdownNow()
    }

    private fun HandTrackingFrame.snapshot(): HandTrackingFrame = copy(
        hands = hands.map { hand ->
            hand.copy(
                points = hand.points.toList(),
                worldPoints = hand.worldPoints.toList()
            )
        }
    )

    companion object {
        const val DEFAULT_PORT = 39471
        private const val SEND_INTERVAL_MS = 33L
        private const val FRAME_STALE_NANOS = 750_000_000L
        private const val MAX_DATAGRAM_BYTES = 8_192
        private const val MAX_HOST_LENGTH = 253
        private const val ROTATION_MATRIX_SIZE = 9
    }
}

/** Stable, dependency-free JSON contract shared with the companion PC receiver. */
internal object XrTrackingPacketCodec {
    fun encode(
        frame: HandTrackingFrame,
        headRotationMatrix: FloatArray?,
        sequence: Long
    ): String = buildString {
        append("{\"protocol\":\"QuestPhoneXR\",\"type\":\"tracking\",\"schemaVersion\":1")
        append(",\"sequence\":").append(sequence.coerceAtLeast(0L))
        append(",\"timestampMs\":").append(frame.timestampMs.coerceAtLeast(0L))
        append(",\"head\":{\"tracked\":")
        val headTracked = headRotationMatrix != null && headRotationMatrix.size >= 9
        append(headTracked)
        append(",\"rotationMatrix\":")
        if (!headTracked) {
            append("null")
        } else {
            append('[')
            headRotationMatrix!!.take(9).forEachIndexed { index, value ->
                if (index > 0) append(',')
                append(value.safeNumber(-1f, 1f))
            }
            append(']')
        }
        append("},\"hands\":[")
        frame.hands.take(MAX_HANDS).forEachIndexed { index, hand ->
            if (index > 0) append(',')
            appendHand(hand)
        }
        append("]}")
    }

    private fun StringBuilder.appendHand(hand: TrackedHand) {
        append("{\"side\":\"").append(hand.side.name).append('"')
        append(",\"tracked\":true")
        append(",\"pinchStrength\":").append(hand.pinchStrength.safeNumber(0f, 1f))
        append(",\"pinchPressed\":").append(hand.isPinching)
        append(",\"imageLandmarks\":")
        appendPoints(hand.points, IMAGE_COORD_LIMIT)
        append(",\"worldLandmarks\":")
        appendPoints(hand.worldPoints, WORLD_COORD_LIMIT)
        append('}')
    }

    private fun StringBuilder.appendPoints(points: List<HandPoint>, limit: Float) {
        append('[')
        points.take(LANDMARK_COUNT).forEachIndexed { index, point ->
            if (index > 0) append(',')
            append("{\"x\":").append(point.x.safeNumber(-limit, limit))
            append(",\"y\":").append(point.y.safeNumber(-limit, limit))
            append(",\"z\":").append(point.z.safeNumber(-limit, limit)).append('}')
        }
        append(']')
    }

    private fun Float.safeNumber(minimum: Float, maximum: Float): String =
        if (isFinite()) coerceIn(minimum, maximum).toString() else "0.0"

    private const val MAX_HANDS = 2
    private const val LANDMARK_COUNT = 21
    private const val IMAGE_COORD_LIMIT = 2f
    private const val WORLD_COORD_LIMIT = 2f
}
