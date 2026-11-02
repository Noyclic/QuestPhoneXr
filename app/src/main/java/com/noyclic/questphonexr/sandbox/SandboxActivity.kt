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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.noyclic.questphonexr.input.AndroidGamepadInputAdapter
import com.noyclic.questphonexr.tracking.HandLandmarkerController
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.input.PhysicalControllerSnapshot
import com.noyclic.questphonexr.vr.ViewerProfile
import com.noyclic.questphonexr.vr.ViewerProfileStore

/** Phone-first 3D sandbox for testing stereo viewing, Joy-Con input and object grabbing. */
class SandboxActivity : ComponentActivity(), SensorEventListener, InputManager.InputDeviceListener {
    private val inputManager by lazy { getSystemService(INPUT_SERVICE) as InputManager }
    private val simulation = SandboxSimulation()
    @Volatile private var controllerSnapshots: List<PhysicalControllerSnapshot> = emptyList()
    @Volatile private var latestHandFrame = HandTrackingFrame(0L, emptyList())
    private val cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var handTracker: HandLandmarkerController? = null
    private var cameraStatusMessage = "Камера рук: запускается"

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: SandboxRenderer
    private lateinit var sensorManager: SensorManager
    private lateinit var statusView: TextView
    private lateinit var viewerProfileStore: ViewerProfileStore
    private var viewerProfile = ViewerProfile()
    private var rotationSensor: Sensor? = null
    private var sensorAvailable = false
    private var lastFrameNanos = 0L
    private var lastStatusUpdateMs = 0L

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

            val targetFps = viewerProfile.targetFps.coerceAtLeast(1)
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
                val hands = latestHandFrame
                glView.queueEvent {
                    simulation.update(snapshot, hands, deltaSeconds)
                    renderer.updateScene(simulation.snapshot())
                    glView.requestRender()
                }
                val nowMs = SystemClock.uptimeMillis()
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

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sensorAvailable = rotationSensor != null

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
                    }
                )
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(640, 480))
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

    private fun onHandFrame(frame: HandTrackingFrame) {
        if (isFinishing) return
        latestHandFrame = frame
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
        val leftConnected = controllers.any { it.hand == com.noyclic.questphonexr.tracking.HandSide.LEFT }
        val rightConnected = controllers.any { it.hand == com.noyclic.questphonexr.tracking.HandSide.RIGHT }
        val scene = simulation.snapshot()
        return "QUESTPHONEXR · VR-ПЕСОЧНИЦА · СТЕРЕО 3DoF" +
            "\nГироскоп: " + (if (sensorAvailable) "да" else "нет") +
            " · IPD " + viewerProfile.ipdMillimeters + " мм · FPS до " + viewerProfile.targetFps +
            "\n" + cameraStatusMessage + " · рук: " + latestHandFrame.handCount +
            "\nJoy-Con L: " + (if (leftConnected) "подключён" else "нет") +
            " · R: " + (if (rightConnected) "подключён" else "нет") +
            "\nОбъектов: " + scene.objects.size + " · захвачено: " + scene.grabbedObjectCount +
            "\nЩипок пальцами или кнопки Joy-Con захватывают предметы"
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
        if (remapped) renderer.updateHeadRotation(displayRotationMatrix)
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

    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        handTracker?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val MAX_PHYSICS_STEP_SECONDS = 0.05f
        const val STATUS_REFRESH_INTERVAL_MS = 250L
    }
}
