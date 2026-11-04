package com.noyclic.questphonexr.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.noyclic.questphonexr.tracking.HandLandmarkerController
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.tracking.HandTrackingMetricsSnapshot
import java.util.concurrent.Executors


/**
 * Process-local diagnostics for the persistent camera pipeline.
 * The Sandbox Activity can read this while the foreground service owns CameraX.
 */
internal object EndXrHandTrackingDiagnostics {
    @Volatile private var cameraState = "stopped"
    @Volatile private var cameraLens = "unknown"
    @Volatile private var cameraWidthPx = 0
    @Volatile private var cameraHeightPx = 0
    @Volatile private var metrics = HandTrackingMetricsSnapshot(
        receivedFrames = 0L,
        submittedFrames = 0L,
        completedFrames = 0L,
        droppedBusyFrames = 0L,
        droppedIntervalFrames = 0L,
        lastPreprocessingMs = 0L,
        averagePreprocessingMs = 0f
    )
    @Volatile private var resultCount = 0L
    @Volatile private var lastResultTimestampMs = 0L
    @Volatile private var lastResultHands = -1
    @Volatile private var resultFps = 0f
    @Volatile private var lastError = ""

    fun begin(widthPx: Int, heightPx: Int) {
        cameraState = "starting"
        cameraLens = "unknown"
        cameraWidthPx = widthPx
        cameraHeightPx = heightPx
        metrics = metrics.copy(
            receivedFrames = 0L,
            submittedFrames = 0L,
            completedFrames = 0L,
            droppedBusyFrames = 0L,
            droppedIntervalFrames = 0L,
            lastPreprocessingMs = 0L,
            averagePreprocessingMs = 0f
        )
        resultCount = 0L
        lastResultTimestampMs = 0L
        lastResultHands = -1
        resultFps = 0f
        lastError = ""
    }

    fun cameraSelected(frontCamera: Boolean) {
        cameraLens = if (frontCamera) "front" else "back"
        cameraState = "binding"
    }

    fun markRunning() {
        cameraState = "running"
    }

    fun markError(message: String) {
        cameraState = "error"
        lastError = message.replace('\n', ' ').replace('\r', ' ')
    }

    fun markStopped() {
        cameraState = "stopped"
    }

    fun updateMetrics(snapshot: HandTrackingMetricsSnapshot) {
        metrics = snapshot
    }

    fun onFrame(frame: HandTrackingFrame) {
        val previous = lastResultTimestampMs
        if (previous > 0L && frame.timestampMs > previous) {
            resultFps = (1000f / (frame.timestampMs - previous)).coerceAtMost(240f)
        }
        lastResultTimestampMs = frame.timestampMs
        lastResultHands = frame.handCount
        resultCount += 1L
    }

    fun latestFps(): Float = resultFps

    fun latestHands(): Int = lastResultHands.coerceAtLeast(0)

    fun report(): String {
        val snapshot = metrics
        val timestamp = lastResultTimestampMs
        val resultAgeMs = if (timestamp > 0L) {
            (android.os.SystemClock.uptimeMillis() - timestamp).coerceAtLeast(0L)
        } else {
            -1L
        }
        return buildString {
            append("service_hand_tracking_state=").append(cameraState).append('\n')
            append("service_camera_lens=").append(cameraLens).append('\n')
            append("service_camera_width_px=").append(cameraWidthPx).append('\n')
            append("service_camera_height_px=").append(cameraHeightPx).append('\n')
            append("service_camera_frames_received=").append(snapshot.receivedFrames).append('\n')
            append("service_mediapipe_frames_submitted=").append(snapshot.submittedFrames).append('\n')
            append("service_mediapipe_results_completed=").append(snapshot.completedFrames).append('\n')
            append("service_mediapipe_frames_dropped_busy=").append(snapshot.droppedBusyFrames).append('\n')
            append("service_mediapipe_frames_dropped_interval=").append(snapshot.droppedIntervalFrames).append('\n')
            append("service_hand_result_count=").append(resultCount).append('\n')
            append("service_hand_result_fps=").append(resultFps).append('\n')
            append("service_last_result_hands=").append(lastResultHands).append('\n')
            append("service_last_result_age_ms=").append(resultAgeMs).append('\n')
            append("service_last_preprocessing_ms=").append(snapshot.lastPreprocessingMs).append('\n')
            append("service_last_frame_width_px=").append(snapshot.lastFrameWidthPx).append('\n')
            append("service_last_frame_height_px=").append(snapshot.lastFrameHeightPx).append('\n')
            append("service_last_frame_format=").append(snapshot.lastFrameFormat).append('\n')
            append("service_last_frame_rotation_degrees=").append(snapshot.lastFrameRotationDegrees).append('\n')
            append("service_last_frame_row_stride=").append(snapshot.lastFrameRowStride).append('\n')
            append("service_last_frame_pixel_stride=").append(snapshot.lastFramePixelStride).append('\n')
            append("service_last_error=").append(lastError).append('\n')
        }
    }
}

