package com.noyclic.questphonexr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.noyclic.questphonexr.input.AndroidGamepadInputAdapter
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
    }

    private fun showStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        handTracker?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
