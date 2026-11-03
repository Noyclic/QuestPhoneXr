package com.noyclic.questphonexr.sandbox

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import java.util.Locale
import com.noyclic.questphonexr.input.AndroidGamepadInputAdapter
import com.noyclic.questphonexr.tracking.HandLandmarkerController
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.input.PhysicalControllerSnapshot
import com.noyclic.questphonexr.input.XrTrackingUdpSender
import com.noyclic.questphonexr.vr.ViewerProfile
import com.noyclic.questphonexr.vr.ViewerProfileStore
import com.noyclic.questphonexr.performance.AndroidDeviceProfiler
import com.noyclic.questphonexr.performance.AdaptivePerformanceController
import com.noyclic.questphonexr.performance.AdaptivePerformanceState
import com.noyclic.questphonexr.performance.DevicePerformancePolicy
import com.noyclic.questphonexr.performance.DevicePerformancePreset

/** Phone-first 3D sandbox for testing stereo viewing, Joy-Con input and object grabbing. */
class SandboxActivity : ComponentActivity(), SensorEventListener, InputManager.InputDeviceListener {
    private val inputManager by lazy { getSystemService(INPUT_SERVICE) as InputManager }
    private val simulation = SandboxSimulation()
    @Volatile private var controllerSnapshots: List<PhysicalControllerSnapshot> = emptyList()
    @Volatile private var latestHandFrame = HandTrackingFrame(0L, emptyList())
    @Volatile private var measuredHandTrackingFps = 0f
    @Volatile private var latestHandPipelineLatencyMs = 0L
    @Volatile private var cameraSourceSizeLabel = "—"
    @Volatile private var mediaPipeInputSizeLabel = "—"
    private var previousHandFrameTimestampMs = 0L
    private val cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var handTracker: HandLandmarkerController? = null
    private var cameraStatusMessage = "Камера рук: запускается"

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: SandboxRenderer
    private lateinit var sensorManager: SensorManager
    private lateinit var statusView: TextView
    private lateinit var viewerProfileStore: ViewerProfileStore
    private lateinit var handCalibrationStore: SandboxHandCalibrationStore
    private lateinit var adaptivePerformance: AdaptivePerformanceController
    private var adaptivePerformanceState = AdaptivePerformanceState(30, 66L, 0)
    private var handCalibration = SandboxHandCalibration.DEFAULT
    private var viewerProfile = ViewerProfile()
    private lateinit var performancePreset: DevicePerformancePreset
    private var rotationSensor: Sensor? = null
    private var sensorAvailable = false
    private var lastFrameNanos = 0L
    private var lastStatusUpdateMs = 0L
    private var trackingSender: XrTrackingUdpSender? = null
    @Volatile private var latestHeadRotationMatrix: FloatArray? = null
    private var pcBridgeHost = ""
    private var pcBridgeStatus = "ПК: отключён"