/**
 * User-started CameraX foreground service. It keeps MediaPipe pinch/grip
 * publishing after Sandbox has paused and an external OpenXR app is foreground.
 *
 * This service does not intercept Android input events from other applications.
 */
class EndXrHandTrackingService : Service(), LifecycleOwner, SensorEventListener {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val rotationSensor by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    @Volatile private var handTracker: HandLandmarkerController? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var cameraGeneration = 0L
    private var cameraWidthPx = DEFAULT_WIDTH_PX
    private var cameraHeightPx = DEFAULT_HEIGHT_PX
    private var maxHands = DEFAULT_MAX_HANDS
    private var minimumInferenceIntervalMs = DEFAULT_INFERENCE_INTERVAL_MS

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        readConfiguration(intent)
        EndXrHandTrackingDiagnostics.begin(cameraWidthPx, cameraHeightPx)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Камера рук готовится для EndXR"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Камера рук готовится для EndXR")
                )
            }
            activeOrPending = true
        } catch (error: Exception) {
            activeOrPending = false
            EndXrHandTrackingDiagnostics.markError(error.message ?: "Could not enter foreground service state.")
            Log.e(TAG, "Could not enter camera foreground-service state", error)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (lifecycleRegistry.currentState == Lifecycle.State.CREATED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        startHeadOrientationTracking()
        startCamera()
        return START_NOT_STICKY
    }

    private fun readConfiguration(intent: Intent?) {
        cameraWidthPx = (intent?.getIntExtra(EXTRA_WIDTH, DEFAULT_WIDTH_PX)
            ?: DEFAULT_WIDTH_PX).coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX)
        cameraHeightPx = (intent?.getIntExtra(EXTRA_HEIGHT, DEFAULT_HEIGHT_PX)
            ?: DEFAULT_HEIGHT_PX).coerceIn(MIN_HEIGHT_PX, MAX_HEIGHT_PX)
        maxHands = (intent?.getIntExtra(EXTRA_MAX_HANDS, DEFAULT_MAX_HANDS)
            ?: DEFAULT_MAX_HANDS).coerceIn(1, 2)
        minimumInferenceIntervalMs = (intent?.getLongExtra(
            EXTRA_INTERVAL_MS,
            DEFAULT_INFERENCE_INTERVAL_MS
        ) ?: DEFAULT_INFERENCE_INTERVAL_MS).coerceIn(0L, 1_000L)
    }

    private fun startCamera() {
        val generation = ++cameraGeneration
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Camera permission is not granted")
            activeOrPending = false
            EndXrHandTrackingDiagnostics.markError("Camera permission is not granted")
            stopSelf()
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(applicationContext)
        providerFuture.addListener({
            if (!activeOrPending || generation != cameraGeneration) return@addListener
            var newTracker: HandLandmarkerController? = null
            try {
                val provider = providerFuture.get()
                // Hand tracking is a user-facing interaction: prefer the front camera.
                // The previous back-camera-first choice made users present their hands
                // away from the lens while looking at the phone display.
                val useFrontCamera = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                val selector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                EndXrHandTrackingDiagnostics.cameraSelected(useFrontCamera)
                check(provider.hasCamera(selector)) {
                    "Android did not expose a usable front or back camera to the foreground service."
                }

                analysisUseCase?.clearAnalyzer()
                analysisUseCase?.let { oldUseCase ->
                    runCatching { provider.unbind(oldUseCase) }
                }
                handTracker?.close()
                handTracker = null

                newTracker = HandLandmarkerController(
                    context = applicationContext,
                    mirrorFrontCamera = useFrontCamera,
                    onFrame = { frame ->
                        EndXrHandTrackingDiagnostics.onFrame(frame)
                        val encoded = EndXrHandInputEncoder.encode(frame)
                        EndXrInputSnapshotStore.publish(encoded.leftRow, encoded.rightRow)
                        EndXrInputSnapshotStore.publishHandPoses(encoded.leftPose, encoded.rightPose)
                    },
                    onError = { message ->
                        EndXrHandTrackingDiagnostics.markError(message)
                        Log.e(TAG, "MediaPipe foreground tracking error: $message")
                        EndXrInputSnapshotStore.clear()
                    },
                    maxHands = maxHands,
                    minimumInferenceIntervalMs = minimumInferenceIntervalMs,
                    outputWidthPx = cameraWidthPx,
                    outputHeightPx = cameraHeightPx,
                    onMetrics = EndXrHandTrackingDiagnostics::updateMetrics
                )

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(cameraWidthPx, cameraHeightPx))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { useCase ->
                        useCase.setAnalyzer(cameraExecutor) { frame ->
                            handTracker?.analyze(frame) ?: frame.close()
                        }
                    }

                // Sandbox releases its Activity-bound camera use case before starting
                // this service. CameraX then binds this independent LifecycleOwner.
                provider.unbindAll()
                cameraProvider = provider
                handTracker = newTracker
                newTracker = null
                analysisUseCase = analysis
                provider.bindToLifecycle(this, selector, analysis)
                EndXrHandTrackingDiagnostics.markRunning()

                Log.i(
                    TAG,
                    "Persistent hand tracking active: " + cameraWidthPx + "x" +
                        cameraHeightPx + ", hands=" + maxHands +
                        ", lens=" + (if (useFrontCamera) "front" else "back") +
                        ", interval=" + minimumInferenceIntervalMs + "ms"
                )
                updateNotification("MediaPipe работает · руки передаются в EndXR")
            } catch (error: Exception) {
                newTracker?.close()
                handTracker?.close()
                handTracker = null
                analysisUseCase?.clearAnalyzer()
                analysisUseCase = null
                EndXrHandTrackingDiagnostics.markError(error.message ?: "Could not bind CameraX.")
                Log.e(TAG, "Could not bind CameraX to EndXR foreground service", error)
                activeOrPending = false
                EndXrInputSnapshotStore.clear()
                // Do not leave a foreground notification and a dead camera service
                // running after CameraX could not attach to this LifecycleOwner.
                stopSelf()
                updateNotification("Камера недоступна · проверь разрешение и устройство")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startHeadOrientationTracking() {
        val sensor = rotationSensor
        if (sensor == null) {
            Log.w(TAG, "No rotation-vector sensor is available; external head orientation stays inactive.")
            EndXrInputSnapshotStore.publishHeadOrientation(0f, 0f, 0f, 0f)
            return
        }
        val registered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        if (!registered) {
            Log.w(TAG, "Could not register the phone rotation-vector sensor.")
            EndXrInputSnapshotStore.publishHeadOrientation(0f, 0f, 0f, 0f)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR
        ) return
        val quaternion = FloatArray(4)
        SensorManager.getQuaternionFromVector(quaternion, event.values)
        EndXrInputSnapshotStore.publishHeadOrientation(
            quaternion[1], quaternion[2], quaternion[3], quaternion[0]
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun buildNotification(message: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            createStopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("QuestPhoneXR · EndXR")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Остановить",
                stopIntent
            )
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "EndXR hand tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Camera hand tracking for an external XR game."
                setShowBadge(false)
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeOrPending = false
        EndXrHandTrackingDiagnostics.markStopped()
        sensorManager.unregisterListener(this)
        cameraGeneration += 1L
        analysisUseCase?.clearAnalyzer()
        analysisUseCase?.let { useCase ->
            runCatching { cameraProvider?.unbind(useCase) }
        }
        analysisUseCase = null
        handTracker?.close()
        handTracker = null
        cameraExecutor.shutdown()
        EndXrInputSnapshotStore.clear()

        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EndXR-HandService"
        private const val CHANNEL_ID = "endxr_hand_tracking"
        private const val NOTIFICATION_ID = 7412
        private const val STOP_REQUEST_CODE = 7413

        private const val ACTION_STOP = "com.noyclic.questphonexr.action.STOP_ENDXR_HANDS"
        private const val EXTRA_WIDTH = "endxr.camera.width"
        private const val EXTRA_HEIGHT = "endxr.camera.height"
        private const val EXTRA_MAX_HANDS = "endxr.camera.max_hands"
        private const val EXTRA_INTERVAL_MS = "endxr.camera.interval_ms"

        private const val DEFAULT_WIDTH_PX = 320
        private const val DEFAULT_HEIGHT_PX = 240
        private const val DEFAULT_MAX_HANDS = 2
        private const val DEFAULT_INFERENCE_INTERVAL_MS = 100L
        private const val MIN_WIDTH_PX = 160
        private const val MAX_WIDTH_PX = 1280
        private const val MIN_HEIGHT_PX = 120
        private const val MAX_HEIGHT_PX = 960

        @Volatile private var activeOrPending = false

        fun isRunning(): Boolean = activeOrPending

        fun diagnosticReport(): String = EndXrHandTrackingDiagnostics.report()

        fun diagnosticFps(): Float = EndXrHandTrackingDiagnostics.latestFps()

        fun diagnosticHandCount(): Int = EndXrHandTrackingDiagnostics.latestHands()

        fun markStartFailed() {
            activeOrPending = false
        }

        fun createStartIntent(
            context: Context,
            widthPx: Int,
            heightPx: Int,
            maxHands: Int,
            minimumInferenceIntervalMs: Long
        ): Intent {
            activeOrPending = true
            return Intent(context, EndXrHandTrackingService::class.java).apply {
                putExtra(EXTRA_WIDTH, widthPx)
                putExtra(EXTRA_HEIGHT, heightPx)
                putExtra(EXTRA_MAX_HANDS, maxHands)
                putExtra(EXTRA_INTERVAL_MS, minimumInferenceIntervalMs)
            }
        }

        fun createStopIntent(context: Context): Intent =
            Intent(context, EndXrHandTrackingService::class.java).setAction(ACTION_STOP)
    }
}
