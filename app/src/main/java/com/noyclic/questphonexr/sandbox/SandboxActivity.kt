package com.noyclic.questphonexr.sandbox

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
import androidx.core.app.NotificationManagerCompat
import android.opengl.GLSurfaceView
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import android.widget.Switch
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import java.util.Locale
import java.io.File
import com.noyclic.questphonexr.input.AndroidGamepadInputAdapter
import com.noyclic.questphonexr.input.GamepadButtonCodes
import com.noyclic.questphonexr.input.VirtualControllerMapper
import com.noyclic.questphonexr.input.VirtualControllerState
import com.noyclic.questphonexr.input.ControllerInputSource
import com.noyclic.questphonexr.tracking.HandLandmarkerController
import com.google.mediapipe.tasks.core.Delegate
import com.noyclic.questphonexr.tracking.HandCameraPreference
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.tracking.HandTrackingMetricsSnapshot
import com.noyclic.questphonexr.tracking.HandTrackingPreset
import com.noyclic.questphonexr.tracking.HandTrackingDelegatePreference
import com.noyclic.questphonexr.tracking.HandTrackingPresetStore
import com.noyclic.questphonexr.input.PhysicalControllerSnapshot
import com.noyclic.questphonexr.input.XrTrackingUdpSender
import com.noyclic.questphonexr.vr.ViewerProfile
import com.noyclic.questphonexr.vr.ViewerProfileStore
import com.noyclic.questphonexr.ui.applyNextVreraStyle
import com.noyclic.questphonexr.ui.nextVreraToneFor
import com.noyclic.questphonexr.performance.AndroidDeviceProfiler
import com.noyclic.questphonexr.performance.AdaptivePerformanceController
import com.noyclic.questphonexr.performance.AdaptivePerformanceState
import com.noyclic.questphonexr.performance.DevicePerformancePolicy
import com.noyclic.questphonexr.performance.DevicePerformancePreset
import com.noyclic.questphonexr.runtime.EndXrGlSurfaceLifecycle
import com.noyclic.questphonexr.runtime.AndroidRuntimeBrokerContract
import com.noyclic.questphonexr.runtime.AndroidRuntimeBrokerProbe
import com.noyclic.questphonexr.runtime.EndXrRuntimeBridge
import com.noyclic.questphonexr.runtime.EndXrInputSnapshotStore
import com.noyclic.questphonexr.runtime.EndXrHandInputEncoder
import com.noyclic.questphonexr.runtime.EndXrHandTrackingService
import com.noyclic.questphonexr.runtime.EndXrRuntimeState

