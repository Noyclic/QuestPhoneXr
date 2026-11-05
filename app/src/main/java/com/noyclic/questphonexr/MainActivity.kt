package com.noyclic.questphonexr

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.noyclic.questphonexr.catalog.BuiltInGameInputProfiles
import com.noyclic.questphonexr.catalog.GameAction
import com.noyclic.questphonexr.catalog.GameInputAdapter
import com.noyclic.questphonexr.catalog.GameInputProfile
import com.noyclic.questphonexr.catalog.ProfiledVrGameInputAdapter
import com.noyclic.questphonexr.catalog.GameLibraryActivity
import com.noyclic.questphonexr.input.AndroidGamepadInputAdapter
import com.noyclic.questphonexr.input.PhysicalControllerSnapshot
import com.noyclic.questphonexr.input.VirtualControllerFrame
import com.noyclic.questphonexr.input.VirtualControllerMapper
import com.noyclic.questphonexr.tracking.HandLandmarkerController
import com.noyclic.questphonexr.tracking.HandCameraPreference
import com.noyclic.questphonexr.tracking.HandSide
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

/** Camera and controller diagnostics screen opened from the phone-first game library. */
class MainActivity : ComponentActivity(), InputManager.InputDeviceListener {

    private val inputManager by lazy { getSystemService(INPUT_SERVICE) as InputManager }

    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var gameProfileSpinner: Spinner

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val virtualControllerMapper = VirtualControllerMapper()
    private val gamepadInput = AndroidGamepadInputAdapter { snapshots ->
        runOnUiThread { updateVirtualControllerSnapshot(snapshots) }
    }
    @Volatile private var lastHandFrame = HandTrackingFrame(0L, emptyList())
    @Volatile private var latestVirtualControllers: VirtualControllerFrame? = null
    private var selectedProfile: GameInputProfile? = BuiltInGameInputProfiles.all.firstOrNull()
    private var selectedGameAdapter: GameInputAdapter? =
        selectedProfile?.let(::ProfiledVrGameInputAdapter)
    private var cameraStatusMessage = "QuestPhoneXR · диагностика отслеживания"
    private var latestHandCount = 0
    private var latestPinchDistance = "—"
    private var cameraProvider: ProcessCameraProvider? = null
    private var handTracker: HandLandmarkerController? = null

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startPhoneCamera()
        else showStatus("Для отслеживания рук на телефоне нужен доступ к камере.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        buildDebugScreen()
        updateVirtualControllerSnapshot()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startPhoneCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildDebugScreen() {
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xCC101319.toInt())
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(16, 19, 25))
            addView(
                previewView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        val backButton = Button(this).apply {
            text = "← Библиотека"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@MainActivity, GameLibraryActivity::class.java))
                finish()
            }
        }
        gameProfileSpinner = Spinner(this).apply {
            val adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                BuiltInGameInputProfiles.all.map { it.displayName }
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            this.adapter = adapter
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    selectedProfile = BuiltInGameInputProfiles.all.getOrNull(position)
                    selectedGameAdapter = selectedProfile?.let(::ProfiledVrGameInputAdapter)
                    renderDiagnostics()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    selectedProfile = null
                    selectedGameAdapter = null
                    renderDiagnostics()
                }
            }
        }
        val assignmentButton = Button(this).apply {
            text = "Найти / назначить контроллеры"
            isAllCaps = false
            setOnClickListener { showControllerAssignmentDialog() }
        }
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                gameProfileSpinner,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                assignmentButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 4)
            setBackgroundColor(0xCC101319.toInt())
            addView(statusView)
            addView(controlsRow)
            addView(backButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(
            topBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP
            )
        )
        setContentView(root)
    }

    private fun showControllerAssignmentDialog() {
        val connected = gamepadInput.refreshConnectedDevices()
        if (connected.isEmpty()) {
            Toast.makeText(
                this,
                "Android пока не обнаружил геймпады или Joy-Con.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val labels = connected.map { controller ->
            val assigned = when (controller.hand) {
                HandSide.LEFT -> "Левая рука"
                HandSide.RIGHT -> "Правая рука"
                else -> "Не назначен"
            }
            controller.deviceName + " · ID " + controller.deviceId + " · " + assigned
        }
        AlertDialog.Builder(this)
            .setTitle("Подключённые контроллеры")
            .setItems(labels.toTypedArray()) { _, index ->
                val selected = connected[index]
                val handChoices = arrayOf(
                    "Назначить левой руке",
                    "Назначить правой руке",
                    "Снять назначение"
                )
                AlertDialog.Builder(this)
                    .setTitle(selected.deviceName)
                    .setItems(handChoices) { _, handIndex ->
                        val hand = when (handIndex) {
                            0 -> HandSide.LEFT
                            1 -> HandSide.RIGHT
                            else -> null
                        }
                        gamepadInput.assignDevice(selected.deviceId, hand)
                        val result = when (hand) {
                            HandSide.LEFT -> "назначен левой руке"
                            HandSide.RIGHT -> "назначен правой руке"
                            else -> "отключён от виртуальной руки"
                        }
                        showStatus(selected.deviceName + " " + result)
                    }
                    .show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun startPhoneCamera() {
        if (isFinishing) return

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val cameraSelection = HandCameraPreference.resolve(this@MainActivity, provider)
                val useFrontCamera = cameraSelection.isFrontCamera

                if (handTracker == null) {
                    handTracker = HandLandmarkerController(
                        context = applicationContext,
                        mirrorFrontCamera = useFrontCamera,
                        onFrame = ::onHandFrame,
                        onError = { message ->
                            runOnUiThread { showStatus("MediaPipe: " + message) }
                        }
                    )
                }

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
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
                provider.bindToLifecycle(this, cameraSelection.selector, preview, analysis)
                showStatus(
                    if (useFrontCamera) {
                        "Фронтальная камера включена вручную · MediaPipe обнаруживает руки…"
                    } else {
                        "Задняя камера · фронтальная выключена · MediaPipe обнаруживает руки…"
                    }
                )
            } catch (error: Exception) {
                handTracker?.close()
                handTracker = null
                showStatus("Камеру не удалось запустить: " + (error.message ?: "неизвестная ошибка"))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onHandFrame(frame: HandTrackingFrame) {
        if (isFinishing) return
        lastHandFrame = frame

        val firstHand = frame.hands.firstOrNull()
        val thumb = firstHand?.thumbTip
        val index = firstHand?.indexTip
        val pinch = if (thumb != null && index != null) {
            String.format(Locale.US, "%.3f", hypot(thumb.x - index.x, thumb.y - index.y))
        } else {
            "—"
        }

        runOnUiThread {
            latestHandCount = frame.handCount
            latestPinchDistance = pinch
            updateVirtualControllerSnapshot(gamepadInput.snapshots())
        }
    }

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

    private fun updateVirtualControllerSnapshot(
        snapshots: List<PhysicalControllerSnapshot> = gamepadInput.snapshots()
    ) {
        latestVirtualControllers = virtualControllerMapper.map(lastHandFrame, snapshots)
        renderDiagnostics()
    }

    private fun renderDiagnostics() {
        if (!::statusView.isInitialized) return
        val frame = latestVirtualControllers
            ?: virtualControllerMapper.map(lastHandFrame, gamepadInput.snapshots())
        latestVirtualControllers = frame

        val assigned = gamepadInput.snapshots()
            .filter { it.hand != null }
            .joinToString { it.deviceName + " → " + it.hand?.name }
            .ifBlank { "нет назначенных геймпадов" }

        val actionFrame = selectedGameAdapter?.map(frame)
        val actionsText = actionFrame?.actions
            ?.entries
            ?.sortedBy { it.key.name }
            ?.joinToString("  ") { entry ->
                entry.key.name + "=" + String.format(Locale.US, "%.2f", entry.value)
            }
            ?.ifBlank { "профиль не содержит действий" }
            ?: "игровой профиль не выбран"

        statusView.text =
            cameraStatusMessage +
                "\nРук: " + latestHandCount + " · pinch distance: " + latestPinchDistance +
                "\nКонтроллеры: " + assigned +
                "\nL: " + formatController(frame.left) +
                "\nR: " + formatController(frame.right) +
                "\n" + (selectedProfile?.displayName ?: "Без профиля") +
                "\nДействия: " + actionsText
    }

    private fun formatController(
        state: com.noyclic.questphonexr.input.VirtualControllerState
    ): String {
        fun number(value: Float) = String.format(Locale.US, "%.2f", value)
        return state.source.name +
            " · XY(" + number(state.stickX) + ", " + number(state.stickY) + ")" +
            " · T " + number(state.trigger) +
            " · G " + number(state.grip) +
            (state.physicalDeviceName?.let { " · " + it } ?: "")
    }

    private fun showStatus(message: String) {
        cameraStatusMessage = message
        renderDiagnostics()
    }

    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        gamepadInput.refreshConnectedDevices()
    }

    override fun onPause() {
        inputManager.unregisterInputDeviceListener(this)
        super.onPause()
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        gamepadInput.refreshConnectedDevices()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        gamepadInput.onDeviceRemoved(deviceId)
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        gamepadInput.refreshConnectedDevices()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        handTracker?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
