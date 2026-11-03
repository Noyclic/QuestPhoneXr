package com.noyclic.questphonexr.vr

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * VR Shinecon diagnostic mode: side-by-side stereo + phone rotation-vector tracking (3DoF).
 * Rendering is explicitly paced to the selected frame-rate limit to reduce idle GPU work.
 */
class PhoneVrPreviewActivity : Activity(), SensorEventListener {
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: PhoneVrRenderer
    private lateinit var sensorManager: SensorManager
    private lateinit var infoView: TextView
    private lateinit var profileStore: ViewerProfileStore
    private var viewerProfile = ViewerProfile()
    private var rotationSensor: Sensor? = null
    private var sensorAvailable = false
    private var lastFrameRequestNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (isFinishing || !::glView.isInitialized) return

            val targetFps = viewerProfile.targetFps.coerceAtLeast(1)
            val frameIntervalNanos = NANOS_PER_SECOND / targetFps
            if (lastFrameRequestNanos == 0L ||
                frameTimeNanos - lastFrameRequestNanos >= frameIntervalNanos
            ) {
                lastFrameRequestNanos = frameTimeNanos
                glView.requestRender()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()

        profileStore = ViewerProfileStore(this)
        viewerProfile = profileStore.load()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sensorAvailable = rotationSensor != null

        renderer = PhoneVrRenderer().apply {
            applyViewerProfile(viewerProfile)
        }
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
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

        infoView = TextView(this).apply {
            text = sensorStatusText()
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(12, 8, 12, 8)
            setBackgroundColor(0x990C111B.toInt())
        }

        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x990C111B.toInt())
            addView(
                infoView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            val actions = LinearLayout(this@PhoneVrPreviewActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val settingsButton = controlButton("Калибровка") {
                showViewerSettings()
            }
            val recenterButton = controlButton("Перецентрировать") {
                glView.queueEvent { renderer.recenterHead() }
                infoView.text = sensorStatusText() + " · центр обновлён"
            }
            actions.addView(
                settingsButton,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            actions.addView(
                recenterButton,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(actions)
        }
        root.addView(
            topPanel,
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

    private fun showViewerSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
        }

        val ipdLabel = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            textSize = 15f
        }
        val ipdSlider = SeekBar(this).apply {
            max = ViewerProfile.MAX_IPD_MM - ViewerProfile.MIN_IPD_MM
            progress = viewerProfile.ipdMillimeters - ViewerProfile.MIN_IPD_MM
        }
        val fovLabel = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            textSize = 15f
        }
        val fovSlider = SeekBar(this).apply {
            max = ViewerProfile.MAX_FOV_DEGREES - ViewerProfile.MIN_FOV_DEGREES
            progress = viewerProfile.fieldOfViewDegrees - ViewerProfile.MIN_FOV_DEGREES
        }
        val distortionLabel = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            textSize = 15f
        }
        val distortionSlider = SeekBar(this).apply {
            max = ViewerProfile.MAX_LENS_DISTORTION_PERCENT -
                ViewerProfile.MIN_LENS_DISTORTION_PERCENT
            progress = viewerProfile.lensDistortionPercent -
                ViewerProfile.MIN_LENS_DISTORTION_PERCENT
        }
        val fpsChoices = ViewerProfile.SUPPORTED_TARGET_FPS
        val fpsLabel = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            textSize = 15f
        }
        val fpsSlider = SeekBar(this).apply {
            max = fpsChoices.lastIndex
            progress = fpsChoices.indexOf(viewerProfile.targetFps).coerceAtLeast(0)
        }
        val swapEyesCheckBox = CheckBox(this).apply {
            text = "Поменять левый и правый глаз местами"
            setTextColor(Color.DKGRAY)
            isChecked = viewerProfile.swapEyes
        }

        fun updateLabels() {
            ipdLabel.text = "Межзрачковое расстояние: " +
                (ipdSlider.progress + ViewerProfile.MIN_IPD_MM) + " мм"
            fovLabel.text = "Угол обзора: " +
                (fovSlider.progress + ViewerProfile.MIN_FOV_DEGREES) + "°"
            distortionLabel.text = "Коррекция линз: " +
                (distortionSlider.progress + ViewerProfile.MIN_LENS_DISTORTION_PERCENT) + "%"
            fpsLabel.text = "Ограничение рендера: " + fpsChoices[fpsSlider.progress] + " FPS"
        }
        updateLabels()
        ipdSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        fovSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        distortionSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        fpsSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        content.addView(ipdLabel)
        content.addView(ipdSlider)
        content.addView(fovLabel)
        content.addView(fovSlider)
        content.addView(distortionLabel)
        content.addView(distortionSlider)
        content.addView(fpsLabel)
        content.addView(fpsSlider)
        content.addView(swapEyesCheckBox)

        AlertDialog.Builder(this)
            .setTitle("Настройка VR Shinecon")
            .setView(content)
            .setNegativeButton("Отмена", null)
            .setNeutralButton("По умолчанию") { _, _ ->
                viewerProfile = ViewerProfile()
                profileStore.save(viewerProfile)
                renderer.applyViewerProfile(viewerProfile)
                lastFrameRequestNanos = 0L
                infoView.text = sensorStatusText()
            }
            .setPositiveButton("Сохранить") { _, _ ->
                viewerProfile = ViewerProfile(
                    ipdMillimeters = ipdSlider.progress + ViewerProfile.MIN_IPD_MM,
                    fieldOfViewDegrees = fovSlider.progress + ViewerProfile.MIN_FOV_DEGREES,
                    lensDistortionPercent = distortionSlider.progress +
                        ViewerProfile.MIN_LENS_DISTORTION_PERCENT,
                    swapEyes = swapEyesCheckBox.isChecked,
                    targetFps = fpsChoices[fpsSlider.progress]
                ).normalized()
                profileStore.save(viewerProfile)
                renderer.applyViewerProfile(viewerProfile)
                lastFrameRequestNanos = 0L
                infoView.text = sensorStatusText()
            }
            .show()
    }

    private fun sensorStatusText(): String {
        val sensorText = if (sensorAvailable) {
            "VR SHINECON · СТЕРЕО + ГИРОСКОП · 3DoF"
        } else {
            "VR SHINECON · СТЕРЕО · ГИРОСКОП НЕ НАЙДЕН"
        }
        return sensorText + " · IPD " + viewerProfile.ipdMillimeters +
            " мм · FOV " + viewerProfile.fieldOfViewDegrees + "° · линзы " +
            viewerProfile.lensDistortionPercent + "% · " + viewerProfile.targetFps + " FPS"
    }

    private fun controlButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

    override fun onResume() {
        super.onResume()
        if (::glView.isInitialized) {
            glView.onResume()
            lastFrameRequestNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        applyImmersiveMode()
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
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

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
