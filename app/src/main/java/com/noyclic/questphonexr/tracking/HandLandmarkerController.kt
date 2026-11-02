package com.noyclic.questphonexr.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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
import java.util.concurrent.atomic.AtomicReference

/** Phone-camera MediaPipe implementation with bounded LIVE_STREAM inference. */
class HandLandmarkerController(
    context: Context,
    private val mirrorFrontCamera: Boolean,
    private val onFrame: (HandTrackingFrame) -> Unit,
    private val onError: (String) -> Unit
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val processing = AtomicBoolean(false)
    private val inFlightImage = AtomicReference<MPImage?>(null)
    private val pinchEstimators = mutableListOf<PinchEstimator>()
    private val landmarker: HandLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(Delegate.CPU)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(MAX_HANDS)
            .setMinHandDetectionConfidence(CONFIDENCE)
            .setMinHandPresenceConfidence(CONFIDENCE)
            .setMinTrackingConfidence(CONFIDENCE)
            .setResultListener(::onResult)
            .setErrorListener(::onTaskError)
            .build()
        landmarker = HandLandmarker.createFromOptions(context, options)
    }

    fun analyze(imageProxy: ImageProxy) {
        if (closed.get() || !processing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        var mpImage: MPImage? = null
        try {
            val source = imageProxyToBitmap(imageProxy)
            val transform = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (mirrorFrontCamera) postScale(-1f, 1f, source.width / 2f, source.height / 2f)
            }
            val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, true)
            if (rotated !== source) source.recycle()
            mpImage = BitmapImageBuilder(rotated).build()
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
            "Expected RGBA_8888 frames; received pixel stride ${plane.pixelStride}."
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

    private fun onResult(result: HandLandmarkerResult, input: MPImage) {
        try {
            if (closed.get()) return
            val landmarkLists = result.landmarks()
            while (pinchEstimators.size < landmarkLists.size) pinchEstimators.add(PinchEstimator())
            while (pinchEstimators.size > landmarkLists.size) pinchEstimators.removeAt(pinchEstimators.lastIndex)
            val hands = landmarkLists.mapIndexed { index, landmarks ->
                val points = landmarks.map { HandPoint(it.x(), it.y(), it.z()) }
                val pinch = pinchEstimators[index].update(points)
                TrackedHand(points, pinch.strength, pinch.isPinching)
            }
            onFrame(HandTrackingFrame(result.timestampMs(), hands))
        } catch (error: Exception) {
            Log.e(TAG, "Could not convert MediaPipe landmarks", error)
            onError(error.message ?: "Could not read hand landmarks.")
        } finally {
            input.close()
            inFlightImage.compareAndSet(input, null)
            processing.set(false)
        }
    }

    private fun onTaskError(error: RuntimeException) {
        inFlightImage.getAndSet(null)?.close()
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
            pinchEstimators.forEach { it.reset() }
            landmarker.close()
        }
    }

    private companion object {
        const val TAG = "QuestPhoneXR.Hands"
        const val MODEL_ASSET = "hand_landmarker.task"
        const val MAX_HANDS = 2
        const val CONFIDENCE = 0.5f
        const val BYTES_PER_PIXEL = 4
    }
}
