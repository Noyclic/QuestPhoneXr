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
import java.util.concurrent.Executors

/**
 * User-started CameraX foreground service. It keeps MediaPipe pinch/grip
 * publishing after Sandbox has paused and an external OpenXR app is foreground.
 *
 * This service does not intercept Android input events from other applications.
 */
class EndXrHandTrackingService : Service(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val cameraExecutor = Executors.newSingleThreadExecutor()

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
            Log.e(TAG, "Could not enter camera foreground-service state", error)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (lifecycleRegistry.currentState == Lifecycle.State.CREATED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
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
            stopSelf()
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(applicationContext)
        providerFuture.addListener({
            if (!activeOrPending || generation != cameraGeneration) return@addListener
            var newTracker: HandLandmarkerController? = null
            try {
                val provider = providerFuture.get()
                val useBackCamera = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                val selector = if (useBackCamera) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                check(provider.hasCamera(selector)) {
                    "Android did not expose a usable camera to the foreground service."
                }

                analysisUseCase?.clearAnalyzer()
                analysisUseCase?.let { oldUseCase ->
                    runCatching { provider.unbind(oldUseCase) }
                }
                handTracker?.close()
                handTracker = null

                newTracker = HandLandmarkerController(
                    context = applicationContext,
                    mirrorFrontCamera = !useBackCamera,
                    onFrame = { frame ->
                        val rows = EndXrHandInputEncoder.encode(frame)
                        EndXrInputSnapshotStore.publish(rows.first, rows.second)
                    },
                    onError = { message ->
                        Log.e(TAG, "MediaPipe foreground tracking error: $message")
                        EndXrInputSnapshotStore.clear()
                    },
                    maxHands = maxHands,
                    minimumInferenceIntervalMs = minimumInferenceIntervalMs,
                    outputWidthPx = cameraWidthPx,
                    outputHeightPx = cameraHeightPx
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

                Log.i(
                    TAG,
                    "Persistent hand tracking active: " + cameraWidthPx + "x" +
                        cameraHeightPx + ", hands=" + maxHands +
                        ", interval=" + minimumInferenceIntervalMs + "ms"
                )
                updateNotification("MediaPipe работает · руки передаются в EndXR")
            } catch (error: Exception) {
                newTracker?.close()
                handTracker?.close()
                handTracker = null
                analysisUseCase?.clearAnalyzer()
                analysisUseCase = null
                Log.e(TAG, "Could not bind CameraX to EndXR foreground service", error)
                EndXrInputSnapshotStore.clear()
                updateNotification("Камера недоступна · проверь разрешение и устройство")
            }
        }, ContextCompat.getMainExecutor(this))
    }

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
