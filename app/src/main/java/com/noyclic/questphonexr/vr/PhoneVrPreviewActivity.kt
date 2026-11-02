package com.noyclic.questphonexr.vr

import android.app.Activity
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView

/**
 * VR Shinecon diagnostic mode: side-by-side stereo + phone rotation-vector tracking (3DoF).
 * Lens distortion and viewer-specific calibration are intentionally not implied by this preview.
 */
class PhoneVrPreviewActivity : Activity(), SensorEventListener {
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: PhoneVrRenderer
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var sensorAvailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sensorAvailable = rotationSensor != null

        renderer = PhoneVrRenderer()
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                glView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val info = TextView(this).apply {
            text = if (sensorAvailable) {
                "VR SHINECON · СТЕРЕО + ГИРОСКОП · 3DoF"
            } else {
                "VR SHINECON · СТЕРЕО · ГИРОСКОП НЕ НАЙДЕН"
            }
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(12, 8, 12, 8)
            setBackgroundColor(0x990C111B.toInt())
        }
        root.addView(
            info,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )

        val exitButton = Button(this).apply {
            text = "Выйти из VR-теста"
            isAllCaps = false
            setOnClickListener { finish() }
        }
        root.addView(
            exitButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = 18 }
        )
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (::glView.isInitialized) glView.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        applyImmersiveMode()
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        if (::glView.isInitialized) glView.onPause()
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR
        ) return

        val deviceRotation = FloatArray(9)
        val displayRotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(deviceRotation, event.values)

        val displayRotation = windowManager.defaultDisplay.rotation
        val remapped = when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                deviceRotation,
                SensorManager.AXIS_Y,
                SensorManager.AXIS_MINUS_X,
                displayRotationMatrix
            )
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                deviceRotation,
                SensorManager.AXIS_MINUS_X,
                SensorManager.AXIS_MINUS_Y,
                displayRotationMatrix
            )
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                deviceRotation,
                SensorManager.AXIS_MINUS_Y,
                SensorManager.AXIS_X,
                displayRotationMatrix
            )
            else -> {
                System.arraycopy(deviceRotation, 0, displayRotationMatrix, 0, 9)
                true
            }
        }
        if (remapped) renderer.updateHeadRotation(displayRotationMatrix)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }
}
