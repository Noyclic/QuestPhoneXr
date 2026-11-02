package com.noyclic.questphonexr.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
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

/** Phone-camera MediaPipe implementation with bounded LIVE_STREAM inference. */
class HandLandmarkerController(
    context: Context,
    private val mirrorFrontCamera: Boolean,
    private val onFrame: (HandTrackingFrame) -> Unit,
    private val onError: (String) -> Unit,
    private val maxHands: Int = MAX_HANDS,
    private val minimumInferenceIntervalMs: Long = 0L,
    private val outputWidthPx: Int = DEFAULT_OUTPUT_WIDTH_PX,
    private val outputHeightPx: Int = DEFAULT_OUTPUT_HEIGHT_PX
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val processing = AtomicBoolean(false)
    private val lastAcceptedFrameMs = AtomicLong(0L)
    private val inFlightImage = AtomicReference<MPImage?>(null)
    private val inFlightTransform = AtomicReference<LetterboxTransform?>(null)
    private val pinchEstimators = mutableListOf<PinchEstimator>()
    private val landmarker: HandLandmarker

    init {
        require(maxHands in 1..MAX_HANDS) { "maxHands must be between 1 and $MAX_HANDS." }
        require(minimumInferenceIntervalMs >= 0L) { "Inference interval cannot be negative." }
        require(outputWidthPx in 1..MAX_OUTPUT_WIDTH_PX && outputHeightPx in 1..MAX_OUTPUT_HEIGHT_PX) {
            "Output dimensions are outside the supported range."
        }
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(Delegate.CPU)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(maxHands)
            .setMinHandDetectionConfidence(CONFIDENCE)
            .setMinHandPresenceConfidence(CONFIDENCE)
            .setMinTrackingConfidence(CONFIDENCE)
            .setResultListener(::onResult)
            .setErrorListener(::onTaskError)
            .build()
        landmarker = HandLandmarker.createFromOptions(context, options)
    }

    fun analyze(imageProxy: ImageProxy) {
        if (closed.get()) {
            imageProxy.close()
            return
        }
        val nowMs = SystemClock.uptimeMillis()
        val lastAcceptedMs = lastAcceptedFrameMs.get()
        if (minimumInferenceIntervalMs > 0L && lastAcceptedMs > 0L &&
            nowMs - lastAcceptedMs < minimumInferenceIntervalMs
        ) {
            imageProxy.close()
            return
        }
        if (!processing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        lastAcceptedFrameMs.set(nowMs)
        var mpImage: MPImage? = null
        try {
            val source = imageProxyToBitmap(imageProxy)
            val transform = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (mirrorFrontCamera) postScale(-1f, 1f, source.width / 2f, source.height / 2f)
            }
            val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, true)
            if (rotated !== source) source.recycle()
            val resized = letterboxBitmap(rotated, outputWidthPx, outputHeightPx)
            if (resized.first !== rotated) rotated.recycle()
            inFlightTransform.set(resized.second)
            mpImage = BitmapImageBuilder(resized.first).build()
            inFlightImage.set(mpImage)
            landmarker.detectAsync(mpImage, SystemClock.uptimeMillis())
        } catch (error: Exception) {
            mpImage?.close()
            if (mpImage != null) inFlightImage.compareAndSet(mpImage, null)
            processing.set(false)
            Log.e(TAG, "Could not process camera frame", error)
            onError(error.message ?: "MediaPipe could not process the camera frame.")
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        require(image.planes.isNotEmpty()) { "CameraX returned a frame without image planes." }
        val plane = image.planes[0]
        require(plane.pixelStride == BYTES_PER_PIXEL) {
            "Expected RGBA_8888 frames; received pixel stride " + plane.pixelStride + "."
        }
        val width = image.width
        val height = image.height
        val rowBytes = width * BYTES_PER_PIXEL
        val packedBytes = ByteArray(rowBytes * height)
        val buffer = plane.buffer.duplicate()
        for (row in 0 until height) {
            buffer.position(row * plane.rowStride)
            buffer.get(packedBytes, row * rowBytes, rowBytes)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.copyPixelsFromBuffer(ByteBuffer.wrap(packedBytes))
        }
    }


    private fun letterboxBitmap(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Pair<Bitmap, LetterboxTransform> {
        val scale = minOf(targetWidth.toFloat() / source.width, targetHeight.toFloat() / source.height)
        val contentWidth = source.width * scale
        val contentHeight = source.height * scale
        val left = (targetWidth - contentWidth) / 2f
        val top = (targetHeight - contentHeight) / 2f
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(android.graphics.Color.BLACK)
            drawBitmap(source, null, RectF(left, top, left + contentWidth, top + contentHeight),
                Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return output to LetterboxTransform(left, top, contentWidth, contentHeight, targetWidth, targetHeight)
    }

    private fun onResult(result: HandLandmarkerResult, input: MPImage) {
        try {
            if (closed.get()) return
            val landmarkLists = result.landmarks()
            val handednessLists = result.handedness()
            val worldLandmarkLists = result.worldLandmarks()
            val transform = inFlightTransform.get()
            while (pinchEstimators.size < landmarkLists.size) pinchEstimators.add(PinchEstimator())
            while (pinchEstimators.size > landmarkLists.size) pinchEstimators.removeAt(pinchEstimators.lastIndex)
            val hands = landmarkLists.mapIndexed { index, landmarks ->
                val points = landmarks.map { landmark ->
                    val mapped = transform?.toSourceNormalized(landmark.x(), landmark.y())
                    if (mapped == null) HandPoint(landmark.x(), landmark.y(), landmark.z())
                    else HandPoint(mapped.first, mapped.second, landmark.z())
                }
                val pinch = pinchEstimators[index].update(points)
                val label = handednessLists.getOrNull(index)
                    ?.firstOrNull()
                    ?.categoryName()
                    ?.lowercase()
                val detectedSide = when (label) {
                    "left" -> HandSide.LEFT
                    "right" -> HandSide.RIGHT
                    else -> HandSide.UNKNOWN
                }
                // The front-camera frame is mirrored before inference, so restore subject handedness.
                val side = if (mirrorFrontCamera) detectedSide.opposite() else detectedSide
                val worldPoints = worldLandmarkLists.getOrNull(index).orEmpty().map {
                    HandPoint(if (mirrorFrontCamera) -it.x() else it.x(), it.y(), it.z())
                }
                TrackedHand(points, pinch.strength, pinch.isPinching, side, worldPoints)
            }
            onFrame(HandTrackingFrame(result.timestampMs(), hands))
        } catch (error: Exception) {
            Log.e(TAG, "Could not convert MediaPipe landmarks", error)
            onError(error.message ?: "Could not read hand landmarks.")
        } finally {
            input.close()
            inFlightImage.compareAndSet(input, null)
            inFlightTransform.set(null)
            processing.set(false)
        }
    }

    private fun onTaskError(error: RuntimeException) {
        inFlightImage.getAndSet(null)?.close()
        inFlightTransform.set(null)
        pinchEstimators.forEach { it.reset() }
        processing.set(false)
        if (!closed.get()) {
            Log.e(TAG, "MediaPipe task error", error)
            onError(error.message ?: "MediaPipe reported an unknown error.")
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            inFlightImage.getAndSet(null)?.close()
            inFlightTransform.set(null)
            pinchEstimators.forEach { it.reset() }
            landmarker.close()
        }
    }


    private data class LetterboxTransform(
        val leftPx: Float,
        val topPx: Float,
        val contentWidthPx: Float,
        val contentHeightPx: Float,
        val targetWidthPx: Int,
        val targetHeightPx: Int
    ) {
        fun toSourceNormalized(x: Float, y: Float): Pair<Float, Float> =
            (((x * targetWidthPx - leftPx) / contentWidthPx).coerceIn(0f, 1f)) to
                (((y * targetHeightPx - topPx) / contentHeightPx).coerceIn(0f, 1f))
    }

    private companion object {
        const val TAG = "QuestPhoneXR.Hands"
        const val DEFAULT_OUTPUT_WIDTH_PX = 480
        const val DEFAULT_OUTPUT_HEIGHT_PX = 360
        const val MAX_OUTPUT_WIDTH_PX = 1920
        const val MAX_OUTPUT_HEIGHT_PX = 1440
        const val MODEL_ASSET = "hand_landmarker.task"
        const val MAX_HANDS = 2
        const val CONFIDENCE = 0.5f
        const val BYTES_PER_PIXEL = 4
    }
}
