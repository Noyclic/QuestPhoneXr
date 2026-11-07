package com.noyclic.questphonexr.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone-camera MediaPipe implementation with bounded LIVE_STREAM inference.
 *
 * CameraX supplies RGBA frames. Conversion buffers and bitmaps are reused between frames:
 * the one-frame-in-flight gate guarantees that the model bitmap is never modified while
 * MediaPipe is reading it.
 */
class HandLandmarkerController(
    context: Context,
    private val mirrorFrontCamera: Boolean,
    private val onFrame: (HandTrackingFrame) -> Unit,
    private val onError: (String) -> Unit,
    private val maxHands: Int = MAX_HANDS,
    minimumInferenceIntervalMs: Long = 0L,
    private val outputWidthPx: Int = DEFAULT_OUTPUT_WIDTH_PX,
    private val outputHeightPx: Int = DEFAULT_OUTPUT_HEIGHT_PX,
    private val onMetrics: ((HandTrackingMetricsSnapshot) -> Unit)? = null,
    private val preferredDelegate: Delegate = Delegate.CPU
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val processing = AtomicBoolean(false)
    private val lastAcceptedFrameMs = AtomicLong(0L)
    private val lastSubmittedTimestampMs = AtomicLong(0L)
    private val minimumInferenceIntervalMs = AtomicLong(minimumInferenceIntervalMs)
    private val inFlightImage = AtomicReference<MPImage?>(null)
    private val inFlightTransform = AtomicReference<HandFrameTransform?>(null)
    private val inFlightStartedAtNanos = AtomicLong(0L)
    private val inFlightSourceWidth = AtomicLong(0L)
    private val inFlightSourceHeight = AtomicLong(0L)
    private val inFlightPreprocessingMs = AtomicLong(0L)

    private val receivedFrames = AtomicLong(0L)
    private val submittedFrames = AtomicLong(0L)
    private val completedFrames = AtomicLong(0L)
    private val droppedBusyFrames = AtomicLong(0L)
    private val droppedIntervalFrames = AtomicLong(0L)
    private val preprocessingSamples = AtomicLong(0L)
    private val totalPreprocessingNanos = AtomicLong(0L)
    private val lastPreprocessingMs = AtomicLong(0L)
    private val lastInferenceMs = AtomicLong(0L)
    private val inferenceSamples = AtomicLong(0L)
    private val totalInferenceNanos = AtomicLong(0L)
    private val lastResultProcessingMs = AtomicLong(0L)
    private val resultProcessingSamples = AtomicLong(0L)
    private val totalResultProcessingNanos = AtomicLong(0L)
    @Volatile private var lastFrameWidthPx = 0
    @Volatile private var lastFrameHeightPx = 0
    @Volatile private var lastFrameFormat = 0
    @Volatile private var lastFrameRotationDegrees = 0
    @Volatile private var lastFrameRowStride = 0
    @Volatile private var lastFramePixelStride = 0
    @Volatile private var lastFrameLumaMean = 0f
    @Volatile private var lastFrameLumaStdDev = 0f

    // Analyzer-thread confined. The processing gate permits only one frame to use these at once.
    private var sourceBitmap: Bitmap? = null
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var packedPixels: ByteBuffer? = null
    private var rowScratch: ByteArray? = null
    private var modelBitmap: Bitmap? = null
    private var modelCanvas: Canvas? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val handTrackManager = HandTrackManager(maxHands)
    private val landmarker: HandLandmarker
    @Volatile private var selectedDelegateName: String = Delegate.CPU.name

    init {
        require(maxHands in 1..MAX_HANDS) { "maxHands must be between 1 and $MAX_HANDS." }
        require(minimumInferenceIntervalMs >= 0L) { "Inference interval cannot be negative." }
        require(outputWidthPx in 1..MAX_OUTPUT_WIDTH_PX && outputHeightPx in 1..MAX_OUTPUT_HEIGHT_PX) {
            "Output dimensions are outside the supported range."
        }

        fun optionsFor(delegate: Delegate): HandLandmarker.HandLandmarkerOptions {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(delegate)
                .build()
            return HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(maxHands)
                .setMinHandDetectionConfidence(DETECTION_CONFIDENCE)
                .setMinHandPresenceConfidence(PRESENCE_CONFIDENCE)
                .setMinTrackingConfidence(TRACKING_CONFIDENCE)
                .setResultListener(::onResult)
                .setErrorListener(::onTaskError)
                .build()
        }

        // GPU is an optimization attempt, never a hard device requirement. If the
        // driver or MediaPipe delegate cannot initialize, retain the known CPU path.
        val requested = runCatching {
            HandLandmarker.createFromOptions(context, optionsFor(preferredDelegate))
        }
        if (requested.isSuccess) {
            selectedDelegateName = preferredDelegate.name
            landmarker = checkNotNull(requested.getOrNull())
        } else if (preferredDelegate != Delegate.CPU) {
            Log.w(TAG, "MediaPipe ${preferredDelegate.name} delegate unavailable; falling back to CPU",
                requested.exceptionOrNull())
            selectedDelegateName = Delegate.CPU.name
            landmarker = HandLandmarker.createFromOptions(context, optionsFor(Delegate.CPU))
        } else {
            throw requested.exceptionOrNull() ?: IllegalStateException("Could not create MediaPipe HandLandmarker.")
        }
    }

    /** Can be adjusted safely while CameraX is active; no model recreation is needed. */
    fun updateMinimumInferenceIntervalMs(intervalMs: Long) {
        require(intervalMs >= 0L) { "Inference interval cannot be negative." }
        if (!closed.get()) minimumInferenceIntervalMs.set(intervalMs)
    }

    fun metricsSnapshot(): HandTrackingMetricsSnapshot {
        val samples = preprocessingSamples.get()
        val averagePreprocessingMs = if (samples == 0L) {
            0f
        } else {
            totalPreprocessingNanos.get().toFloat() / samples.toFloat() / NANOS_PER_MILLISECOND
        }
        return HandTrackingMetricsSnapshot(
            receivedFrames = receivedFrames.get(),
            submittedFrames = submittedFrames.get(),
            completedFrames = completedFrames.get(),
            droppedBusyFrames = droppedBusyFrames.get(),
            droppedIntervalFrames = droppedIntervalFrames.get(),
            lastPreprocessingMs = lastPreprocessingMs.get(),
            averagePreprocessingMs = averagePreprocessingMs,
            delegateName = selectedDelegateName,
            lastInferenceMs = lastInferenceMs.get(),
            averageInferenceMs = if (inferenceSamples.get() == 0L) 0f else
                totalInferenceNanos.get().toFloat() / inferenceSamples.get().toFloat() /
                    NANOS_PER_MILLISECOND,
            lastResultProcessingMs = lastResultProcessingMs.get(),
            averageResultProcessingMs = if (resultProcessingSamples.get() == 0L) 0f else
                totalResultProcessingNanos.get().toFloat() /
                    resultProcessingSamples.get().toFloat() / NANOS_PER_MILLISECOND,
            lastFrameWidthPx = lastFrameWidthPx,
            lastFrameHeightPx = lastFrameHeightPx,
            lastFrameFormat = lastFrameFormat,
            lastFrameRotationDegrees = lastFrameRotationDegrees,
            lastFrameRowStride = lastFrameRowStride,
            lastFramePixelStride = lastFramePixelStride,
            lastFrameLumaMean = lastFrameLumaMean,
            lastFrameLumaStdDev = lastFrameLumaStdDev
        )
    }

    private fun publishMetrics() {
        val callback = onMetrics ?: return
        runCatching { callback(metricsSnapshot()) }
    }

    fun analyze(imageProxy: ImageProxy) {
        receivedFrames.incrementAndGet()
        lastFrameWidthPx = imageProxy.width
        lastFrameHeightPx = imageProxy.height
        lastFrameFormat = imageProxy.format
        lastFrameRotationDegrees = imageProxy.imageInfo.rotationDegrees
        imageProxy.planes.firstOrNull()?.let { plane ->
            lastFrameRowStride = plane.rowStride
            lastFramePixelStride = plane.pixelStride
        }
        publishMetrics()
        if (closed.get()) {
            imageProxy.close()
            return
        }

        val nowMs = SystemClock.uptimeMillis()
        val minimumIntervalMs = minimumInferenceIntervalMs.get()
        val lastAcceptedMs = lastAcceptedFrameMs.get()
        if (minimumIntervalMs > 0L && lastAcceptedMs > 0L &&
            nowMs - lastAcceptedMs < minimumIntervalMs
        ) {
            droppedIntervalFrames.incrementAndGet()
            publishMetrics()
            imageProxy.close()
            return
        }
        if (!processing.compareAndSet(false, true)) {
            droppedBusyFrames.incrementAndGet()
            publishMetrics()
            imageProxy.close()
            return
        }

        lastAcceptedFrameMs.set(nowMs)
        val startedAtNanos = SystemClock.elapsedRealtimeNanos()
        inFlightStartedAtNanos.set(startedAtNanos)
        inFlightSourceWidth.set(imageProxy.width.toLong())
        inFlightSourceHeight.set(imageProxy.height.toLong())

        var mpImage: MPImage? = null
        try {
            val source = copyRgbaFrame(imageProxy)
            val transform = drawModelInput(
                source = source,
                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                mirror = mirrorFrontCamera
            )
            inFlightTransform.set(transform)

            mpImage = BitmapImageBuilder(checkNotNull(modelBitmap)).build()
            inFlightImage.set(mpImage)

            val preprocessingNanos =
                (SystemClock.elapsedRealtimeNanos() - startedAtNanos).coerceAtLeast(0L)
            val preprocessingMs = preprocessingNanos / NANOS_PER_MILLISECOND
            inFlightPreprocessingMs.set(preprocessingMs)
            lastPreprocessingMs.set(preprocessingMs)
            totalPreprocessingNanos.addAndGet(preprocessingNanos)
            preprocessingSamples.incrementAndGet()

            val timestampMs = nextMonotonicTimestamp()
            submittedFrames.incrementAndGet()
            publishMetrics()
            landmarker.detectAsync(mpImage, timestampMs)
        } catch (error: Exception) {
            mpImage?.let {
                inFlightImage.compareAndSet(it, null)
                runCatching { it.close() }
            }
            resetInFlightState()
            publishMetrics()
            Log.e(TAG, "Could not process camera frame", error)
            onError(error.message ?: "MediaPipe could not process the camera frame.")
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Packs CameraX's possibly padded RGBA rows into a reusable direct buffer and copies them
     * into a persistent bitmap. No full-frame ByteArray or Bitmap is allocated per accepted frame.
     */
    private fun copyRgbaFrame(image: ImageProxy): Bitmap {
        require(image.planes.isNotEmpty()) { "CameraX returned a frame without image planes." }
        val plane = image.planes[0]
        require(plane.pixelStride == BYTES_PER_PIXEL) {
            "Expected RGBA_8888 frames; received pixel stride " + plane.pixelStride + "."
        }

        val width = image.width
        val height = image.height
        require(width > 0 && height > 0) { "CameraX returned an empty frame." }
        val rowBytes = width * BYTES_PER_PIXEL
        val requiredBytes = rowBytes * height

        if (sourceBitmap == null || sourceWidth != width || sourceHeight != height) {
            sourceBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            sourceWidth = width
            sourceHeight = height
            packedPixels = null
            rowScratch = null
        }

        val bitmap = checkNotNull(sourceBitmap)
        val input = plane.buffer.duplicate()
        val initialPosition = input.position()
        if (plane.rowStride == rowBytes && input.remaining() >= requiredBytes) {
            // CameraX's common tightly packed RGBA path needs no intermediate row
            // buffer or direct-buffer repack. The duplicate keeps ImageProxy's buffer
            // position untouched while Bitmap copies the exact pixel payload.
            input.limit(initialPosition + requiredBytes)
            bitmap.copyPixelsFromBuffer(input)
        } else {
            // Preserve support for padded camera rows on devices with unusual strides.
            if (packedPixels == null || packedPixels?.capacity() != requiredBytes) {
                packedPixels = ByteBuffer.allocateDirect(requiredBytes)
                rowScratch = ByteArray(rowBytes)
            }
            val packed = checkNotNull(packedPixels)
            val scratch = checkNotNull(rowScratch)
            packed.clear()
            for (row in 0 until height) {
                input.position(initialPosition + row * plane.rowStride)
                input.get(scratch, 0, rowBytes)
                packed.put(scratch, 0, rowBytes)
            }
            packed.flip()
            bitmap.copyPixelsFromBuffer(packed)
        }
        updateFrameSignalMetrics(bitmap)
        return bitmap
    }


    /**
     * Samples a tiny fixed grid to distinguish a live camera image from a black/frozen feed.
     * This is diagnostic-only and avoids allocating a second full-resolution image.
     */
    private fun updateFrameSignalMetrics(bitmap: Bitmap) {
        val columns = 8
        val rows = 6
        var sum = 0.0
        var sumSquares = 0.0
        var samples = 0
        for (row in 0 until rows) {
            val y = ((row + 0.5f) * bitmap.height / rows).toInt()
                .coerceIn(0, bitmap.height - 1)
            for (column in 0 until columns) {
                val x = ((column + 0.5f) * bitmap.width / columns).toInt()
                    .coerceIn(0, bitmap.width - 1)
                val pixel = bitmap.getPixel(x, y)
                val red = (pixel shr 16) and 0xff
                val green = (pixel shr 8) and 0xff
                val blue = pixel and 0xff
                val luma = (red + green + blue) / 3.0
                sum += luma
                sumSquares += luma * luma
                samples += 1
            }
        }
        val mean = sum / samples
        val variance = (sumSquares / samples - mean * mean).coerceAtLeast(0.0)
        lastFrameLumaMean = mean.toFloat()
        lastFrameLumaStdDev = kotlin.math.sqrt(variance).toFloat()
    }

    /**
     * Rotates, mirrors, scales and letterboxes directly into one reusable model bitmap.
     * No temporary rotated bitmap, Matrix or RectF is created for every frame.
     */
    private fun drawModelInput(
        source: Bitmap,
        rotationDegrees: Int,
        mirror: Boolean
    ): HandFrameTransform {
        if (modelBitmap == null) {
            modelBitmap = Bitmap.createBitmap(
                outputWidthPx,
                outputHeightPx,
                Bitmap.Config.ARGB_8888
            )
            modelCanvas = Canvas(checkNotNull(modelBitmap))
        }
        val canvas = checkNotNull(modelCanvas)
        val rotation = normalizeCameraRotationDegrees(rotationDegrees)
        val rotatedWidth = if (rotation == 90 || rotation == 270) source.height else source.width
        val rotatedHeight = if (rotation == 90 || rotation == 270) source.width else source.height
        val scale = minOf(
            outputWidthPx.toFloat() / rotatedWidth.toFloat(),
            outputHeightPx.toFloat() / rotatedHeight.toFloat()
        )
        val contentWidth = rotatedWidth * scale
        val contentHeight = rotatedHeight * scale
        val left = (outputWidthPx - contentWidth) / 2f
        val top = (outputHeightPx - contentHeight) / 2f

        canvas.drawColor(Color.BLACK)
        val saveCount = canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)

        if (mirror) {
            canvas.translate(rotatedWidth.toFloat(), 0f)
            canvas.scale(-1f, 1f)
        }

        when (rotation) {
            90 -> {
                canvas.translate(source.height.toFloat(), 0f)
                canvas.rotate(90f)
            }
            180 -> {
                canvas.translate(source.width.toFloat(), source.height.toFloat())
                canvas.rotate(180f)
            }
            270 -> {
                canvas.translate(0f, source.width.toFloat())
                canvas.rotate(270f)
            }
        }

        canvas.drawBitmap(source, 0f, 0f, bitmapPaint)
        canvas.restoreToCount(saveCount)

        return HandFrameTransform(
            leftPx = left,
            topPx = top,
            scale = scale,
            targetWidthPx = outputWidthPx,
            targetHeightPx = outputHeightPx,
            rotatedWidthPx = rotatedWidth,
            rotatedHeightPx = rotatedHeight,
            mirroredHorizontally = mirror
        )
    }

    private fun nextMonotonicTimestamp(): Long {
        while (true) {
            val previous = lastSubmittedTimestampMs.get()
            val next = maxOf(SystemClock.uptimeMillis(), previous + 1L)
            if (lastSubmittedTimestampMs.compareAndSet(previous, next)) return next
        }
    }

    private fun onResult(result: HandLandmarkerResult, input: MPImage) {
        val callbackStartedNanos = SystemClock.elapsedRealtimeNanos()
        try {
            if (closed.get()) return
            val landmarkLists = result.landmarks()
            val handednessLists = result.handedness()
            val worldLandmarkLists = result.worldLandmarks()
            val transform = inFlightTransform.get()

            val observations = landmarkLists.mapIndexed { index, landmarks ->
                val points = landmarks.map { landmark ->
                    val mapped = transform?.toRotatedFrameNormalized(landmark.x(), landmark.y())
                    if (mapped == null) {
                        HandPoint(landmark.x(), landmark.y(), landmark.z())
                    } else {
                        HandPoint(mapped.first, mapped.second, landmark.z())
                    }
                }
                val category = handednessLists.getOrNull(index)?.firstOrNull()
                val label = category?.categoryName()?.lowercase()
                val detectedSide = when (label) {
                    "left" -> HandSide.LEFT
                    "right" -> HandSide.RIGHT
                    else -> HandSide.UNKNOWN
                }
                // Preserve the current front-camera convention after mirroring the input image.
                val side = if (mirrorFrontCamera) detectedSide.opposite() else detectedSide
                val worldPoints = worldLandmarkLists.getOrNull(index).orEmpty().map {
                    HandPoint(if (mirrorFrontCamera) -it.x() else it.x(), it.y(), it.z())
                }
                HandObservation(
                    points = points,
                    worldPoints = worldPoints,
                    observedSide = side,
                    handednessConfidence = category?.score() ?: 0f
                )
            }
            // Association happens before filtering, so result-list reordering cannot exchange
            // the two hands' filter history, stable IDs or pinch state.
            val hands = handTrackManager.update(observations, result.timestampMs())
            val resultProcessingNanos =
                (SystemClock.elapsedRealtimeNanos() - callbackStartedNanos).coerceAtLeast(0L)
            val resultProcessingMs = resultProcessingNanos / NANOS_PER_MILLISECOND
            lastResultProcessingMs.set(resultProcessingMs)
            totalResultProcessingNanos.addAndGet(resultProcessingNanos)
            resultProcessingSamples.incrementAndGet()

            val startedAtNanos = inFlightStartedAtNanos.getAndSet(0L)
            val latencyMs = if (startedAtNanos > 0L) {
                ((SystemClock.elapsedRealtimeNanos() - startedAtNanos).coerceAtLeast(0L) /
                    NANOS_PER_MILLISECOND)
            } else {
                0L
            }
            val preprocessingNanos = inFlightPreprocessingMs.getAndSet(0L) *
                NANOS_PER_MILLISECOND
            val inferenceNanos = (callbackStartedNanos - startedAtNanos - preprocessingNanos)
                .coerceAtLeast(0L)
            val inferenceMs = inferenceNanos / NANOS_PER_MILLISECOND
            lastInferenceMs.set(inferenceMs)
            totalInferenceNanos.addAndGet(inferenceNanos)
            inferenceSamples.incrementAndGet()
            val sourceWidth = inFlightSourceWidth.getAndSet(0L).toInt()
            val sourceHeight = inFlightSourceHeight.getAndSet(0L).toInt()
            val preprocessingMs = preprocessingNanos / NANOS_PER_MILLISECOND

            completedFrames.incrementAndGet()
            publishMetrics()
            onFrame(
                HandTrackingFrame(
                    timestampMs = result.timestampMs(),
                    hands = hands,
                    pipelineLatencyMs = latencyMs,
                    sourceWidthPx = sourceWidth,
                    sourceHeightPx = sourceHeight,
                    inputWidthPx = outputWidthPx,
                    inputHeightPx = outputHeightPx,
                    preprocessingLatencyMs = preprocessingMs
                )
            )
        } catch (error: Exception) {
            Log.e(TAG, "Could not convert MediaPipe landmarks", error)
            onError(error.message ?: "Could not read hand landmarks.")
        } finally {
            runCatching { input.close() }
            inFlightImage.compareAndSet(input, null)
            inFlightTransform.set(null)
            processing.set(false)
        }
    }

    private fun onTaskError(error: RuntimeException) {
        inFlightImage.getAndSet(null)?.close()
        handTrackManager.reset()
        resetInFlightState()
        publishMetrics()
        if (!closed.get()) {
            Log.e(TAG, "MediaPipe task error", error)
            onError(error.message ?: "MediaPipe reported an unknown error.")
        }
    }

    private fun resetInFlightState() {
        inFlightTransform.set(null)
        inFlightStartedAtNanos.set(0L)
        inFlightSourceWidth.set(0L)
        inFlightSourceHeight.set(0L)
        inFlightPreprocessingMs.set(0L)
        processing.set(false)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            inFlightImage.getAndSet(null)?.close()
            resetInFlightState()
            handTrackManager.reset()
            landmarker.close()

            // Do not recycle bitmaps while a native callback could still hold a reference.
            sourceBitmap = null
            modelBitmap = null
            modelCanvas = null
            packedPixels = null
            rowScratch = null
        }
    }

    private companion object {
        const val TAG = "QuestPhoneXR.Hands"
        const val DEFAULT_OUTPUT_WIDTH_PX = 480
        const val DEFAULT_OUTPUT_HEIGHT_PX = 360
        const val MAX_OUTPUT_WIDTH_PX = 1920
        const val MAX_OUTPUT_HEIGHT_PX = 1440
        const val MODEL_ASSET = "hand_landmarker.task"
        const val MAX_HANDS = 2
        // Slightly more permissive than the demo defaults: mobile front cameras,
        // partial hand rotation and lower light otherwise cause avoidable dropouts.
        const val DETECTION_CONFIDENCE = 0.40f
        const val PRESENCE_CONFIDENCE = 0.40f
        const val TRACKING_CONFIDENCE = 0.45f
        const val BYTES_PER_PIXEL = 4
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
