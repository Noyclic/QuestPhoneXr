package com.noyclic.endvr.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

internal class MjpegStreamClient(
    private val url: URL,
    private val listener: Listener
) {
    interface Listener {
        fun onState(message: String)
        fun onFrame(bitmap: Bitmap, decodeMs: Double)
    }

    private val running = AtomicBoolean(false)

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    @Volatile
    private var activeInput: InputStream? = null

    @Volatile
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ runLoop() }, "EndVR-MJPEG-client").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        try {
            activeInput?.close()
        } catch (_: Exception) {
        }
        activeConnection?.disconnect()
        worker?.interrupt()
    }

    private fun runLoop() {
        var retrySeconds = 1L
        while (running.get()) {
            var connection: HttpURLConnection? = null
            try {
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 15000
                    useCaches = false
                    setRequestProperty("Accept", "multipart/x-mixed-replace")
                }
                activeConnection = connection
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("PC host returned HTTP " + responseCode)
                }
                val boundary = MjpegPartReader.boundaryFromContentType(connection.contentType)
                val input = BufferedInputStream(connection.inputStream)
                activeInput = input
                listener.onState("Connected • waiting for video frames")
                val reader = MjpegPartReader(input, boundary)
                while (running.get()) {
                    val bytes = reader.readFrame() ?: throw EOFException("PC host ended the stream")
                    val decodeStarted = SystemClock.elapsedRealtimeNanos()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IllegalStateException("Received frame is not a supported image")
                    val decodeMs = (SystemClock.elapsedRealtimeNanos() - decodeStarted) / 1_000_000.0
                    listener.onFrame(bitmap, decodeMs)
                    retrySeconds = 1L
                }
            } catch (failure: Exception) {
                if (running.get()) {
                    val reason = failure.message?.take(120) ?: failure.javaClass.simpleName
                    listener.onState("Connection lost: " + reason + " • retrying in " + retrySeconds + "s")
                    try {
                        Thread.sleep(retrySeconds * 1000L)
                    } catch (_: InterruptedException) {
                        if (!running.get()) break
                    }
                    retrySeconds = (retrySeconds * 2L).coerceAtMost(8L)
                }
            } finally {
                try {
                    activeInput?.close()
                } catch (_: Exception) {
                }
                activeInput = null
                connection?.disconnect()
                if (activeConnection === connection) activeConnection = null
            }
        }
        listener.onState("Stream stopped")
    }
}

internal class MjpegPartReader(
    private val input: InputStream,
    boundary: String,
    private val maxFrameBytes: Int = 8 * 1024 * 1024
) {
    private val boundaryLine = "--" + boundary.removePrefix("--")

    init {
        require(boundary.isNotBlank()) { "Multipart boundary is empty" }
        require(maxFrameBytes > 0) { "Maximum frame size must be positive" }
    }

    fun readFrame(): ByteArray? {
        var marker = readAsciiLine() ?: return null
        while (marker.isEmpty()) marker = readAsciiLine() ?: return null
        if (marker == boundaryLine + "--") return null
        if (marker != boundaryLine) throw IllegalStateException("Unexpected MJPEG multipart boundary")

        val headers = HashMap<String, String>()
        var headerBytes = 0
        var headerCount = 0
        while (true) {
            val line = readAsciiLine() ?: throw EOFException("Truncated MJPEG part headers")
            if (line.isEmpty()) break
            headerBytes += line.length
            headerCount += 1
            if (headerBytes > 8192 || headerCount > 32) {
                throw IllegalStateException("MJPEG part headers exceed the safety limit")
            }
            val separator = line.indexOf(':')
            if (separator <= 0) throw IllegalStateException("Malformed MJPEG part header")
            headers[line.substring(0, separator).trim().lowercase()] =
                line.substring(separator + 1).trim()
        }

        val length = headers["content-length"]?.toIntOrNull()
            ?: throw IllegalStateException("MJPEG part has no valid Content-Length")
        if (length <= 0 || length > maxFrameBytes) {
            throw IllegalStateException("MJPEG frame size is outside the allowed range")
        }
        val frame = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(frame, offset, length - offset)
            if (count < 0) throw EOFException("Truncated MJPEG image data")
            if (count == 0) continue
            offset += count
        }

        val separator = readAsciiLine() ?: throw EOFException("MJPEG frame is missing its separator")
        if (separator.isNotEmpty()) throw IllegalStateException("Malformed MJPEG frame separator")
        return frame
    }

    private fun readAsciiLine(): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) {
                if (bytes.size() == 0) return null
                throw EOFException("Truncated MJPEG header line")
            }
            if (next == 10) break
            if (next != 13) {
                if (bytes.size() >= 4096) throw IllegalStateException("MJPEG header line is too long")
                bytes.write(next)
            }
        }
        return String(bytes.toByteArray(), Charsets.US_ASCII)
    }

    companion object {
        fun boundaryFromContentType(contentType: String?): String {
            val value = contentType ?: throw IllegalStateException("PC host did not send a Content-Type")
            if (!value.lowercase().startsWith("multipart/x-mixed-replace")) {
                throw IllegalStateException("PC host is not serving an MJPEG stream")
            }
            val parameter = value.split(';').map { it.trim() }
                .firstOrNull { it.lowercase().startsWith("boundary=") }
                ?: throw IllegalStateException("MJPEG response has no multipart boundary")
            val boundary = parameter.substringAfter('=').trim().removeSurrounding("\"")
            if (boundary.isBlank() || boundary.length > 70 ||
                !boundary.all { it.isLetterOrDigit() || it in "-_." }) {
                throw IllegalStateException("MJPEG response has an invalid multipart boundary")
            }
            return boundary.removePrefix("--")
        }
    }
}