    private val gamepadInput = AndroidGamepadInputAdapter { snapshots ->
        controllerSnapshots = snapshots
        runOnUiThread { updateStatus() }
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startHandTracking()
        else {
            cameraStatusMessage = "Камера рук: разрешение не выдано · можно управлять Joy-Con"
            updateStatus()
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (isFinishing || !::glView.isInitialized) return

            val nowMs = SystemClock.uptimeMillis()
            val updatedAdaptiveState = adaptivePerformance.observe(
                nowMs = nowMs,
                renderFps = renderer.measuredFps,
                handTrackingFps = measuredHandTrackingFps,
                pipelineLatencyMs = latestHandPipelineLatencyMs
            )
            if (updatedAdaptiveState != adaptivePerformanceState) {
                adaptivePerformanceState = updatedAdaptiveState
                handTracker?.updateMinimumInferenceIntervalMs(
                    updatedAdaptiveState.minimumInferenceIntervalMs
                )
                updateStatus()
            }

            val targetFps = adaptivePerformanceState.renderFpsCap
            val intervalNanos = NANOS_PER_SECOND / targetFps
            if (lastFrameNanos == 0L || frameTimeNanos - lastFrameNanos >= intervalNanos) {
                val deltaSeconds = if (lastFrameNanos == 0L) {
                    1f / targetFps
                } else {
                    ((frameTimeNanos - lastFrameNanos).toDouble() / NANOS_PER_SECOND)
                        .toFloat()
                        .coerceIn(0.001f, MAX_PHYSICS_STEP_SECONDS)
                }
                lastFrameNanos = frameTimeNanos
                val snapshot = controllerSnapshots
                val latestHands = latestHandFrame
                val handsAgeMs = SystemClock.uptimeMillis() - latestHands.timestampMs
                val hands = if (latestHands.hands.isNotEmpty() && handsAgeMs > HAND_FRAME_STALE_MS) {
                    HandTrackingFrame(SystemClock.uptimeMillis(), emptyList()).also {
                        latestHandFrame = it
                        trackingSender?.publish(it)
                    }
                } else {
                    latestHands
                }
                glView.queueEvent {
                    simulation.update(snapshot, hands, deltaSeconds)
                    renderer.updateScene(simulation.snapshot())
                    glView.requestRender()
                }
                if (nowMs - lastStatusUpdateMs >= STATUS_REFRESH_INTERVAL_MS) {
                    lastStatusUpdateMs = nowMs
                    updateStatus()
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()

        viewerProfileStore = ViewerProfileStore(this)
        viewerProfile = viewerProfileStore.load()
        handCalibrationStore = SandboxHandCalibrationStore(this)
        handCalibration = handCalibrationStore.load()
        simulation.setHandCalibration(handCalibration)
        // One lightweight capability read per sandbox entry; no background benchmarking.
        performancePreset = DevicePerformancePolicy.recommend(
            AndroidDeviceProfiler.read(this)
        )
        adaptivePerformance = AdaptivePerformanceController(
            preset = performancePreset,
            userRequestedFps = viewerProfile.targetFps
        )
        adaptivePerformanceState = adaptivePerformance.currentState()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sensorAvailable = rotationSensor != null
        pcBridgeHost = getSharedPreferences(PC_BRIDGE_PREFERENCES, MODE_PRIVATE)
            .getString(KEY_PC_HOST, "")
            .orEmpty()

        renderer = SandboxRenderer().apply {
            applyViewerProfile(viewerProfile)
            updateScene(simulation.snapshot())
        }
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        buildScreen()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startHandTracking()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildScreen() {
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

        statusView = TextView(this).apply {
            text = statusText()
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(12, 8, 12, 8)
            setBackgroundColor(0x990C111B.toInt())
        }

        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x990C111B.toInt())
            addView(statusView)

            val buttons = LinearLayout(this@SandboxActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            buttons.addView(
                controlButton("Добавить куб") { spawnCube() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            buttons.addView(
                controlButton("Контроллеры") { showControllerAssignmentDialog() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            buttons.addView(
                controlButton("Перецентрировать") {
                    glView.queueEvent { renderer.recenterHead() }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            buttons.addView(
                controlButton("Сбросить") { resetScene() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(buttons)
            addView(
                controlButton("Подключить ПК · телеметрия рук и головы") {
                    showPcBridgeDialog()
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                controlButton("Калибровка рук") { showHandCalibrationDialog() },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                controlButton("Разрешение камеры") { showCameraResolutionDialog() },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            topPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        root.addView(
            controlButton("Выйти из песочницы") { finish() },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = 14 }
        )

        setContentView(root)
    }

    private fun startHandTracking() {
        if (isFinishing || handTracker != null) return

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isFinishing) return@addListener
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val useBackCamera = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                val selector = if (useBackCamera) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                if (!useBackCamera && !provider.hasCamera(selector)) {
                    cameraStatusMessage = "Камера рук: камера не найдена"
                    updateStatus()
                    return@addListener
                }

                handTracker = HandLandmarkerController(
                    context = applicationContext,
                    mirrorFrontCamera = !useBackCamera,
                    onFrame = ::onHandFrame,
                    onError = { message ->
                        runOnUiThread {
                            cameraStatusMessage = "MediaPipe: " + message
                            updateStatus()
                        }
                    },
                    maxHands = performancePreset.maxTrackedHands,
                    minimumInferenceIntervalMs = adaptivePerformanceState.minimumInferenceIntervalMs,
                    outputWidthPx = effectiveCameraWidthPx(),
                    outputHeightPx = effectiveCameraHeightPx()
                )
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(
                        android.util.Size(effectiveCameraWidthPx(), effectiveCameraHeightPx())
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { useCase ->
                        useCase.setAnalyzer(cameraExecutor) { frame ->
                            handTracker?.analyze(frame) ?: frame.close()
                        }
                    }

                provider.unbindAll()
                provider.bindToLifecycle(this, selector, analysis)
                cameraStatusMessage = if (useBackCamera) {
                    "MediaPipe: задняя камера · жест pinch включён"
                } else {
                    "MediaPipe: фронтальная камера · жест pinch включён"
                }
                updateStatus()
            } catch (error: Exception) {
                cameraStatusMessage = "Не удалось запустить камеру рук: " +
                    (error.message ?: "неизвестная ошибка")
                updateStatus()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun effectiveCameraWidthPx(): Int =
        if (viewerProfile.cameraWidthPx == ViewerProfile.AUTO_CAMERA_SIZE) {
            performancePreset.cameraWidthPx
        } else viewerProfile.cameraWidthPx

    private fun effectiveCameraHeightPx(): Int =
        if (viewerProfile.cameraHeightPx == ViewerProfile.AUTO_CAMERA_SIZE) {
            performancePreset.cameraHeightPx
        } else viewerProfile.cameraHeightPx

    private fun showHandCalibrationDialog() {
        val current = handCalibration.normalized()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        fun sliderRow(title: String, progress: Int): Pair<TextView, SeekBar> {
            val label = TextView(this).apply {
                setTextColor(Color.DKGRAY)
                textSize = 13f
                setPadding(0, dp(5), 0, 0)
                text = title
            }
            val slider = SeekBar(this).apply {
                max = 100
                this.progress = progress.coerceIn(0, 100)
            }
            content.addView(label)
            content.addView(slider)
            return label to slider
        }

        fun offsetProgress(value: Float): Int =
            (((value + SandboxHandCalibration.MAX_OFFSET_METERS) /
                (SandboxHandCalibration.MAX_OFFSET_METERS * 2f)) * 100f).toInt()

        fun scaleProgress(value: Float): Int =
            (((value - SandboxHandCalibration.MIN_SCALE) /
                (SandboxHandCalibration.MAX_SCALE - SandboxHandCalibration.MIN_SCALE)) * 100f).toInt()

        fun depthProgress(value: Float): Int =
            (value / SandboxHandCalibration.MAX_DEPTH_SCALE * 100f).toInt()

        val offsetX = sliderRow("Смещение X", offsetProgress(current.horizontalOffset))
        val offsetY = sliderRow("Смещение Y", offsetProgress(current.verticalOffset))
        val scaleX = sliderRow("Ширина движения", scaleProgress(current.horizontalScale))
        val scaleY = sliderRow("Высота движения", scaleProgress(current.verticalScale))
        val depth = sliderRow("Влияние относительной глубины", depthProgress(current.relativeDepthScale))

        fun offsetValue(progress: Int): Float =
            (progress / 100f * SandboxHandCalibration.MAX_OFFSET_METERS * 2f) -
                SandboxHandCalibration.MAX_OFFSET_METERS

        fun scaleValue(progress: Int): Float =
            SandboxHandCalibration.MIN_SCALE +
                (progress / 100f) *
                (SandboxHandCalibration.MAX_SCALE - SandboxHandCalibration.MIN_SCALE)

        fun depthValue(progress: Int): Float =
            progress / 100f * SandboxHandCalibration.MAX_DEPTH_SCALE

        fun selectedCalibration() = SandboxHandCalibration(
            horizontalOffset = offsetValue(offsetX.second.progress),
            verticalOffset = offsetValue(offsetY.second.progress),
            horizontalScale = scaleValue(scaleX.second.progress),
            verticalScale = scaleValue(scaleY.second.progress),
            relativeDepthScale = depthValue(depth.second.progress)
        ).normalized()

        fun updateLabels() {
            val value = selectedCalibration()
            offsetX.first.text = String.format(Locale.US, "Смещение X: %+.2f м", value.horizontalOffset)
            offsetY.first.text = String.format(Locale.US, "Смещение Y: %+.2f м", value.verticalOffset)
            scaleX.first.text = String.format(Locale.US, "Ширина движения: %.2f×", value.horizontalScale)
            scaleY.first.text = String.format(Locale.US, "Высота движения: %.2f×", value.verticalScale)
            depth.first.text = String.format(Locale.US, "Влияние относительной глубины: %.2f×", value.relativeDepthScale)
        }

        listOf(offsetX, offsetY, scaleX, scaleY, depth).forEach { pair ->
            pair.second.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateLabels()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        updateLabels()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Калибровка рук")
            .setMessage("Подстрой положение и размер рабочей зоны под камеру. Настройки применятся к обеим рукам.")
            .setView(content)
            .setNegativeButton("Отмена", null)
            .setNeutralButton("По умолчанию", null)
            .setPositiveButton("Применить", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                saveHandCalibration(selectedCalibration())
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                saveHandCalibration(SandboxHandCalibration.DEFAULT)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun saveHandCalibration(value: SandboxHandCalibration) {
        val safeValue = value.normalized()
        handCalibration = safeValue
        handCalibrationStore.save(safeValue)
        glView.queueEvent {
            simulation.setHandCalibration(safeValue)
            renderer.updateScene(simulation.snapshot())
            glView.requestRender()
        }
        Toast.makeText(this, "Калибровка рук сохранена.", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun showCameraResolutionDialog() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        fields.addView(TextView(this).apply {
            text = "Ширина и высота: 64×48 — 1920×1440. Например, 192×88. Кадр будет вписан без растяжения."
            textSize = 13f
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val widthInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "Ширина"
            setText(effectiveCameraWidthPx().toString())
        }
        val heightInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "Высота"
            setText(effectiveCameraHeightPx().toString())
        }
        row.addView(widthInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply { text = " × "; textSize = 20f })
        row.addView(heightInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fields.addView(row)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Разрешение камеры")
            .setView(fields)
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Авто", null)
            .setPositiveButton("Применить", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val width = widthInput.text.toString().toIntOrNull()
                val height = heightInput.text.toString().toIntOrNull()
                if (width == null || height == null ||
                    width !in ViewerProfile.MIN_CAMERA_WIDTH_PX..ViewerProfile.MAX_CAMERA_WIDTH_PX ||
                    height !in ViewerProfile.MIN_CAMERA_HEIGHT_PX..ViewerProfile.MAX_CAMERA_HEIGHT_PX
                ) {
                    Toast.makeText(this, "Ширина: 64–1920, высота: 48–1440 пикселей", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                viewerProfile = viewerProfile.copy(cameraWidthPx = width, cameraHeightPx = height).normalized()
                viewerProfileStore.save(viewerProfile)
                dialog.dismiss()
                restartHandTracking()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                viewerProfile = viewerProfile.copy(
                    cameraWidthPx = ViewerProfile.AUTO_CAMERA_SIZE,
                    cameraHeightPx = ViewerProfile.AUTO_CAMERA_SIZE
                )
                viewerProfileStore.save(viewerProfile)
                dialog.dismiss()
                restartHandTracking()
            }
        }
        dialog.show()
    }

    private fun restartHandTracking() {
        cameraProvider?.unbindAll()
        handTracker?.close()
        handTracker = null
        latestHandFrame = HandTrackingFrame(SystemClock.uptimeMillis(), emptyList())
        cameraStatusMessage = "Камера: применяем новое разрешение…"
        updateStatus()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startHandTracking()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun onHandFrame(frame: HandTrackingFrame) {
        if (isFinishing) return
        val previousTimestamp = previousHandFrameTimestampMs
        if (previousTimestamp > 0L && frame.timestampMs > previousTimestamp) {
            val intervalMs = frame.timestampMs - previousTimestamp
            measuredHandTrackingFps = (1000f / intervalMs).coerceAtMost(240f)
        }
        previousHandFrameTimestampMs = frame.timestampMs
        latestHandPipelineLatencyMs = frame.pipelineLatencyMs
        cameraSourceSizeLabel = if (frame.sourceWidthPx > 0 && frame.sourceHeightPx > 0) {
            "${frame.sourceWidthPx}×${frame.sourceHeightPx}"
        } else {
            "—"
        }
        mediaPipeInputSizeLabel = if (frame.inputWidthPx > 0 && frame.inputHeightPx > 0) {
            "${frame.inputWidthPx}×${frame.inputHeightPx}"
        } else {
            "—"
        }
        latestHandFrame = frame
        trackingSender?.publish(frame)
    }

    private fun showControllerAssignmentDialog() {
        val connected = gamepadInput.refreshConnectedDevices()
        if (connected.isEmpty()) {
            statusView.text = "Android пока не обнаружил Joy-Con или другой геймпад."
            return
        }

        val labels = connected.map { controller ->
            val assigned = when (controller.hand) {
                com.noyclic.questphonexr.tracking.HandSide.LEFT -> "левая рука"
                com.noyclic.questphonexr.tracking.HandSide.RIGHT -> "правая рука"
                else -> "не назначен"
            }
            controller.deviceName + " · ID " + controller.deviceId + " · " + assigned
        }
        AlertDialog.Builder(this)
            .setTitle("Назначение Joy-Con")
            .setItems(labels.toTypedArray()) { _, index ->
                val selected = connected[index]
                val options = arrayOf("Левая рука", "Правая рука", "Снять назначение")
                AlertDialog.Builder(this)
                    .setTitle(selected.deviceName)
                    .setItems(options) { _, handIndex ->
                        val hand = when (handIndex) {
                            0 -> com.noyclic.questphonexr.tracking.HandSide.LEFT
                            1 -> com.noyclic.questphonexr.tracking.HandSide.RIGHT
                            else -> null
                        }
                        gamepadInput.assignDevice(selected.deviceId, hand)
                    }
                    .show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showPcBridgeDialog() {
        val hostInput = EditText(this).apply {
            hint = "Например: 192.168.1.25"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(pcBridgeHost)
            setSelection(text.length)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(TextView(this@SandboxActivity).apply {
                text = "Укажи IPv4-адрес компьютера. Телефон и ПК должны быть в одной локальной сети.\n\nПорт UDP: 39471 · частота: до 30 пакетов/с.\nНа ПК должен быть запущен QuestPhoneXRTrackingReceiver."
                setTextColor(Color.DKGRAY)
                textSize = 14f
            })
            addView(hostInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Связь с ПК · XR Tracking")
            .setView(content)
            .setPositiveButton("Подключить") { _, _ ->
                val host = hostInput.text.toString().trim()
                if (host.isBlank()) {
                    Toast.makeText(this, "Укажи IP-адрес компьютера.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pcBridgeHost = host
                getSharedPreferences(PC_BRIDGE_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PC_HOST, host)
                    .apply()

                val sender = trackingSender ?: XrTrackingUdpSender(
                    onStatus = { message ->
                        runOnUiThread {
                            if (!isFinishing) {
                                pcBridgeStatus = message
                                updateStatus()
                            }
                        }
                    }
                ).also { trackingSender = it }
                latestHeadRotationMatrix?.let(sender::updateHeadRotation)
                sender.publish(
                    latestHandFrame.takeIf { it.timestampMs > 0L }
                        ?: HandTrackingFrame(SystemClock.uptimeMillis(), emptyList())
                )
                runCatching { sender.start(host) }
                    .onFailure { error ->
                        pcBridgeStatus = "ПК: ${error.message ?: "неверный адрес"}"
                        updateStatus()
                    }
            }
            .setNeutralButton("Остановить") { _, _ ->
                trackingSender?.stop()
                pcBridgeStatus = "ПК: отключён"
                updateStatus()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun spawnCube() {
        simulation.spawnCube()
        glView.queueEvent { renderer.updateScene(simulation.snapshot()) }
        glView.requestRender()
        updateStatus()
    }

    private fun resetScene() {
        simulation.reset()
        glView.queueEvent { renderer.updateScene(simulation.snapshot()) }
        glView.requestRender()
        updateStatus()
    }

    private fun statusText(): String {
        val controllers = controllerSnapshots
        val leftConnected = controllers.any {
            it.hand == com.noyclic.questphonexr.tracking.HandSide.LEFT
        }
        val rightConnected = controllers.any {
            it.hand == com.noyclic.questphonexr.tracking.HandSide.RIGHT
        }
        val scene = simulation.snapshot()
        val actualFps = renderer.measuredFps
        val fpsText = if (actualFps > 0f) {
            String.format(Locale.US, "%.1f", actualFps)
        } else {
            "—"
        }
        val handFpsText = if (measuredHandTrackingFps > 0f) {
            String.format(Locale.US, "%.1f", measuredHandTrackingFps)
        } else {
            "—"
        }
        val inferenceFps =
            1000L / adaptivePerformanceState.minimumInferenceIntervalMs.coerceAtLeast(1L)

        return buildString {
            append("QUESTPHONEXR · VR-ПЕСОЧНИЦА · СТЕРЕО 3DoF")
            append('\n')
            append("Гироскоп: ").append(if (sensorAvailable) "да" else "нет")
            append(" · IPD ").append(viewerProfile.ipdMillimeters)
            append(" мм · FPS лимит ").append(adaptivePerformanceState.renderFpsCap)
            append(" · факт ").append(fpsText)
            append('\n')
            append("Автопрофиль: ").append(performancePreset.tier.label)
            append(" · ").append(adaptivePerformanceState.label)
            append(" · камера ").append(effectiveCameraWidthPx())
            append('×').append(effectiveCameraHeightPx())
            append(" · две руки · MediaPipe до ").append(inferenceFps).append(" кадр/с")
            append('\n')
            append(cameraStatusMessage).append(" · рук: ").append(latestHandFrame.handCount)
            append('\n')
            append("MediaPipe: ").append(handFpsText)
            append(" FPS · pipeline ").append(latestHandPipelineLatencyMs)
            append(" мс · камера ").append(cameraSourceSizeLabel)
            append(" → модель ").append(mediaPipeInputSizeLabel)
            append('\n')
            append(pcBridgeStatus).append('\n')
            append("Joy-Con L: ").append(if (leftConnected) "подключён" else "нет")
            append(" · R: ").append(if (rightConnected) "подключён" else "нет")
            append('\n')
            append("Объектов: ").append(scene.objects.size)
            append(" · захвачено: ").append(scene.grabbedObjectCount)
            append(" · очки: ").append(scene.score)
            append('\n')
            append("Щипок пальцами или кнопки Joy-Con захватывают предметы")
        }
    }

    private fun updateStatus() {
        if (::statusView.isInitialized) statusView.text = statusText()
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
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
        if (::sensorManager.isInitialized) {
            rotationSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        controllerSnapshots = gamepadInput.refreshConnectedDevices()
        applyImmersiveMode()
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
        inputManager.unregisterInputDeviceListener(this)
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

        val remapped = when (windowManager.defaultDisplay.rotation) {
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
        if (remapped) {
            renderer.updateHeadRotation(displayRotationMatrix)
            latestHeadRotationMatrix = displayRotationMatrix.copyOf()
            trackingSender?.updateHeadRotation(displayRotationMatrix)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (gamepadInput.onKeyEvent(event, true)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (gamepadInput.onKeyEvent(event, false)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepadInput.onMotionEvent(event)) return true
        return super.onGenericMotionEvent(event)
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        controllerSnapshots = gamepadInput.refreshConnectedDevices()
        updateStatus()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        gamepadInput.onDeviceRemoved(deviceId)
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        controllerSnapshots = gamepadInput.refreshConnectedDevices()
        updateStatus()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onDestroy() {
        trackingSender?.close()
        trackingSender = null
        cameraProvider?.unbindAll()
        handTracker?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val MAX_PHYSICS_STEP_SECONDS = 0.05f
        const val STATUS_REFRESH_INTERVAL_MS = 250L
        const val HAND_FRAME_STALE_MS = 500L
        const val PC_BRIDGE_PREFERENCES = "pc_tracking_bridge"
        const val KEY_PC_HOST = "host"
    }
}