/** Phone-first 3D sandbox for testing stereo viewing, Joy-Con input and object grabbing. */
class SandboxActivity : ComponentActivity(), SensorEventListener, InputManager.InputDeviceListener {
    private val inputManager by lazy { getSystemService(INPUT_SERVICE) as InputManager }
    private val simulation = SandboxSimulation()
    private val virtualControllerMapper = VirtualControllerMapper()
    @Volatile private var controllerSnapshots: List<PhysicalControllerSnapshot> = emptyList()
    @Volatile private var latestHandFrame = HandTrackingFrame(0L, emptyList())
    @Volatile private var latestHandFrameReceivedAtMs = 0L
    @Volatile private var latestSandboxTrackingMetrics: HandTrackingMetricsSnapshot? = null
    @Volatile private var measuredHandTrackingFps = 0f
    @Volatile private var latestHandPipelineLatencyMs = 0L
    @Volatile private var latestPreprocessingLatencyMs = 0L
    @Volatile private var cameraSourceSizeLabel = "—"
    @Volatile private var mediaPipeInputSizeLabel = "—"
    private var previousHandFrameTimestampMs = 0L
    private var lastPublishedServiceFrameMs = 0L
    private var lastExpiredServiceFrameMs = 0L
    private var handDiagnosticsRunning = false
    private val cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var handTracker: HandLandmarkerController? = null
    @Volatile private var handTrackingStartPending = false
    private var cameraStatusMessage = "Камера рук: запускается"

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: SandboxRenderer
    private val xrSurfaceLifecycle = EndXrGlSurfaceLifecycle()
    private val endXrRuntime = EndXrRuntimeBridge()
    @Volatile private var endXrRuntimeLabel = "ожидает GLES surface"
    @Volatile private var endXrOpenXrTestLabel = "OpenXR probe: ждёт GLES-контекст"
    @Volatile private var endXrOpenXrDiagnosticReport = ""
    @Volatile private var endXrOpenXrDiagnosticPath = ""
    private var endXrOpenXrTestGeneration = -1L
    private var retryOpenXrProbeOnResume = false
    @Volatile private var endXrCompletedPhoneFrames = 0L
    private var endXrAttemptedSurfaceGeneration = -1L
    private var endXrRecoveryAttempts = 0
    private var endXrLastAttemptMs = 0L
    private lateinit var sensorManager: SensorManager
    private lateinit var statusView: TextView
    private var persistentHandTrackingButton: Button? = null
    private lateinit var endXrProbeStatusView: TextView
    private lateinit var viewerProfileStore: ViewerProfileStore
    private lateinit var handCalibrationStore: SandboxHandCalibrationStore
    private lateinit var adaptivePerformance: AdaptivePerformanceController
    private lateinit var handTrackingPresetStore: HandTrackingPresetStore
    private var handTrackingPreset = HandTrackingPreset.BALANCED
    private var handTrackingDelegatePreference = HandTrackingDelegatePreference.GPU_PREFERRED
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
    private var shouldOpenPcBridgeOnStart = false

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
            showCameraPermissionSettingsDialog()
        }
        openPcBridgeDialogIfRequested()
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            startPersistentHandTrackingService()
        } else {
            cameraStatusMessage =
                "Уведомления EndXR отключены · разреши их в настройках Android"
            updateStatus()
            showNotificationPermissionSettingsDialog()
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (isFinishing || !::glView.isInitialized) return

            val nowMs = SystemClock.uptimeMillis()
            val serviceActive = EndXrHandTrackingService.isRunning()
            val currentHandTrackingFps = if (serviceActive) {
                EndXrHandTrackingService.diagnosticFps()
            } else measuredHandTrackingFps
            val currentPipelineLatencyMs = if (serviceActive) {
                EndXrHandTrackingService.latestFrame().pipelineLatencyMs
            } else latestHandPipelineLatencyMs
            val updatedAdaptiveState = adaptivePerformance.observe(
                nowMs = nowMs,
                renderFps = renderer.measuredFps,
                handTrackingFps = currentHandTrackingFps,
                pipelineLatencyMs = currentPipelineLatencyMs
            )
            if (updatedAdaptiveState != adaptivePerformanceState) {
                adaptivePerformanceState = updatedAdaptiveState
                if (serviceActive) {
                    EndXrHandTrackingService.updateInferenceInterval(
                        this@SandboxActivity,
                        updatedAdaptiveState.minimumInferenceIntervalMs
                    )
                } else {
                    handTracker?.updateMinimumInferenceIntervalMs(
                        updatedAdaptiveState.minimumInferenceIntervalMs
                    )
                }
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
                // The foreground service owns CameraX while persistent hand tracking is active.
                // Read its latest MediaPipe output instead of rendering Sandbox-local empty frames.
                val latestHands = if (serviceActive) {
                    EndXrHandTrackingService.latestFrame()
                } else latestHandFrame
                val receivedAtMs = if (serviceActive) {
                    EndXrHandTrackingService.latestFrameReceivedAtMs()
                } else latestHandFrameReceivedAtMs
                val handStaleTimeoutMs = sandboxHandFrameStaleTimeoutMs(currentHandTrackingFps)
                val serviceFrameStale = serviceActive && latestHands.hands.isNotEmpty() &&
                    isSandboxHandFrameStale(nowMs, receivedAtMs, handStaleTimeoutMs)
                val hands = if (latestHands.hands.isNotEmpty() &&
                    isSandboxHandFrameStale(nowMs, receivedAtMs, handStaleTimeoutMs)
                ) {
                    HandTrackingFrame(nowMs, emptyList()).also { emptyFrame ->
                        if (serviceActive) {
                            // Send one empty frame when the last service result expires.
                            if (latestHands.timestampMs != lastExpiredServiceFrameMs) {
                                lastExpiredServiceFrameMs = latestHands.timestampMs
                                trackingSender?.publish(emptyFrame)
                            }
                        } else {
                            latestHandFrame = emptyFrame
                            latestHandFrameReceivedAtMs = 0L
                            trackingSender?.publish(emptyFrame)
                        }
                    }
                } else latestHands
                if (serviceActive && !serviceFrameStale && latestHands.timestampMs > 0L &&
                    latestHands.timestampMs != lastPublishedServiceFrameMs
                ) {
                    lastPublishedServiceFrameMs = latestHands.timestampMs
                    lastExpiredServiceFrameMs = 0L
                    trackingSender?.publish(latestHands)
                }
                publishOpenXrInput(hands, snapshot)
                val cameraToWorldRotation = renderer.cameraToWorldRotation()
                glView.queueEvent {
                    // Use GL-thread time for presentation so interpolation follows actual rendering.
                    val renderTimeMs = SystemClock.uptimeMillis()
                    simulation.update(
                        snapshot,
                        hands,
                        deltaSeconds,
                        cameraToWorldRotation,
                        receivedAtMs,
                        renderTimeMs
                    )
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
        handTrackingPresetStore = HandTrackingPresetStore(this)
        handTrackingPreset = handTrackingPresetStore.load(
            HandTrackingPreset.recommend(performancePreset.tier)
        )
        handTrackingDelegatePreference = handTrackingPresetStore.loadDelegatePreference()
        val trackingPerformancePreset = performancePreset.copy(
            minimumInferenceIntervalMs = handTrackingPreset.minimumInferenceIntervalMs
        )
        adaptivePerformance = AdaptivePerformanceController(
            preset = trackingPerformancePreset,
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
            // EndXR observes the same GLES 3 context that renders the existing phone scene.
            // The current milestone does not yet expose an OpenXR swapchain compositor.
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(
                    gl: javax.microedition.khronos.opengles.GL10?,
                    config: javax.microedition.khronos.egl.EGLConfig?
                ) {
                    stopEndXrRuntime("EndXR Core: создаётся GLES surface")
                    xrSurfaceLifecycle.onGlSurfaceCreated()
                    renderer.onSurfaceCreated(gl, config)
                }

                override fun onSurfaceChanged(
                    gl: javax.microedition.khronos.opengles.GL10?,
                    width: Int,
                    height: Int
                ) {
                    xrSurfaceLifecycle.onGlSurfaceChanged(width, height)
                    renderer.onSurfaceChanged(gl, width, height)
                    ensureEndXrRuntimeStarted()
                    runEndXrOpenXrSelfTestIfNeeded()
                }

                override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
                    xrSurfaceLifecycle.onGlFrame()
                    renderFrameThroughEndXr(gl)
                }
            })
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        buildScreen()
        shouldOpenPcBridgeOnStart = intent.getBooleanExtra(EXTRA_OPEN_PC_BRIDGE, false)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startHandTracking()
            openPcBridgeDialogIfRequested()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Starts the internal EndXR frame clock only after the GLES surface is valid.
     * The Sandbox renderer remains usable as a fallback if native startup fails.
     */
    private fun ensureEndXrRuntimeStarted() {
        if (xrSurfaceLifecycle.state != EndXrGlSurfaceLifecycle.State.READY) return
        val generation = xrSurfaceLifecycle.surfaceGeneration
        val currentState = endXrRuntime.snapshot()?.state ?: EndXrRuntimeState.COLD
        val sameSurface = endXrAttemptedSurfaceGeneration == generation
        if (sameSurface && currentState != EndXrRuntimeState.LOST &&
            currentState != EndXrRuntimeState.COLD
        ) return
        if (sameSurface && endXrRecoveryAttempts >= MAX_ENDXR_START_ATTEMPTS) return
        val nowMs = SystemClock.uptimeMillis()
        if (sameSurface && nowMs - endXrLastAttemptMs < ENDXR_RECOVERY_DELAY_MS) return
        if (!sameSurface) {
            endXrAttemptedSurfaceGeneration = generation
            endXrRecoveryAttempts = 0
        } else {
            endXrRuntime.shutdown()
        }
        endXrRecoveryAttempts += 1
        endXrLastAttemptMs = nowMs
        if (!endXrRuntime.isAvailable) {
            endXrRuntimeLabel = "native library unavailable"
            return
        }

        // Sandbox renders two side-by-side views; runtime dimensions describe one eye.
        val width = (xrSurfaceLifecycle.surfaceWidth / 2).coerceIn(64, 8192)
        val height = xrSurfaceLifecycle.surfaceHeight.coerceIn(64, 8192)
        val targetFps = adaptivePerformanceState.renderFpsCap.coerceIn(10, 120)
        fun fail(stage: String, error: String) {
            endXrRuntime.shutdown()
            endXrRuntimeLabel =
                "$stage: $error · attempt $endXrRecoveryAttempts/$MAX_ENDXR_START_ATTEMPTS"
        }
        endXrRuntime.initialize(width, height, targetFps)?.let {
            fail("initialize", it)
            return
        }
        endXrRuntime.createSession()?.let {
            fail("create session", it)
            return
        }
        endXrRuntime.beginSession()?.let {
            fail("begin session", it)
            return
        }
        endXrCompletedPhoneFrames = 0L
        endXrRuntimeLabel =
            "active · 2 views · ${width}×$height/eye · $targetFps FPS target"
    }

    /** Called exclusively from GLSurfaceView's render thread. */
    /** Runs the loader-facing runtime against the real EGL context on the GL thread. */
    private fun runEndXrOpenXrSelfTestIfNeeded() {
        if (xrSurfaceLifecycle.state != EndXrGlSurfaceLifecycle.State.READY) return
        val generation = xrSurfaceLifecycle.surfaceGeneration
        if (endXrOpenXrTestGeneration == generation) return
        endXrOpenXrTestGeneration = generation
        val nativeReport = endXrRuntime.runOpenXrSelfTest(this)
        persistEndXrOpenXrDiagnostic(nativeReport)
        runOnUiThread { updateStatus() }
    }

    private fun persistEndXrOpenXrDiagnostic(nativeReport: String) {
        val report = buildString {
            append(nativeReport.trimEnd()).append('\n')
            append("---- APP CONTEXT ----\n")
            append(endXrRuntime.buildOpenXrAppDiagnosticContext(this@SandboxActivity))
            val deviceWallClock = java.time.OffsetDateTime.now()
            append("app_device_local_time=").append(deviceWallClock).append('\n')
            append("app_device_timezone=").append(java.time.ZoneId.systemDefault().id).append('\n')
            append("app_device_epoch_ms=").append(System.currentTimeMillis()).append('\n')
            append("app_device_uptime_ms=").append(SystemClock.uptimeMillis()).append('\n')
            append("app_device_elapsed_realtime_ms=").append(SystemClock.elapsedRealtime()).append('\n')
            append("app_surface_generation=").append(xrSurfaceLifecycle.surfaceGeneration).append('\n')
            append("app_surface_state=").append(xrSurfaceLifecycle.state.name).append('\n')
            append("app_surface_width=").append(xrSurfaceLifecycle.surfaceWidth).append('\n')
            append("app_surface_height=").append(xrSurfaceLifecycle.surfaceHeight).append('\n')
            append("app_rotation_sensor_available=").append(sensorAvailable).append('\n')
            append("app_camera_permission_granted=").append(
                ContextCompat.checkSelfPermission(this@SandboxActivity, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            ).append('\n')
            val persistentTrackingActive = EndXrHandTrackingService.isRunning()
            append("app_camera_status=").append(cameraStatusMessage).append('\n')
            append("app_hand_tracking_source=").append(
                when {
                    persistentTrackingActive -> "foreground_service"
                    handTracker != null -> "sandbox_activity"
                    else -> "inactive"
                }
            ).append('\n')
            append("app_hand_tracking_fps=").append(
                if (persistentTrackingActive) EndXrHandTrackingService.diagnosticFps()
                else measuredHandTrackingFps
            ).append('\n')
            append("app_tracked_hands=").append(
                if (persistentTrackingActive) EndXrHandTrackingService.diagnosticHandCount()
                else latestHandFrame.handCount
            ).append('\n')
            append(EndXrHandTrackingService.diagnosticReport())
            val sandboxMetrics = handTracker?.metricsSnapshot() ?: latestSandboxTrackingMetrics
            append("sandbox_camera_frames_received=").append(sandboxMetrics?.receivedFrames ?: -1L).append('\n')
            append("sandbox_mediapipe_frames_submitted=").append(sandboxMetrics?.submittedFrames ?: -1L).append('\n')
            append("sandbox_mediapipe_results_completed=").append(sandboxMetrics?.completedFrames ?: -1L).append('\n')
            append("sandbox_mediapipe_frames_dropped_busy=").append(sandboxMetrics?.droppedBusyFrames ?: -1L).append('\n')
            append("sandbox_mediapipe_frames_dropped_interval=").append(sandboxMetrics?.droppedIntervalFrames ?: -1L).append('\n')
            append("sandbox_camera_frame_size=").append(sandboxMetrics?.lastFrameWidthPx ?: 0)
                .append('x').append(sandboxMetrics?.lastFrameHeightPx ?: 0).append('\n')
            append("sandbox_camera_frame_rotation=").append(sandboxMetrics?.lastFrameRotationDegrees ?: 0).append('\n')
            append("sandbox_camera_luma_mean=").append(sandboxMetrics?.lastFrameLumaMean ?: 0f).append('\n')
            append("sandbox_camera_luma_stddev=").append(sandboxMetrics?.lastFrameLumaStdDev ?: 0f).append('\n')
            appendLine("sandbox_mediapipe_delegate=${sandboxMetrics?.delegateName ?: "unknown"}")
            appendLine("sandbox_mediapipe_delegate_init_thread_id=${sandboxMetrics?.delegateInitThreadId ?: 0L}")
            appendLine("sandbox_mediapipe_analyzer_thread_id=${sandboxMetrics?.lastAnalyzeThreadId ?: 0L}")
            appendLine("sandbox_mediapipe_delegate_thread_matches_analyzer=${sandboxMetrics?.delegateThreadMatchesAnalyzer ?: false}")
            appendLine("sandbox_average_inference_ms=${sandboxMetrics?.averageInferenceMs ?: 0f}")
            appendLine("sandbox_average_result_processing_ms=${sandboxMetrics?.averageResultProcessingMs ?: 0f}")
            append("sandbox_last_preprocessing_ms=").append(sandboxMetrics?.lastPreprocessingMs ?: 0L).append('\n')
            append("sandbox_last_frame_received_age_ms=").append(
                if (latestHandFrameReceivedAtMs > 0L) {
                    (SystemClock.uptimeMillis() - latestHandFrameReceivedAtMs).coerceAtLeast(0L)
                } else -1L
            ).append('\n')
            append("app_connected_gamepads=").append(controllerSnapshots.size).append('\n')
            append("app_endxr_core_status=").append(endXrRuntimeLabel).append('\n')
        }
        val internalFile = File(filesDir, "diagnostics/endxr-openxr-latest.log")
        val externalFile = getExternalFilesDir(null)?.let {
            File(it, "ci-diagnostics/endxr-openxr-latest.log")
        }
        val paths = "app_internal_report_path=" + internalFile.absolutePath + '\n' +
            "app_external_report_path=" + (externalFile?.absolutePath ?: "unavailable") + '\n'
        val completeReport = report + paths
        val errors = mutableListOf<String>()
        runCatching {
            internalFile.parentFile?.mkdirs()
            internalFile.writeText(completeReport)
        }.onFailure { errors += "internal: " + it.javaClass.simpleName + ": " + it.message }
        if (externalFile != null) {
            runCatching {
                externalFile.parentFile?.mkdirs()
                externalFile.writeText(completeReport)
            }.onFailure { errors += "external: " + it.javaClass.simpleName + ": " + it.message }
        }
        endXrOpenXrDiagnosticReport = if (errors.isEmpty()) completeReport else {
            completeReport + "app_report_save_error=" + errors.joinToString(" | ") + '\n'
        }
        endXrOpenXrDiagnosticPath = externalFile?.absolutePath ?: internalFile.absolutePath
        endXrOpenXrTestLabel = nativeReport.lineSequence().firstOrNull()
            ?: "OpenXR probe: FAIL · empty diagnostic result"
        val conciseProbeState = when {
            nativeReport.startsWith("OpenXR probe: PASS") -> "OpenXR probe: PASS"
            nativeReport.startsWith("OpenXR probe: FAIL") -> "OpenXR probe: FAIL"
            else -> "OpenXR probe: NOT READY"
        }
        runOnUiThread {
            if (::endXrProbeStatusView.isInitialized) {
                endXrProbeStatusView.text = conciseProbeState
            }
            updateStatus()
        }
        Log.i("EndXR-DIAG", "OpenXR report saved: " + endXrOpenXrDiagnosticPath)
        if (errors.isNotEmpty()) Log.e("EndXR-DIAG", errors.joinToString(" | "))
    }

    private fun nativeInputRow(state: VirtualControllerState): FloatArray {
        var buttons = 0
        if (GamepadButtonCodes.BUTTON_A in state.pressedKeyCodes) buttons = buttons or (1 shl 0)
        if (GamepadButtonCodes.BUTTON_B in state.pressedKeyCodes) buttons = buttons or (1 shl 1)
        if (GamepadButtonCodes.BUTTON_X in state.pressedKeyCodes) buttons = buttons or (1 shl 2)
        if (GamepadButtonCodes.BUTTON_Y in state.pressedKeyCodes) buttons = buttons or (1 shl 3)
        if (GamepadButtonCodes.BUTTON_START in state.pressedKeyCodes) buttons = buttons or (1 shl 4)
        return floatArrayOf(
            if (state.source != ControllerInputSource.NONE) 1f else 0f,
            state.trigger.coerceIn(0f, 1f), state.grip.coerceIn(0f, 1f),
            state.stickX.coerceIn(-1f, 1f), state.stickY.coerceIn(-1f, 1f),
            buttons.toFloat()
        )
    }

    private fun publishOpenXrInput(
        handFrame: HandTrackingFrame = latestHandFrame,
        controllers: List<PhysicalControllerSnapshot> = controllerSnapshots
    ) {
        val frame = virtualControllerMapper.map(handFrame, controllers)
        val left = nativeInputRow(frame.left)
        val right = nativeInputRow(frame.right)
        val cameraInput = EndXrHandInputEncoder.encode(handFrame)
        endXrRuntime.setControllerInput(left, right)
        EndXrInputSnapshotStore.publish(left, right)
        EndXrInputSnapshotStore.publishHandPoses(cameraInput.leftPose, cameraInput.rightPose)
    }

    private fun launchExternalOpenXrProbe() {
        val probePackage = "com.noyclic.xrclientprobe"
        val launchIntent = packageManager.getLaunchIntentForPackage(probePackage)
        if (launchIntent != null) {
            runCatching { startActivity(launchIntent) }
                .onFailure { error ->
                    Toast.makeText(
                        this,
                        "Не удалось открыть внешний тест: ${error.message ?: "ошибка запуска"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Внешний OpenXR тест не установлен")
            .setMessage(
                "Для независимой проверки нужен отдельный APK EndXR External Client Probe. " +
                    "Скачай QuestPhoneXR-debug-apk и QuestPhoneXR-external-client-probe-apk " +
                    "из одного и того же запуска GitHub Actions и установи оба. " +
                    "Одинаковая подпись нужна диагностическому клиенту для проверки защищённого IPC."
            )
            .setPositiveButton("Открыть GitHub Actions") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/Noyclic/QuestPhoneXr/actions")
                        )
                    )
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        "Не удалось открыть GitHub Actions: ${error.message ?: "ошибка"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showRuntimeBrokerDiagnostic() {
        Toast.makeText(this, "Проверяем OpenXR RuntimeBroker…", Toast.LENGTH_SHORT).show()
        Thread({
            val report = AndroidRuntimeBrokerProbe.inspect(this).toDisplayText()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                AlertDialog.Builder(this)
                    .setTitle("Диагностика OpenXR RuntimeBroker")
                    .setMessage(report)
                    .setPositiveButton("Копировать отчёт") { _, _ ->
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("EndXR RuntimeBroker diagnostics", report)
                        )
                        Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Закрыть", null)
                    .show()
            }
        }, "EndXR-Broker-Diagnostics").start()
    }

    private fun openOpenXrRuntimeBroker() {
        val brokerPackage = AndroidRuntimeBrokerContract.INSTALLABLE_BROKER_PACKAGE
        val storeUrl = AndroidRuntimeBrokerContract.INSTALLABLE_BROKER_PLAY_STORE_URL
        val brokerIntent = Intent().setClassName(
            brokerPackage,
            AndroidRuntimeBrokerContract.INSTALLABLE_BROKER_MAIN_ACTIVITY
        )
        retryOpenXrProbeOnResume = true
        runCatching {
            startActivity(brokerIntent)
            Toast.makeText(
                this,
                "В Runtime Broker выбери «EndXR OpenXR Runtime», затем вернись сюда.",
                Toast.LENGTH_LONG
            ).show()
        }.onSuccess {
            return
        }.onFailure { launchError ->
            retryOpenXrProbeOnResume = false
            Log.i(
                "EndXR-RuntimeBroker",
                "Khronos Broker activity is not available; offering official store listing.",
                launchError
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Нужен OpenXR Runtime Broker")
            .setMessage(
                "Официальный Khronos OpenXR Loader не может выбрать EndXR, пока на этом Android-устройстве нет Runtime Broker. Установи «OpenXR Runtime Broker» от The Khronos Group, открой его и затем вернись в QuestPhoneXR. Сам Broker не содержит VR-движок — он только выбирает установленный Runtime."
            )
            .setPositiveButton("Открыть Google Play") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$brokerPackage")))
                }.onFailure {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(storeUrl)))
                    }.onFailure { error ->
                        Toast.makeText(
                            this,
                            "Не удалось открыть магазин: ${error.message ?: "нет приложения для ссылок"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNeutralButton("Скопировать ссылку") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OpenXR Runtime Broker", storeUrl))
                Toast.makeText(this, "Ссылка скопирована.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun showOpenXrInputDiagnostic() {
        val frame = virtualControllerMapper.map(latestHandFrame, controllerSnapshots)
        fun describe(label: String, state: VirtualControllerState): String = buildString {
            append(label).append(": ").append(state.source.name).append('\n')
            append("  trigger=").append(String.format(Locale.US, "%.2f", state.trigger))
            append(" · grip=").append(String.format(Locale.US, "%.2f", state.grip)).append('\n')
            append("  stick=(").append(String.format(Locale.US, "%.2f", state.stickX))
                .append(", ").append(String.format(Locale.US, "%.2f", state.stickY)).append(")")
                .append(" · pinch=").append(state.pinchPressed)
        }
        publishOpenXrInput()
        AlertDialog.Builder(this)
            .setTitle("Проверка OpenXR ввода")
            .setMessage(
                describe("Левая рука", frame.left) + "\n\n" +
                    describe("Правая рука", frame.right) +
                    "\n\nДанные переданы через JNI в EndXR внутри процесса Sandbox. " +
                    "Это не подтверждает системную передачу ввода сторонним APK."
            )
            .setPositiveButton("ОК", null)
            .show()
    }

    private fun copyEndXrOpenXrDiagnostic() {
        val report = endXrOpenXrDiagnosticReport
        if (report.isBlank()) {
            Toast.makeText(this, "Диагностика ещё не запускалась", Toast.LENGTH_SHORT).show()
            return
        }
        Log.i(
            "EndXR-DIAG",
            "Copy requested: chars=${report.length}, schema4=${report.contains("diagnostic_schema=4")}"
        )
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("EndXR runtime diagnostics", report))
        Log.i("EndXR-DIAG", "Clipboard setPrimaryClip completed")
        Toast.makeText(this, "Отчёт скопирован · " + endXrOpenXrDiagnosticPath, Toast.LENGTH_LONG).show()
    }

    private fun renderFrameThroughEndXr(gl: javax.microedition.khronos.opengles.GL10?) {
        ensureEndXrRuntimeStarted()
        val isRunning = endXrRuntime.snapshot()?.state == EndXrRuntimeState.RUNNING
        val waitResult = if (isRunning) {
            endXrRuntime.waitFrame(System.nanoTime())
        } else {
            null
        }

        // Keep the phone Sandbox rendering even if the internal runtime clock fails.
        renderer.onDrawFrame(gl)

        if (waitResult == null) return
        val timing = waitResult.timing
        if (timing == null) {
            val error = waitResult.error ?: "waitFrame failed without an error message"
            endXrRuntime.markLost(error)
            endXrRuntimeLabel = "lost · $error"
            return
        }

        val endError = endXrRuntime.endFrame(timing, renderer.lastFrameCompleted)
        if (endError != null) {
            endXrRuntime.markLost(endError)
            endXrRuntimeLabel = "lost · $endError"
            return
        }

        if (renderer.lastFrameCompleted) {
            endXrCompletedPhoneFrames =
                endXrRuntime.snapshot()?.completedPhoneFrames ?: endXrCompletedPhoneFrames
        }
    }

    /** Tears down only the app-owned lifecycle core; no XR driver or Binder is involved. */
    private fun stopEndXrRuntime(status: String = "остановлен") {
        when (endXrRuntime.snapshot()?.state) {
            EndXrRuntimeState.RUNNING,
            EndXrRuntimeState.SESSION_READY -> {
                endXrRuntime.requestStop()
                endXrRuntime.destroySession()
            }
            EndXrRuntimeState.STOPPING -> endXrRuntime.destroySession()
            else -> Unit
        }
        endXrRuntime.shutdown()
        endXrAttemptedSurfaceGeneration = -1L
        endXrRecoveryAttempts = 0
        endXrLastAttemptMs = 0L
        endXrOpenXrTestGeneration = -1L
        endXrRuntimeLabel = status
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
            setBackgroundColor(0xE60C111B.toInt())
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView)

            val buttons = LinearLayout(this@SandboxActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            buttons.addView(
                controlButton("Игры") { showGameSelectionDialog() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
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
            val frontCameraSwitch = Switch(this@SandboxActivity).apply {
                text = "Разрешить фронтальную камеру"
                isChecked = HandCameraPreference.isFrontCameraEnabled(this@SandboxActivity)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnCheckedChangeListener { _, enabled ->
                    val wasTracking = handTracker != null ||
                        handTrackingStartPending || EndXrHandTrackingService.isRunning()
                    HandCameraPreference.setFrontCameraEnabled(this@SandboxActivity, enabled)
                    cameraStatusMessage = if (enabled) {
                        "Фронтальная камера разрешена вручную · перезапускаем отслеживание"
                    } else {
                        "Фронтальная камера запрещена · используется только задняя"
                    }
                    if (wasTracking) {
                        restartHandTracking()
                    } else {
                        updateStatus()
                    }
                }
            }
            addView(
                frontCameraSwitch,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                controlButton("Профиль MediaPipe") { showHandTrackingPresetDialog() },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                controlButton("Движок MediaPipe: GPU / CPU") { showHandTrackingDelegateDialog() },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                controlButton("Диагностика рук (4 с)") { runHandDiagnostics() },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            persistentHandTrackingButton = controlButton(
                "Включить фоновую камеру рук для Quest-игр"
            ) {
                togglePersistentHandTracking()
            }.apply {
                contentDescription = "Включить фоновую камеру рук для внешних XR-игр"
            }
            addView(
                persistentHandTrackingButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val settingsScroll = android.widget.ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            addView(
                scrollContent,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        topPanel.addView(
            settingsScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val panelMaxHeight = (resources.displayMetrics.heightPixels * 0.82f)
            .toInt()
            .coerceAtLeast(dp(180))
        root.addView(
            topPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                panelMaxHeight,
                Gravity.TOP
            )
        )

        // Keep the probe result in a short, dedicated accessibility node. The
        // full status panel is intentionally verbose and can be clipped by UiAutomator.
        endXrProbeStatusView = TextView(this).apply {
            text = "OpenXR probe: waiting"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(0xCC101923.toInt())
            contentDescription = "EndXR OpenXR probe result"
        }
        root.addView(
            endXrProbeStatusView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                bottomMargin = dp(66)
                marginStart = dp(12)
            }
        )

        // This action stays available even when the settings panel is hidden.
        val diagnosticsButton = controlButton("Диагностика EndXR") {
            copyEndXrOpenXrDiagnostic()
        }.apply {
            textSize = 13f
            contentDescription = "Скопировать диагностику EndXR"
            setPadding(dp(12), dp(5), dp(12), dp(5))
            minHeight = dp(42)
            elevation = dp(6).toFloat()
        }
        root.addView(
            diagnosticsButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                bottomMargin = dp(14)
                marginStart = dp(12)
            }
        )
        val inputDiagnosticsButton = controlButton("Проверить OpenXR ввод") {
            showOpenXrInputDiagnostic()
        }.apply {
            textSize = 13f
            contentDescription = "Проверить передачу ввода в EndXR"
            setPadding(dp(12), dp(5), dp(12), dp(5))
            minHeight = dp(40)
            elevation = dp(6).toFloat()
        }
        root.addView(
            inputDiagnosticsButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                bottomMargin = dp(62)
                marginStart = dp(12)
            }
        )

        val externalProbeButton = controlButton("Внешний OpenXR тест") {
            launchExternalOpenXrProbe()
        }.apply {
            textSize = 13f
            contentDescription = "Запустить отдельное приложение для проверки OpenXR Loader и EndXR"
            setPadding(dp(12), dp(5), dp(12), dp(5))
            minHeight = dp(40)
            elevation = dp(6).toFloat()
        }
        root.addView(
            externalProbeButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                bottomMargin = dp(158)
                marginEnd = dp(12)
            }
        )

        val brokerDiagnosticsButton = controlButton("Диагностика Broker") {
            showRuntimeBrokerDiagnostic()
        }.apply {
            textSize = 13f
            contentDescription = "Проверить наличие RuntimeBroker и выбранный OpenXR runtime"
            setPadding(dp(12), dp(5), dp(12), dp(5))
            minHeight = dp(40)
            elevation = dp(6).toFloat()
        }
        root.addView(
            brokerDiagnosticsButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                bottomMargin = dp(110)
                marginEnd = dp(12)
            }
        )

        val runtimeBrokerButton = controlButton("Выбор OpenXR Runtime") {
            openOpenXrRuntimeBroker()
        }.apply {
            textSize = 13f
            contentDescription = "Открыть Khronos OpenXR Runtime Broker"
            setPadding(dp(12), dp(5), dp(12), dp(5))
            minHeight = dp(40)
            elevation = dp(6).toFloat()
        }
        root.addView(
            runtimeBrokerButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                bottomMargin = dp(62)
                marginEnd = dp(12)
            }
        )

        // Keep a small always-visible toggle so the scene can be used without UI clutter.
        lateinit var settingsToggle: Button
        settingsToggle = controlButton("Скрыть настройки") {
            val willShow = topPanel.visibility != View.VISIBLE
            topPanel.visibility = if (willShow) View.VISIBLE else View.GONE
            settingsToggle.text = if (willShow) "Скрыть настройки" else "Показать настройки"
        }
        root.addView(
            settingsToggle,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                bottomMargin = dp(14)
                marginEnd = dp(12)
            }
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
        if (isFinishing || handTracker != null || handTrackingStartPending ||
            EndXrHandTrackingService.isRunning()
        ) return

        handTrackingStartPending = true
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isFinishing || EndXrHandTrackingService.isRunning()) {
                handTrackingStartPending = false
                return@addListener
            }
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                // Rear camera is the default. Selfie camera is opt-in through the settings switch.
                val cameraSelection = HandCameraPreference.resolve(this@SandboxActivity, provider)
                val useFrontCamera = cameraSelection.isFrontCamera
                val selector = cameraSelection.selector

                // The GPU delegate is thread-affine: create it on the same single-thread
                // executor that CameraX uses for analyze()/detectAsync().
                val tracker = cameraExecutor.submit<HandLandmarkerController> {
                    HandLandmarkerController(
                        context = applicationContext,
                        mirrorFrontCamera = useFrontCamera,
                        onFrame = ::onHandFrame,
                        onError = { message ->
                            runOnUiThread {
                                cameraStatusMessage = "MediaPipe: " + message
                                updateStatus()
                            }
                        },
                        maxHands = handTrackingPreset.maxTrackedHands,
                        minimumInferenceIntervalMs = adaptivePerformanceState.minimumInferenceIntervalMs,
                        // The selected preset can use a smaller model bitmap than the
                        // CameraX source to trade landmark detail for inference workload.
                        outputWidthPx = minOf(effectiveCameraWidthPx(), handTrackingPreset.modelInputWidthPx),
                        outputHeightPx = minOf(effectiveCameraHeightPx(), handTrackingPreset.modelInputHeightPx),
                        onMetrics = { snapshot -> latestSandboxTrackingMetrics = snapshot },
                        preferredDelegate = handTrackingDelegatePreference.delegate,
                        ownerExecutor = cameraExecutor
                    )
                }.get()
                handTracker = tracker
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(
                        android.util.Size(effectiveCameraWidthPx(), effectiveCameraHeightPx())
                    )
                    // CameraX must know the display orientation; otherwise rotationDegrees
                    // can stay at zero while the physical phone is turned.
                    .setTargetRotation(glView.display?.rotation ?: Surface.ROTATION_0)
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
                handTrackingStartPending = false
                cameraStatusMessage = if (useFrontCamera) {
                    "MediaPipe: фронтальная камера включена вручную · жест pinch включён"
                } else {
                    "MediaPipe: задняя камера · фронтальная выключена · жест pinch включён"
                }
                updateStatus()
            } catch (error: Exception) {
                handTrackingStartPending = false
                handTracker?.close()
                handTracker = null
                cameraStatusMessage = "Не удалось запустить камеру рук: " +
                    (error.message ?: "неизвестная ошибка")
                updateStatus()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun togglePersistentHandTracking() {
        if (EndXrHandTrackingService.isRunning()) {
            stopService(Intent(this, EndXrHandTrackingService::class.java))
            cameraStatusMessage = "Останавливаем фоновое отслеживание рук…"
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !EndXrHandTrackingService.isRunning()) {
                    startHandTracking()
                }
            }, 500L)
            updateStatus()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraStatusMessage = "Сначала разреши доступ к камере, затем снова нажми кнопку."
            updateStatus()
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }

        // Android 13+ requires a runtime grant before the ongoing tracking notification
        // can be shown in the notification drawer. Older versions can still have
        // app/channel notifications disabled manually, so check that as well.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            cameraStatusMessage =
                "Уведомления EndXR отключены · включи их в настройках Android"
            updateStatus()
            showNotificationPermissionSettingsDialog()
            return
        }

        startPersistentHandTrackingService()
    }

    private fun startPersistentHandTrackingService() {
        if (EndXrHandTrackingService.isRunning()) return

        // Transfer CameraX ownership while the Activity is still visible.
        cameraProvider?.unbindAll()
        handTracker?.close()
        handTracker = null
        handTrackingStartPending = false
        EndXrInputSnapshotStore.clear()

        val serviceIntent = EndXrHandTrackingService.createStartIntent(
            this,
            effectiveCameraWidthPx(),
            effectiveCameraHeightPx(),
            handTrackingPreset.maxTrackedHands,
            adaptivePerformanceState.minimumInferenceIntervalMs,
            minOf(effectiveCameraWidthPx(), handTrackingPreset.modelInputWidthPx),
            minOf(effectiveCameraHeightPx(), handTrackingPreset.modelInputHeightPx),
            preferGpuDelegate = handTrackingDelegatePreference.delegate == Delegate.GPU
        )
        runCatching {
            ContextCompat.startForegroundService(this, serviceIntent)
            cameraStatusMessage =
                "EndXR: фоновая камера рук запущена · уведомление должно оставаться активным"
        }.onFailure { error ->
            EndXrHandTrackingService.markStartFailed(
                error.message ?: error.javaClass.simpleName
            )
            cameraStatusMessage = "Не удалось запустить фоновую камеру: " +
                (error.message ?: error.javaClass.simpleName)
            startHandTracking()
        }
        updateStatus()
    }

    private fun showNotificationPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Разреши уведомления EndXR")
            .setMessage(
                "Android скрывает уведомление фонового отслеживания, пока уведомления " +
                    "для QuestPhoneXR запрещены. Открой настройки приложения и включи " +
                    "«Разрешить уведомления», затем снова запусти фоновую камеру рук."
            )
            .setPositiveButton("Открыть настройки") { _, _ ->
                val settingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                runCatching { startActivity(settingsIntent) }.onFailure {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                }
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    // Keep enough source detail for reliable palm/landmark detection. The phone
    // diagnostics path that successfully detects hands uses 640x480; the old 320x240
    // Sandbox stream frequently missed frontal and partially rotated hands.
    private fun effectiveCameraWidthPx(): Int =
        maxOf(
            MIN_HAND_TRACKING_CAMERA_WIDTH_PX,
            if (viewerProfile.cameraWidthPx == ViewerProfile.AUTO_CAMERA_SIZE) {
                handTrackingPreset.cameraWidthPx
            } else viewerProfile.cameraWidthPx
        )

    private fun effectiveCameraHeightPx(): Int =
        maxOf(
            MIN_HAND_TRACKING_CAMERA_HEIGHT_PX,
            if (viewerProfile.cameraHeightPx == ViewerProfile.AUTO_CAMERA_SIZE) {
                handTrackingPreset.cameraHeightPx
            } else viewerProfile.cameraHeightPx
        )

    private fun currentHandMetrics(): HandTrackingMetricsSnapshot? =
        if (EndXrHandTrackingService.isRunning()) EndXrHandTrackingService.diagnosticMetrics()
        else handTracker?.metricsSnapshot() ?: latestSandboxTrackingMetrics

    private fun currentDiagnosticHandFrame(): HandTrackingFrame =
        if (EndXrHandTrackingService.isRunning()) EndXrHandTrackingService.latestFrame()
        else latestHandFrame

    private fun handTrackingOwner() = when {
        EndXrHandTrackingService.isRunning() -> HandTrackingOwner.SERVICE
        handTracker != null -> HandTrackingOwner.SANDBOX
        else -> HandTrackingOwner.NONE
    }

    private fun bundledModelBytes(): Long = runCatching {
        assets.open("hand_landmarker.task").use { stream ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        }
    }.getOrDefault(-1L)

    private fun runHandDiagnostics() {
        if (isFinishing || isDestroyed || handDiagnosticsRunning) return
        handDiagnosticsRunning = true
        val permissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (permissionGranted &&
            handTrackingOwner() == HandTrackingOwner.NONE && !handTrackingStartPending
        ) {
            startHandTracking()
        }

        Toast.makeText(
            this, "Проверка рук: покажи ладони камере",
            Toast.LENGTH_LONG
        ).show()

        val handler = Handler(Looper.getMainLooper())
        // CameraX startup is asynchronous. Wait for the first frame from the current
        // owner before taking the baseline, otherwise an early tap can compare null
        // metrics with a live tracker and report a false "tracker not running" error.
        val cameraReadyDeadlineMs = SystemClock.uptimeMillis() + 8_000L
        val awaitCameraReady = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) {
                    handDiagnosticsRunning = false
                    return
                }

                val owner = handTrackingOwner()
                val metrics = currentHandMetrics()
                val hasCurrentCameraFrames = owner != HandTrackingOwner.NONE &&
                    (metrics?.receivedFrames ?: 0L) > 0L
                val status = cameraStatusMessage
                val startupFailed = status.startsWith("Не удалось") ||
                    (status.startsWith("MediaPipe: ") && !status.contains("pinch включён"))
                if (permissionGranted && !hasCurrentCameraFrames && !startupFailed &&
                    SystemClock.uptimeMillis() < cameraReadyDeadlineMs
                ) {
                    handler.postDelayed(this, 150L)
                    return
                }

                val before = metrics
                val baselineAtMs = SystemClock.uptimeMillis()
                var maxHands = currentDiagnosticHandFrame().handCount
                val sampler = object : Runnable {
                    override fun run() {
                        if (isFinishing || isDestroyed) {
                            handDiagnosticsRunning = false
                            return
                        }
                        maxHands = maxOf(maxHands, currentDiagnosticHandFrame().handCount)
                        if (SystemClock.uptimeMillis() - baselineAtMs < 4_000L) {
                            handler.postDelayed(this, 200L)
                        } else {
                            finishHandDiagnostics(permissionGranted, before, maxHands, baselineAtMs)
                        }
                    }
                }
                handler.post(sampler)
            }
        }
        handler.post(awaitCameraReady)
    }

    private fun finishHandDiagnostics(
        permissionGranted: Boolean,
        before: HandTrackingMetricsSnapshot?,
        maxHands: Int,
        baselineAtMs: Long
    ) {
        handDiagnosticsRunning = false
        if (isFinishing || isDestroyed) return
        val owner = handTrackingOwner()
        val status = cameraStatusMessage
        val sandboxFailure = owner != HandTrackingOwner.SERVICE &&
            (status.startsWith("Не удалось") ||
                (status.startsWith("MediaPipe: ") && !status.contains("pinch включён")))
        val lastError = when {
            owner == HandTrackingOwner.SERVICE -> EndXrHandTrackingService.diagnosticLastError()
            sandboxFailure -> status
            else -> ""
        }
        val report = HandTrackingDiagnostician.diagnose(
            HandDiagnosisInput(
                cameraPermissionGranted = permissionGranted,
                modelAssetBytes = bundledModelBytes(),
                hasAnyCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
                hasFrontCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT),
                owner = owner,
                windowMs = (SystemClock.uptimeMillis() - baselineAtMs).coerceAtLeast(1L),
                before = before,
                after = currentHandMetrics(),
                maxHandsSeen = maxHands,
                lastPipelineLatencyMs = currentDiagnosticHandFrame().pipelineLatencyMs,
                inferenceIntervalMs = adaptivePerformanceState.minimumInferenceIntervalMs,
                lastError = lastError,
                frontCameraEnabled = HandCameraPreference.isFrontCameraEnabled(this)
            )
        )
        val reportText = report.render() + "\n— Статус камеры: " + status +
            "\n— Устройство: " + android.os.Build.MANUFACTURER + " " +
            android.os.Build.MODEL + " · Android " +
            android.os.Build.VERSION.RELEASE + "\n"
        Log.i("QuestPhoneXR.HandDiag", reportText)

        val reportView = TextView(this).apply {
            text = reportText
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Диагностика рук")
            .setView(android.widget.ScrollView(this).apply { addView(reportView) })
            .setNegativeButton("Закрыть", null)
            .setNeutralButton("Повторить") { _, _ -> runHandDiagnostics() }
            .setPositiveButton("Копировать") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Hand tracking diagnosis", reportText))
                Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showCameraPermissionSettingsDialog() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Нужен доступ к камере")
            .setMessage(
                "Без камеры отслеживание рук не работает. Открой настройки приложения → " +
                    "Разрешения → Камера → Разрешить, затем вернись сюда."
            )
            .setNegativeButton("Позже", null)
            .setPositiveButton("Открыть настройки") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)
                        )
                    )
                }
            }
            .show()
    }

    private fun showHandTrackingPresetDialog() {
        val presets = HandTrackingPreset.entries.toTypedArray()
        var selectedIndex = presets.indexOf(handTrackingPreset).coerceAtLeast(0)
        val labels = presets.map { "${it.label} — ${it.description}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Профиль MediaPipe")
            .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Применить") { _, _ ->
                val selected = presets[selectedIndex]
                if (selected != handTrackingPreset) {
                    handTrackingPreset = selected
                    handTrackingPresetStore.save(selected)
                    val trackingPerformancePreset = performancePreset.copy(
                        minimumInferenceIntervalMs = selected.minimumInferenceIntervalMs
                    )
                    adaptivePerformance = AdaptivePerformanceController(
                        preset = trackingPerformancePreset,
                        userRequestedFps = viewerProfile.targetFps
                    )
                    adaptivePerformanceState = adaptivePerformance.currentState()
                    restartHandTracking()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showHandTrackingDelegateDialog() {
        val preferences = HandTrackingDelegatePreference.entries.toTypedArray()
        var selectedIndex = preferences.indexOf(handTrackingDelegatePreference).coerceAtLeast(0)
        val labels = preferences.map { it.label }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Движок MediaPipe")
            .setMessage(
                "GPU может ускорить обработку, но результат зависит от драйвера Adreno. " +
                    "Режим GPU автоматически использует CPU, если GPU-делегат не запускается. " +
                    "Режим CPU позволяет сравнить производительность на том же телефоне."
            )
            .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Применить") { _, _ ->
                val selected = preferences[selectedIndex]
                if (selected != handTrackingDelegatePreference) {
                    handTrackingDelegatePreference = selected
                    handTrackingPresetStore.saveDelegatePreference(selected)
                    val wasTracking = handTracker != null ||
                        handTrackingStartPending || EndXrHandTrackingService.isRunning()
                    if (wasTracking) {
                        restartHandTracking()
                    } else {
                        updateStatus()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

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
        if (EndXrHandTrackingService.isRunning()) {
            val intent = EndXrHandTrackingService.createStartIntent(
                this,
                effectiveCameraWidthPx(),
                effectiveCameraHeightPx(),
                handTrackingPreset.maxTrackedHands,
                adaptivePerformanceState.minimumInferenceIntervalMs,
                minOf(effectiveCameraWidthPx(), handTrackingPreset.modelInputWidthPx),
                minOf(effectiveCameraHeightPx(), handTrackingPreset.modelInputHeightPx),
                preferGpuDelegate = handTrackingDelegatePreference.delegate == Delegate.GPU
            )
            runCatching {
                ContextCompat.startForegroundService(this, intent)
                cameraStatusMessage = "EndXR: обновляем параметры фоновой камеры…"
            }.onFailure { error ->
                EndXrHandTrackingService.markStartFailed(
                error.message ?: error.javaClass.simpleName
            )
                cameraStatusMessage = "Не удалось обновить фоновую камеру: " +
                    (error.message ?: error.javaClass.simpleName)
            }
            updateStatus()
            return
        }
        cameraProvider?.unbindAll()
        handTracker?.close()
        handTracker = null
        latestHandFrame = HandTrackingFrame(SystemClock.uptimeMillis(), emptyList())
        latestHandFrameReceivedAtMs = 0L
        latestSandboxTrackingMetrics = null
        latestPreprocessingLatencyMs = 0L
        latestHandPipelineLatencyMs = 0L
        measuredHandTrackingFps = 0f
        previousHandFrameTimestampMs = 0L
        cameraStatusMessage = "Камера: применяем новые параметры…"
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
        latestPreprocessingLatencyMs = frame.preprocessingLatencyMs
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
        // Use callback arrival time for freshness; frame.timestampMs is captured before
        // asynchronous inference and can already be old when MediaPipe returns it.
        latestHandFrameReceivedAtMs = SystemClock.uptimeMillis()
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

    private fun openPcBridgeDialogIfRequested() {
        if (!shouldOpenPcBridgeOnStart) return
        shouldOpenPcBridgeOnStart = false
        window.decorView.post {
            if (!isFinishing && !isDestroyed) showPcBridgeDialog()
        }
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

    /**
     * The game picker is intentionally an in-scene dialog: choosing a mode must not
     * send the user back to the app's top-level Sandbox / APK / PC mode selector.
     * Only the physics sandbox is wired to a playable scene at this stage.
     */
    private fun showGameSelectionDialog() {
        val games = arrayOf(
            "Физическая песочница · доступна",
            "Тактический шутер с ботами · скоро",
            "Тир и тренировка меткости · скоро",
            "Выживание с волнами ботов · скоро",
            "Паркур и испытания · скоро",
            "Арена роботов · скоро"
        )
        AlertDialog.Builder(this)
            .setTitle("Выбор игры")
            .setItems(games) { _, which ->
                if (which == 0) {
                    Toast.makeText(this, "Вы уже в физической песочнице", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        "Этот режим появится после стабилизации EndXR runtime",
                        Toast.LENGTH_LONG
                    ).show()
                }
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
        val leftTrackedHand = latestHandFrame.hands.firstOrNull {
            it.side == com.noyclic.questphonexr.tracking.HandSide.LEFT
        }
        val rightTrackedHand = latestHandFrame.hands.firstOrNull {
            it.side == com.noyclic.questphonexr.tracking.HandSide.RIGHT
        }
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
        val inferenceIntervalMs = adaptivePerformanceState.minimumInferenceIntervalMs
        val inferenceRateLabel = if (inferenceIntervalMs == 0L) {
            "без искусственного ограничения"
        } else {
            "до ${1000L / inferenceIntervalMs} кадров/с"
        }

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
            append(" · ").append(handTrackingPreset.maxTrackedHands).append(" рук · MediaPipe ")
                .append(inferenceRateLabel)
            append('\n')
            append(cameraStatusMessage).append(" · рук: ").append(latestHandFrame.handCount)
            append('\n')
            append("Pinch L: ").append(pinchDiagnostic(leftTrackedHand))
            append(" · R: ").append(pinchDiagnostic(rightTrackedHand))
            append('\n')
            val serviceRunning = EndXrHandTrackingService.isRunning()
            val serviceState = EndXrHandTrackingService.diagnosticState()
            append("Фоновая камера EndXR: ")
            append(
                when {
                    serviceRunning && serviceState == "running" -> "РАБОТАЕТ"
                    serviceRunning -> "ЗАПУСКАЕТСЯ ($serviceState)"
                    serviceState == "error" -> "ОШИБКА · смотри service_last_error"
                    else -> "ВЫКЛЮЧЕНА"
                }
            )
            append('\n')
            val trackerMetrics = if (serviceRunning) {
                EndXrHandTrackingService.diagnosticMetrics()
            } else {
                handTracker?.metricsSnapshot() ?: latestSandboxTrackingMetrics
            }
            append("MediaPipe delegate: ").append(trackerMetrics?.delegateName ?: "ожидание")
            append(" · выбор: ").append(handTrackingDelegatePreference.label)
            append('\n')
            append("MediaPipe: ").append(handFpsText)
            append(" FPS · подготовка ").append(latestPreprocessingLatencyMs)
            append(" мс · pipeline ").append(latestHandPipelineLatencyMs)
            append(" мс · пропущено ").append(trackerMetrics?.droppedFrames ?: 0L)
            append(" · камера ").append(cameraSourceSizeLabel)
            append(" → модель ").append(mediaPipeInputSizeLabel)
            append('\n')
            append(pcBridgeStatus).append('\n')
            append("EndXR GL: ").append(xrSurfaceLifecycle.state.name)
            append(" · surface #").append(xrSurfaceLifecycle.surfaceGeneration)
            append(" · ").append(xrSurfaceLifecycle.surfaceWidth).append('×')
                .append(xrSurfaceLifecycle.surfaceHeight)
            append(" · core ").append(endXrRuntimeLabel)
            append(" · phone frames ").append(endXrCompletedPhoneFrames)
            endXrRuntime.snapshot()?.let { runtime ->
                append('\n')
                append("EndXR frames: ").append(runtime.framesStarted)
                append(" · sent ").append(runtime.submittedFrames)
                append(" · dropped ").append(runtime.droppedFrames)
                append(" · late ").append(runtime.lateFrames)
                append(" · lateness ").append(runtime.lastLatenessNanos / 1_000_000L)
                    .append(" ms")
            }
            append('\n').append(endXrOpenXrTestLabel).append('\n')
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

    private fun pinchDiagnostic(hand: com.noyclic.questphonexr.tracking.TrackedHand?): String {
        if (hand == null) return "—"
        val percent = (hand.pinchStrength * 100f).toInt().coerceIn(0, 100)
        val ratio = hand.pinchDistanceRatio.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
        return "$percent% · разрыв $ratio× · " + if (hand.isPinching) "ON" else "off"
    }

    private fun updateStatus() {
        if (::statusView.isInitialized) statusView.text = statusText()

        val running = EndXrHandTrackingService.isRunning()
        val state = EndXrHandTrackingService.diagnosticState()
        val label = when {
            running && state == "running" -> "Остановить фоновую камеру рук · работает"
            running -> "Остановить фоновую камеру рук · запускается"
            else -> "Включить фоновую камеру рук для Quest-игр"
        }
        persistentHandTrackingButton?.let { button ->
            if (button.text.toString() != label) {
                button.text = label
                button.applyNextVreraStyle(nextVreraToneFor(label))
            }
            button.contentDescription = label
        }
    }

    private fun controlButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            applyNextVreraStyle(nextVreraToneFor(label))
            setOnClickListener { action() }
        }

    override fun onResume() {
        super.onResume()
        if (::glView.isInitialized) {
            glView.onResume()
            val retryOpenXrProbe = retryOpenXrProbeOnResume
            retryOpenXrProbeOnResume = false
            glView.queueEvent {
                if (retryOpenXrProbe) endXrOpenXrTestGeneration = -1L
                xrSurfaceLifecycle.onActivityResumed()
                if (retryOpenXrProbe) runEndXrOpenXrSelfTestIfNeeded()
            }
            glView.requestRender()
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
        if (EndXrHandTrackingService.isRunning()) {
            cameraStatusMessage = "EndXR: фоновое отслеживание рук активно"
        } else if (handTracker == null && !handTrackingStartPending &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            startHandTracking()
        }
        applyImmersiveMode()
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
        inputManager.unregisterInputDeviceListener(this)
        endXrRuntime.clearControllerInput()
        if (!EndXrHandTrackingService.isRunning()) {
            EndXrInputSnapshotStore.clear()
        }
        if (::glView.isInitialized) {
            glView.queueEvent {
                xrSurfaceLifecycle.onActivityPaused()
                stopEndXrRuntime("приостановлен")
            }
            glView.requestRender()
            glView.onPause()
        }
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
            val quaternion = FloatArray(4)
            SensorManager.getQuaternionFromVector(quaternion, event.values)
            endXrRuntime.setHeadOrientation(
                quaternion[1], quaternion[2], quaternion[3], quaternion[0]
            )
            EndXrInputSnapshotStore.publishHeadOrientation(
                quaternion[1], quaternion[2], quaternion[3], quaternion[0]
            )
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
        if (!EndXrHandTrackingService.isRunning()) {
            cameraProvider?.unbindAll()
            handTracker?.close()
        }
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val MIN_HAND_TRACKING_CAMERA_WIDTH_PX = 640
        private const val MIN_HAND_TRACKING_CAMERA_HEIGHT_PX = 480
        const val EXTRA_OPEN_PC_BRIDGE = "com.noyclic.questphonexr.OPEN_PC_BRIDGE"
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val MAX_PHYSICS_STEP_SECONDS = 0.05f
        private const val MODEL_INPUT_WIDTH_PX = 480
        private const val MODEL_INPUT_HEIGHT_PX = 360
        private const val STATUS_REFRESH_INTERVAL_MS = 250L
        private const val HAND_FRAME_STALE_MS = 500L
        private const val ENDXR_RECOVERY_DELAY_MS = 1_500L
        private const val MAX_ENDXR_START_ATTEMPTS = 3
        private const val PC_BRIDGE_PREFERENCES = "pc_tracking_bridge"
        private const val KEY_PC_HOST = "host"
    }
}
