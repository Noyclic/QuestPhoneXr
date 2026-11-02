package com.noyclic.questphonexr.input

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
 * Sends the latest virtual-controller snapshot to a paired Quest over the local Wi-Fi network.
 * UDP is intentionally used for low-latency state snapshots: a lost packet is replaced by the
 * next one, rather than delaying newer input behind an old reliable packet.
 */
class ControllerFrameUdpSender(
    private val onStatus: (String) -> Unit,
    private val port: Int = DEFAULT_PORT
) : Closeable {
    @Volatile private var latestFrame: VirtualControllerFrame? = null
    @Volatile private var targetHost: String? = null
    @Volatile private var running = false
    private val sequence = AtomicLong(0L)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "QuestPhoneXR-UDP").apply { isDaemon = true }
    }
    private var task: ScheduledFuture<*>? = null
    private var socket: DatagramSocket? = null

    val isRunning: Boolean get() = running

    fun publish(frame: VirtualControllerFrame) {
        latestFrame = frame
    }

    @Synchronized
    fun start(host: String) {
        val normalized = host.trim()
        require(normalized.isNotEmpty()) { "Укажи IP-адрес Quest." }
        stopLocked()
        targetHost = normalized
        running = true
        task = executor.scheduleAtFixedRate(::sendLatest, 0L, SEND_INTERVAL_MS, TimeUnit.MILLISECONDS)
        onStatus("Подключение к $normalized:$port…")
    }

    @Synchronized
    fun stop() {
        stopLocked()
        onStatus("Передача контроллеров остановлена.")
    }

    private fun sendLatest() {
        if (!running) return
        try {
            val host = targetHost ?: return
            val address = InetAddress.getByName(host)
            val frame = latestFrame ?: return
            val payload = encode(frame, sequence.incrementAndGet()).toByteArray(StandardCharsets.UTF_8)
            val datagram = DatagramPacket(payload, payload.size, address, port)
            val activeSocket = socket ?: DatagramSocket().also { socket = it }
            activeSocket.send(datagram)
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            synchronized(this) { stopLocked() }
            onStatus("UDP: $message")
        }
    }

    private fun stopLocked() {
        running = false
        task?.cancel(false)
        task = null
        socket?.close()
        socket = null
        targetHost = null
    }

    private fun encode(frame: VirtualControllerFrame, seq: Long): String = buildString {
        append("{\"schemaVersion\":1,\"sequence\":").append(seq)
        append(",\"timestampMs\":").append(frame.timestampMs)
        append(",\"left\":")
        appendController(frame.left)
        append(",\"right\":")
        appendController(frame.right)
        append('}')
    }

    private fun StringBuilder.appendController(state: VirtualControllerState) {
        append("{\"tracked\":").append(state.isTracked)
        append(",\"trigger\":").append(state.trigger.safeNumber())
        append(",\"grip\":").append(state.grip.safeNumber())
        append(",\"pinchPressed\":").append(state.pinchPressed)
        append(",\"stickX\":").append(state.stickX.safeNumber())
        append(",\"stickY\":").append(state.stickY.safeNumber())
        append(",\"keys\":[")
        state.pressedKeyCodes.sorted().forEachIndexed { index, code ->
            if (index > 0) append(',')
            append(code)
        }
        append("],\"pose\":")
        val pose = state.pose
        if (pose == null) {
            append("null")
        } else {
            append("{\"imageX\":").append(pose.imageX.safeNumber())
            append(",\"imageY\":").append(pose.imageY.safeNumber())
            append(",\"relativeDepth\":").append(pose.relativeDepth.safeNumber())
            append(",\"palmForward\":")
            appendVector(pose.palmForward)
            append(",\"palmNormal\":")
            appendVector(pose.palmNormal)
            append('}')
        }
        append('}')
    }

    private fun StringBuilder.appendVector(vector: Vector3f) {
        append("{\"x\":").append(vector.x.safeNumber())
        append(",\"y\":").append(vector.y.safeNumber())
        append(",\"z\":").append(vector.z.safeNumber()).append('}')
    }

    private fun Float.safeNumber(): String = if (isFinite()) toString() else "0.0"

    override fun close() {
        synchronized(this) { stopLocked() }
        executor.shutdownNow()
    }

    companion object {
        const val DEFAULT_PORT = 39470
        private const val SEND_INTERVAL_MS = 33L
    }
}
