package com.noyclic.questphonexr.runtime

import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Runs the native OpenXR/Monado smoke test away from the app's main process.
 * The native probe is invoked only on GLSurfaceView's GLES 3 thread.
 */
class OpenXrRuntimeProbeActivity : ComponentActivity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var statusView: TextView
    private lateinit var runButton: Button
    @Volatile private var glReady = false
    @Volatile private var probeRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
                    glReady = true
                    runOnUiThread {
                        statusView.text = "GLES 3 готов. Нажми кнопку для запуска изолированного теста Monado/OpenXR."
                        runButton.isEnabled = true
                    }
                }

                override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) = Unit
                override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) = Unit
            })
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(12, 16, 23))
            addView(glView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        statusView = TextView(this).apply {
            text = "Запускаем отдельный процесс XR…"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(16, 14, 16, 14)
            setBackgroundColor(0xE6101722.toInt())
        }
        runButton = Button(this).apply {
            text = "Запустить тест XR-сеанса"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { runProbe() }
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
                text = "OPENXR · ИЗОЛИРОВАННЫЙ ТЕСТ"
                setTextColor(0xFF8FC7FF.toInt())
                textSize = 12f
            })
            addView(ScrollView(this@OpenXrRuntimeProbeActivity).apply {
                addView(statusView)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
            addView(runButton)
            addView(closeButton)
        }
        root.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))
        setContentView(root)
    }

    private fun runProbe() {
        if (!glReady || probeRunning || isFinishing) return
        probeRunning = true
        runButton.isEnabled = false
        statusView.text = "Подключаемся к Monado, передаём Activity Surface и проверяем первый стереокадр…"
        glView.queueEvent {
            val report = OpenXrGlesSessionProbe.inspectReport(this@OpenXrRuntimeProbeActivity)
            runOnUiThread {
                statusView.text = report.toDisplayText()
                probeRunning = false
                runButton.isEnabled = glReady && !isFinishing
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
        if (::glView.isInitialized) glView.onResume()
    }

    override fun onPause() {
        if (::glView.isInitialized) glView.onPause()
        super.onPause()
    }
}