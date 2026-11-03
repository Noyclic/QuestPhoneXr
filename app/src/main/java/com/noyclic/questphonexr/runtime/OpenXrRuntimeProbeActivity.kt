package com.noyclic.questphonexr.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * Runs a persistent OpenXR GLES session in a dedicated process.
 * Each GLSurfaceView callback submits at most one XR frame; the native session
 * and swapchain remain alive between callbacks. The separate process contains
 * native runtime failures while the persistent session is still being validated.
 */
class OpenXrRuntimeProbeActivity : ComponentActivity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var statusView: TextView
    private lateinit var runButton: Button
    private lateinit var copyReportButton: Button
    @Volatile private var lastReportText: String = ""

    @Volatile private var glReady = false
    @Volatile private var sessionRequested = false
    @Volatile private var nativeSessionCreated = false
    @Volatile private var lastStatusUpdateMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(
                    gl: javax.microedition.khronos.opengles.GL10?,
                    config: javax.microedition.khronos.egl.EGLConfig?
                ) {
                    glReady = true
                    // A new EGL context invalidates the old graphics binding. Rebuild
                    // the session on this GL thread instead of reusing stale handles.
                    if (sessionRequested) {
                        if (nativeSessionCreated) {
                            OpenXrPersistentGlesSession.destroy()
                            nativeSessionCreated = false
                        }
                        val report = OpenXrPersistentGlesSession.create(this@OpenXrRuntimeProbeActivity)
                        nativeSessionCreated = report.status == "SESSION_CREATED_WAITING_FOR_READY" ||
                            report.status == "SESSION_ALREADY_CREATED"
                        publishReport(report, stopOnFailure = !nativeSessionCreated)
                    } else {
                        runOnUiThread {
                            if (::statusView.isInitialized) {
                                statusView.text = "GLES 3 готов. Нажми «Запустить постоянную XR-сессию»."
                            }
                            if (::runButton.isInitialized) runButton.isEnabled = true
                            if (::copyReportButton.isInitialized) {
                                copyReportButton.isEnabled = lastReportText.isNotBlank()
                            }
                        }
                    }
                }

                override fun onSurfaceChanged(
                    gl: javax.microedition.khronos.opengles.GL10?,
                    width: Int,
                    height: Int
                ) = Unit

                override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
                    if (!sessionRequested || !nativeSessionCreated) return
                    val report = OpenXrPersistentGlesSession.renderFrame()
                    val now = SystemClock.uptimeMillis()
                    val isSteadyFrame = report.status == "FRAME_SUBMITTED" ||
                        report.status == "FRAME_NOT_RENDERED" ||
                        report.status == "WAITING_FOR_SESSION_READY"
                    if (!isSteadyFrame) {
                        // Stop issuing XR calls after a real stage failure; the session
                        // is torn down on this same GL thread before the error is shown.
                        val cleanup = OpenXrPersistentGlesSession.destroy()
                        nativeSessionCreated = false
                        sessionRequested = false
                        lastStatusUpdateMs = now
                        publishReport(
                            report.copy(message = report.message + "\nCleanup: " + cleanup.message),
                            stopOnFailure = true
                        )
                    } else if (now - lastStatusUpdateMs >= STATUS_UPDATE_INTERVAL_MS) {
                        lastStatusUpdateMs = now
                        publishReport(report, stopOnFailure = false)
                    }
                }
            })
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        statusView = TextView(this).apply {
            text = "Запускаем изолированный GLES-поток…"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(16, 14, 16, 14)
            setBackgroundColor(0xE6101722.toInt())
        }
        runButton = Button(this).apply {
            text = "Запустить постоянную XR-сессию"
            isAllCaps = false
            isEnabled = false
            setOnClickListener {
                if (sessionRequested) stopPersistentSession() else startPersistentSession()
            }
        }
        copyReportButton = Button(this).apply {
            text = "Скопировать XR-отчёт"
            isAllCaps = false
            isEnabled = false
            setOnClickListener {
                val reportText = lastReportText
                if (reportText.isNotBlank()) {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("QuestPhoneXR OpenXR report", reportText)
                    )
                    Toast.makeText(
                        this@OpenXrRuntimeProbeActivity,
                        "XR-отчёт скопирован",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        val closeButton = Button(this).apply {
            text = "Назад в библиотеку"
            isAllCaps = false
            setOnClickListener { finish() }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            setBackgroundColor(0xE6101722.toInt())
            addView(TextView(this@OpenXrRuntimeProbeActivity).apply {
                text = "OPENXR · PERSISTENT GLES SESSION"
                setTextColor(0xFF8FC7FF.toInt())
                textSize = 12f
            })
            addView(TextView(this@OpenXrRuntimeProbeActivity).apply {
                text = "Сессия и swapchain создаются один раз. Каждый GL callback отправляет один стереокадр; пауза и возврат обрабатываются отдельно."
                setTextColor(0xFFD4DCE8.toInt())
                textSize = 12f
                setPadding(0, 6, 0, 8)
            })
            addView(ScrollView(this@OpenXrRuntimeProbeActivity).apply {
                addView(statusView)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
            addView(runButton)
            addView(copyReportButton)
            addView(closeButton)
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(12, 16, 23))
            addView(
                glView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                panel,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            )
        }
        setContentView(root)
    }

    private fun startPersistentSession() {
        if (!glReady || sessionRequested || isFinishing) return
        sessionRequested = true
        runButton.isEnabled = false
        runButton.text = "Создаём XR-сессию…"
        statusView.text = "Инициализация OpenXR выполняется на GLES GL-потоке…"
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glView.queueEvent {
            val report = OpenXrPersistentGlesSession.create(this@OpenXrRuntimeProbeActivity)
            nativeSessionCreated = report.status == "SESSION_CREATED_WAITING_FOR_READY" ||
                report.status == "SESSION_ALREADY_CREATED"
            publishReport(report, stopOnFailure = !nativeSessionCreated)
        }
        glView.requestRender()
    }

    private fun stopPersistentSession() {
        if (!sessionRequested && !nativeSessionCreated) return
        sessionRequested = false
        runButton.isEnabled = false
        runButton.text = "Останавливаем XR-сессию…"
        glView.queueEvent {
            val report = OpenXrPersistentGlesSession.destroy()
            nativeSessionCreated = false
            publishReport(report, stopOnFailure = false)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    runButton.text = "Запустить постоянную XR-сессию"
                    runButton.isEnabled = glReady
                }
            }
        }
        glView.requestRender()
    }

    private fun publishReport(report: OpenXrProbeReport, stopOnFailure: Boolean) {
        runOnUiThread {
            if (isFinishing || isDestroyed || !::statusView.isInitialized) return@runOnUiThread
            lastReportText = report.toDisplayText()
            statusView.text = lastReportText
            if (::copyReportButton.isInitialized) {
                copyReportButton.isEnabled = lastReportText.isNotBlank()
            }
            if (::runButton.isInitialized) {
                runButton.text = if (sessionRequested && nativeSessionCreated) {
                    "Остановить XR-сессию"
                } else {
                    "Запустить постоянную XR-сессию"
                }
                runButton.isEnabled = glReady
            }
            if (stopOnFailure) {
                sessionRequested = false
                nativeSessionCreated = false
                glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
        }
    }

    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onResume() {
        super.onResume()
        if (::glView.isInitialized) {
            glView.onResume()
            if (sessionRequested && nativeSessionCreated) {
                glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                glView.queueEvent {
                    val report = OpenXrPersistentGlesSession.resume()
                    publishReport(report, stopOnFailure = false)
                }
            }
            glView.requestRender()
        }
        applyImmersiveMode()
    }

    override fun onPause() {
        if (::glView.isInitialized) {
            val shouldDestroy = isFinishing
            if (nativeSessionCreated) {
                glView.queueEvent {
                    val report = if (shouldDestroy) {
                        OpenXrPersistentGlesSession.destroy().also {
                            nativeSessionCreated = false
                        }
                    } else {
                        OpenXrPersistentGlesSession.pause()
                    }
                    publishReport(report, stopOnFailure = false)
                }
                glView.requestRender()
            }
            glView.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        sessionRequested = false
        super.onDestroy()
    }

    companion object {
        private const val STATUS_UPDATE_INTERVAL_MS = 500L
    }
}
