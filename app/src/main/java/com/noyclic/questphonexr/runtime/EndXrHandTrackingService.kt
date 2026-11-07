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
import com.noyclic.questphonexr.tracking.HandCameraPreference
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.tracking.HandTrackingMetricsSnapshot
import com.google.mediapipe.tasks.core.Delegate
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
    @Volatile private var latestFrame = HandTrackingFrame(0L, emptyList())
    @Volatile private var latestFrameReceivedAtMs = 0L

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
            averagePreprocessingMs = 0f,
            lastFrameWidthPx = 0,
            lastFrameHeightPx = 0,
            lastFrameFormat = 0,
            lastFrameRotationDegrees = 0,
            lastFrameRowStride = 0,
            lastFramePixelStride = 0,
            lastFrameLumaMean = 0f,
            lastFrameLumaStdDev = 0f
        )
        resultCount = 0L
        lastResultTimestampMs = 0L
        lastResultHands = -1
        resultFps = 0f
        lastError = ""
        latestFrame = HandTrackingFrame(0L, emptyList())
        latestFrameReceivedAtMs = 0L
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
        // Keep the failure reason visible after stopSelf() triggers onDestroy().
        // A new start calls begin(), which resets the previous error.
        if (cameraState != "error") cameraState = "stopped"
    }

    fun currentState(): String = cameraState

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
        latestFrame = frame
        latestFrameReceivedAtMs = android.os.SystemClock.uptimeMillis()
    }

    fun latestHandFrame(): HandTrackingFrame = latestFrame

    fun latestHandFrameReceivedAtMs(): Long = latestFrameReceivedAtMs

    fun currentMetrics(): HandTrackingMetricsSnapshot = metrics

    fun currentError(): String = lastError

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
            appendLine("service_mediapipe_delegate=${snapshot.delegateName}")
            append("service_last_preprocessing_ms=").append(snapshot.lastPreprocessingMs).append('\n')
            appendLine("service_last_inference_ms=${snapshot.lastInferenceMs}")
            appendLine("service_average_inference_ms=${snapshot.averageInferenceMs}")
            appendLine("service_last_result_processing_ms=${snapshot.lastResultProcessingMs}")
            appendLine("service_average_result_processing_ms=${snapshot.averageResultProcessingMs}")
            append("service_last_frame_width_px=").append(snapshot.lastFrameWidthPx).append('\n')
            append("service_last_frame_height_px=").append(snapshot.lastFrameHeightPx).append('\n')
            append("service_last_frame_format=").append(snapshot.lastFrameFormat).append('\n')
            append("service_last_frame_rotation_degrees=").append(snapshot.lastFrameRotationDegrees).append('\n')
            append("service_last_frame_row_stride=").append(snapshot.lastFrameRowStride).append('\n')
            append("service_last_frame_pixel_stride=").append(snapshot.lastFramePixelStride).append('\n')
            append("service_last_frame_luma_mean=").append(snapshot.lastFrameLumaMean).append('\n')
            append("service_last_frame_luma_stddev=").append(snapshot.lastFrameLumaStdDev).append('\n')
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
    private val servicePreferences by lazy {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
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
            activeOrPending = false
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_UPDATE_INTERVAL) {
            val updatedInterval = intent.getLongExtra(EXTRA_INTERVAL_MS, minimumInferenceIntervalMs)
                .coerceIn(0L, 1_000L)
            minimumInferenceIntervalMs = updatedInterval
            servicePreferences.edit().putLong(EXTRA_INTERVAL_MS, updatedInterval).apply()
            handTracker?.updateMinimumInferenceIntervalMs(updatedInterval)
            Log.i(TAG, "Updated live inference interval to ${updatedInterval}ms")
            return START_STICKY
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
        // This camera service is explicitly enabled by the user and should recover
        // if Android reclaims the app process under memory pressure. A null restart
        // intent restores the last user-selected camera configuration below.
        return START_STICKY
    }

    private fun readConfiguration(intent: Intent?) {
        // START_STICKY restarts are delivered with a null Intent. Retain the latest
        // user-selected settings so recovery does not silently fall back to 320x240.
        val savedWidth = servicePreferences.getInt(EXTRA_WIDTH, DEFAULT_WIDTH_PX)
        val savedHeight = servicePreferences.getInt(EXTRA_HEIGHT, DEFAULT_HEIGHT_PX)
        val savedMaxHands = servicePreferences.getInt(EXTRA_MAX_HANDS, DEFAULT_MAX_HANDS)
        val savedInterval = servicePreferences.getLong(
            EXTRA_INTERVAL_MS,
            DEFAULT_INFERENCE_INTERVAL_MS
        )

        cameraWidthPx = (intent?.getIntExtra(EXTRA_WIDTH, savedWidth)
            ?: savedWidth).coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX)
        cameraHeightPx = (intent?.getIntExtra(EXTRA_HEIGHT, savedHeight)
            ?: savedHeight).coerceIn(MIN_HEIGHT_PX, MAX_HEIGHT_PX)
        maxHands = (intent?.getIntExtra(EXTRA_MAX_HANDS, savedMaxHands)
            ?: savedMaxHands).coerceIn(1, 2)
        minimumInferenceIntervalMs = (intent?.getLongExtra(EXTRA_INTERVAL_MS, savedInterval)
            ?: savedInterval).coerceIn(0L, 1_000L)

        servicePreferences.edit()
            .putInt(EXTRA_WIDTH, cameraWidthPx)
            .putInt(EXTRA_HEIGHT, cameraHeightPx)
            .putInt(EXTRA_MAX_HANDS, maxHands)
            .putLong(EXTRA_INTERVAL_MS, minimumInferenceIntervalMs)
            .apply()
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
                // Persistent tracking follows the same explicit opt-in policy as Sandbox.
                // A null START_STICKY restart reads the saved preference; it never defaults to selfie.
                val cameraSelection = HandCameraPreference.resolve(this@EndXrHandTrackingService, provider)
                val useFrontCamera = cameraSelection.isFrontCamera
                val selector = cameraSelection.selector
                EndXrHandTrackingDiagnostics.cameraSelected(useFrontCamera)

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
                    // Keep camera capture resolution high enough for hand detail, but cap
                    // the bitmap sent to MediaPipe to reduce per-frame model work.
                    outputWidthPx = minOf(cameraWidthPx, MAX_MODEL_INPUT_WIDTH_PX),
                    outputHeightPx = minOf(cameraHeightPx, MAX_MODEL_INPUT_HEIGHT_PX),
                    onMetrics = EndXrHandTrackingDiagnostics::updateMetrics,
                    preferredDelegate = Delegate.GPU
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
                        ", lens=" + (if (useFrontCamera) "front (user enabled)" else "back") +
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
        private const val PREFERENCES_NAME = "endxr_hand_tracking_service"

        private const val ACTION_STOP = "com.noyclic.questphonexr.action.STOP_ENDXR_HANDS"
        private const val ACTION_UPDATE_INTERVAL = "com.noyclic.questphonexr.action.UPDATE_ENDXR_INTERVAL"
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
        private const val MAX_MODEL_INPUT_WIDTH_PX = 320
        private const val MAX_MODEL_INPUT_HEIGHT_PX = 240

        @Volatile private var activeOrPending = false

        fun isRunning(): Boolean = activeOrPending

        fun diagnosticReport(): String = EndXrHandTrackingDiagnostics.report()

        fun diagnosticState(): String = EndXrHandTrackingDiagnostics.currentState()

        fun diagnosticFps(): Float = EndXrHandTrackingDiagnostics.latestFps()

        fun diagnosticMetrics(): HandTrackingMetricsSnapshot = EndXrHandTrackingDiagnostics.currentMetrics()

        fun diagnosticLastError(): String = EndXrHandTrackingDiagnostics.currentError()

        fun latestFrame(): HandTrackingFrame = EndXrHandTrackingDiagnostics.latestHandFrame()

        fun latestFrameReceivedAtMs(): Long = EndXrHandTrackingDiagnostics.latestHandFrameReceivedAtMs()

        fun diagnosticHandCount(): Int = EndXrHandTrackingDiagnostics.latestHands()

        fun markStartFailed(message: String? = null) {
            activeOrPending = false
            if (!message.isNullOrBlank()) {
                EndXrHandTrackingDiagnostics.markError(message)
            }
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

        fun updateInferenceInterval(context: Context, intervalMs: Long) {
            val intent = Intent(context, EndXrHandTrackingService::class.java)
                .setAction(ACTION_UPDATE_INTERVAL)
                .putExtra(EXTRA_INTERVAL_MS, intervalMs.coerceIn(0L, 1_000L))
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "Could not update live tracking interval", it) }
        }

        fun createStopIntent(context: Context): Intent =
            Intent(context, EndXrHandTrackingService::class.java).setAction(ACTION_STOP)
    }
}
