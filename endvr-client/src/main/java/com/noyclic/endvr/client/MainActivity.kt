package com.noyclic.endvr.client

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.net.URL
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var preview: StereoPreviewView
    private lateinit var connectionPanel: LinearLayout
    private lateinit var statusLabel: TextView
    private lateinit var hostInput: EditText
    private lateinit var overlay: LinearLayout

    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameLock = Any()
    private var latestFrame: PendingFrame? = null
    private var frameDeliveryPosted = false
    private var streamClient: MjpegStreamClient? = null
    private var fpsWindowStartMs = 0L
    private var framesInWindow = 0
    private var lastDecodeMs = 0.0

    private data class PendingFrame(val bitmap: Bitmap, val decodeMs: Double)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildInterface()
    }

    private fun buildInterface() {
        root = FrameLayout(this).apply { setBackgroundColor(0xFF080B10.toInt()) }
        preview = StereoPreviewView(this).apply {
            visibility = View.GONE
            setOnClickListener { overlay.visibility = if (overlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        root.addView(preview, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        connectionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(0xFF151D29.toInt())
        }
        val title = TextView(this).apply {
            text = "EndVR Client"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        val description = TextView(this).apply {
            text = "Connect to the PC host on the same trusted Wi-Fi. This first build displays a generated left/right-eye test pattern, not a game capture."
            textSize = 14f
            setTextColor(0xFFD0D9E5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(16))
        }
        hostInput = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText("http://192.168.1.2:8765/stream.mjpg")
            hint = "http://PC-LAN-IP:8765/stream.mjpg"
            textSize = 16f
            setSelectAllOnFocus(true)
        }
        val connectButton = Button(this).apply {
            text = "Connect"
            setOnClickListener { connectToHost() }
        }
        val help = TextView(this).apply {
            text = "On the PC run: endvr-host --bind 0.0.0.0 --width 640 --height 360 --fps 30\nThen replace 192.168.1.2 with the PC's local Wi-Fi IP address."
            textSize = 13f
            setTextColor(0xFFB7C4D4.toInt())
            setPadding(0, dp(14), 0, 0)
        }
        connectionPanel.addView(title, fullWidth())
        connectionPanel.addView(description, fullWidth())
        connectionPanel.addView(hostInput, fullWidth())
        connectionPanel.addView(connectButton, fullWidth())
        connectionPanel.addView(help, fullWidth())
        root.addView(connectionPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(8), dp(7))
            setBackgroundColor(0xB0000000.toInt())
            visibility = View.GONE
        }
        statusLabel = TextView(this).apply {
            text = "Connecting to PC host…"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val stopButton = Button(this).apply {
            text = "Stop"
            setOnClickListener { stopStreaming(showPanel = true) }
        }
        overlay.addView(statusLabel, LinearLayout.LayoutParams(0, dp(44), 1f))
        overlay.addView(stopButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)
        ))
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))

        setContentView(root)
    }

    private fun connectToHost() {
        val address = hostInput.text.toString().trim()
        val parsed = try {
            URL(address)
        } catch (_: Exception) {
            statusLabel.text = "Enter a valid host URL."
            return
        }
        if (parsed.protocol != "http" || parsed.host.isBlank()) {
            statusLabel.text = "Use an HTTP URL on your trusted local network."
            return
        }
        stopStreaming(showPanel = false)
        connectionPanel.visibility = View.GONE
        preview.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE
        preview.clearFrame()
        statusLabel.text = "Connecting to " + parsed.host + "…"
        fpsWindowStartMs = 0L
        framesInWindow = 0
        setImmersive(true)

        streamClient = MjpegStreamClient(parsed, object : MjpegStreamClient.Listener {
            override fun onState(message: String) {
                runOnUiThread {
                    if (streamClient != null) statusLabel.text = message
                }
            }

            override fun onFrame(bitmap: Bitmap, decodeMs: Double) {
                synchronized(frameLock) {
                    latestFrame = PendingFrame(bitmap, decodeMs)
                    if (!frameDeliveryPosted) {
                        frameDeliveryPosted = true
                        mainHandler.post { deliverLatestFrame() }
                    }
                }
            }
        }).also { it.start() }
    }

    private fun deliverLatestFrame() {
        val pending = synchronized(frameLock) {
            val value = latestFrame
            latestFrame = null
            frameDeliveryPosted = false
            value
        }
        if (pending != null && streamClient != null) {
            preview.setFrame(pending.bitmap)
            lastDecodeMs = pending.decodeMs
            framesInWindow += 1
            val now = SystemClock.elapsedRealtime()
            if (fpsWindowStartMs == 0L) fpsWindowStartMs = now
            val elapsed = now - fpsWindowStartMs
            if (elapsed >= 1000L) {
                val fps = framesInWindow * 1000.0 / elapsed.toDouble()
                statusLabel.text = String.format(
                    Locale.US, "Test stream  •  %.1f displayed FPS  •  decode %.1f ms",
                    fps, lastDecodeMs
                )
                framesInWindow = 0
                fpsWindowStartMs = now
            }
        }
        var scheduleAgain = false
        synchronized(frameLock) {
            if (latestFrame != null && !frameDeliveryPosted) {
                frameDeliveryPosted = true
                scheduleAgain = true
            }
        }
        if (scheduleAgain) mainHandler.post { deliverLatestFrame() }
    }

    private fun stopStreaming(showPanel: Boolean) {
        val oldClient = streamClient
        streamClient = null
        oldClient?.stop()
        synchronized(frameLock) {
            latestFrame = null
            frameDeliveryPosted = false
        }
        preview.clearFrame()
        preview.visibility = if (showPanel) View.GONE else preview.visibility
        overlay.visibility = View.GONE
        if (showPanel) {
            connectionPanel.visibility = View.VISIBLE
            setImmersive(false)
        }
    }

    private fun setImmersive(enabled: Boolean) {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (enabled) {
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Deprecated by Android in favor of the back dispatcher")
    override fun onBackPressed() {
        if (streamClient != null) {
            stopStreaming(showPanel = true)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        stopStreaming(showPanel = false)
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
