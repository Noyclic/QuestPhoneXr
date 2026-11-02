package com.noyclic.questphonexr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.text.InputType
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.noyclic.questphonexr.input.AndroidGamepadInputAdapter
import com.noyclic.questphonexr.input.ControllerFrameUdpSender
import com.noyclic.questphonexr.input.VirtualControllerFrame
import com.noyclic.questphonexr.input.VirtualControllerMapper
import com.noyclic.questphonexr.tracking.HandLandmarkerController
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var targetAddressInput: EditText
    private val controllerSender = ControllerFrameUdpSender { message ->
        runOnUiThread { showStatus(message) }
    }

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val virtualControllerMapper = VirtualControllerMapper()
    private val gamepadInput = AndroidGamepadInputAdapter()
    @Volatile private var lastHandFrame = HandTrackingFrame(0L, emptyList())
    @Volatile private var latestVirtualControllers: VirtualControllerFrame? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var handTracker: HandLandmarkerController? = null
    private var isQuestDevice = false

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
        isQuestDevice = packageManager.hasSystemFeature("android.hardware.vr.headtracking")

        if (isQuestDevice) {
            showStatus(
                "QuestPhoneXR запущен на VR-устройстве.\n" +
                    "OpenXR Android Loader включён в APK.\n" +
                    "Следующая контрольная точка — OpenXR instance/session и рендер в шлеме."
            )
            return
        }

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
            textSize = 16f
            setPadding(24, 18, 24, 18)
            setBackgroundColor(0xCC101319.toInt())
            text = "QuestPhoneXR · запуск"
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
            addView(
                statusView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP
                )
            )
        }

        val savedHost = getSharedPreferences("questphonexr", MODE_PRIVATE)
            .getString("quest_host", "") ?: ""
        targetAddressInput = EditText(this).apply {
            hint = "IP-адрес Quest (например, 192.168.1.50)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(savedHost)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFB8C0CC.toInt())
            setPadding(16, 8, 16, 8)
            setBackgroundColor(0xDD252B35.toInt())
        }
        val connectButton = Button(this).apply {
            text = "Подключить Quest"
            setOnClickListener {
                val host = targetAddressInput.text.toString().trim()
                try {
                    controllerSender.start(host)
                    getSharedPreferences("questphonexr", MODE_PRIVATE)
                        .edit().putString("quest_host", host).apply()
                } catch (error: IllegalArgumentException) {
                    showStatus(error.message ?: "Проверь IP-адрес Quest.")
                }
            }
        }
        val stopButton = Button(this).apply {
            text = "Стоп"
            setOnClickListener { controllerSender.stop() }
        }
        val connectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 4, 12, 12)
            setBackgroundColor(0xCC101319.toInt())
            addView(targetAddressInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(connectButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(stopButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(
            connectionBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM
            )
        )
        setContentView(root)
    }

    private fun startPhoneCamera() {
        if (isQuestDevice || isFinishing) return

        try {
            if (handTracker == null) {
                handTracker = HandLandmarkerController(
                    context = applicationContext,
                    mirrorFrontCamera = true,
                    onFrame = ::onHandFrame,
                    onError = { message ->
                        runOnUiThread { showStatus("MediaPipe: " + message) }
                    }
                )
            }
        } catch (error: Exception) {
            showStatus("Не удалось запустить MediaPipe: " + (error.message ?: "неизвестная ошибка"))
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

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
                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                provider.bindToLifecycle(this, selector, preview, analysis)
                showStatus("Камера запущена · MediaPipe обнаруживает руки…")
            } catch (error: Exception) {
                showStatus("Камеру не удалось запустить: " + (error.message ?: "неизвестная ошибка"))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onHandFrame(frame: HandTrackingFrame) {
        if (isFinishing) return
        lastHandFrame = frame
        latestVirtualControllers = virtualControllerMapper.map(frame, gamepadInput.snapshots())
        latestVirtualControllers?.let(controllerSender::publish)

        val firstHand = frame.hands.firstOrNull()
        val thumb = firstHand?.thumbTip
        val index = firstHand?.indexTip
        val pinch = if (thumb != null && index != null) {
            String.format(Locale.US, "%.3f", hypot(thumb.x - index.x, thumb.y - index.y))
        } else {
            "—"
        }
        val assignedGamepads = gamepadInput.snapshots().count { it.hand != null }

        runOnUiThread {
            showStatus(
                "MediaPipe LIVE_STREAM · рук: " + frame.handCount +
                    " · pinch distance: " + pinch + "\n" +
                    "Virtual Controllers · назначенных геймпадов: " + assignedGamepads
            )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (gamepadInput.onKeyEvent(event, true)) {
            refreshVirtualControllerSnapshot()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (gamepadInput.onKeyEvent(event, false)) {
            refreshVirtualControllerSnapshot()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepadInput.onMotionEvent(event)) {
            refreshVirtualControllerSnapshot()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun refreshVirtualControllerSnapshot() {
        latestVirtualControllers = virtualControllerMapper.map(lastHandFrame, gamepadInput.snapshots())
        latestVirtualControllers?.let(controllerSender::publish)
    }

    private fun showStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        handTracker?.close()
        controllerSender.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
