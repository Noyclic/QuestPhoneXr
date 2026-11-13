package com.noyclic.questphonexr.tracking

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.android.gms.litert.LiteRt
import com.google.android.gms.litert.LiteRtDsl.initializationOptions
import com.google.android.gms.tasks.Tasks
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

data class RtmposeRuntimeInfo(
    val modelFile: File,
    val accelerator: String,
    val inputElementCount: Int,
    val outputElementCounts: List<Int>,
    val warmupLatencyMs: Long
)

data class RtmposeInferenceResult(
    val decoded: RtmposeHandDecodeResult,
    /** Includes CompiledModel.run and output readback, which synchronizes GPU completion. */
    val endToEndInferenceLatencyMs: Long
)

/**
 * Google Play services LiteRT runner for RTMPose-Hand.
 *
 * One executor owns Environment, CompiledModel and all TensorBuffers for their full lifetime.
 * The fixed input scratch array is exposed as a short-lived lease so preprocessing can write
 * directly into it without allocating/copying ~0.75 MB on every camera frame. Only one
 * inference may be in flight. After [submit] is called, the lease must not be modified until
 * the callback runs.
 *
 * This runner is intentionally GPU-only. The published FP16 graph has no supported XNNPACK
 * execution path; a missing/incompatible GPU must leave MediaPipe active rather than silently
 * switching to a very slow CPU reference path.
 */
class RtmposeLiteRtRunner(
    context: Context,
    private val modelFile: File = RtmposeModelStore.installedFile(context)
) : Closeable {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EndXR-RTMPose-LiteRT").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val inputLeaseActive = AtomicBoolean(false)
    private val inputScratch = FloatArray(INPUT_ELEMENT_COUNT)

    // These handles are touched only by executor's thread except the readiness flags.
    private var environment: Environment? = null
    private var model: CompiledModel? = null
    private var inputBuffers: List<TensorBuffer> = emptyList()
    private var outputBuffers: List<TensorBuffer> = emptyList()
    @Volatile private var ready = false
    @Volatile private var initializationStarted = false

    fun initialize(callback: (Result<RtmposeRuntimeInfo>) -> Unit) {
        if (closed.get()) {
            callback(Result.failure(IllegalStateException("RTMPose runner is closed.")))
            return
        }
        if (initializationStarted) {
            callback(Result.failure(IllegalStateException("RTMPose initialization was already requested.")))
            return
        }
        initializationStarted = true
        try {
            executor.execute {
                try {
                    check(RtmposeModelStore.verify(modelFile)) {
                        "Verified RTMPose model file is missing. Download it explicitly first."
                    }

                    val options = initializationOptions { setEnableGpu(true) }
                    val createdEnvironment = Tasks.await(LiteRt.initialize(appContext, options))
                    environment = createdEnvironment
                    check(createdEnvironment.getAvailableAccelerators().contains(Accelerator.GPU)) {
                        "LiteRT GPU accelerator is not available on this device."
                    }

                    val compiledModel = CompiledModel.create(
                        modelFile.absolutePath,
                        CompiledModel.Options(Accelerator.GPU),
                        createdEnvironment
                    )
                    model = compiledModel
                    inputBuffers = compiledModel.createInputBuffers()
                    outputBuffers = compiledModel.createOutputBuffers()

                    check(inputBuffers.size == 1) {
                        "RTMPose expects exactly one input tensor; got ${inputBuffers.size}."
                    }
                    check(outputBuffers.size == EXPECTED_OUTPUT_COUNT) {
                        "RTMPose expects two SimCC output tensors; got ${outputBuffers.size}."
                    }

                    // Warm-up compiles the GPU graph before any camera frame is sent to it.
                    val inputBuffer = inputBuffers.single()
                    inputBuffer.writeFloat(inputScratch)
                    val warmupStarted = SystemClock.elapsedRealtimeNanos()
                    compiledModel.run(inputBuffers, outputBuffers)
                    val warmupOutputs = outputBuffers.map { it.readFloat() }
                    val warmupLatencyMs =
                        ((SystemClock.elapsedRealtimeNanos() - warmupStarted) /
                            NANOS_PER_MILLISECOND).coerceAtLeast(0L)

                    check(warmupOutputs.all { it.size == OUTPUT_ELEMENT_COUNT }) {
                        "RTMPose output tensors do not match [1,21,512]."
                    }
                    ready = true
                    callback(
                        Result.success(
                            RtmposeRuntimeInfo(
                                modelFile = modelFile,
                                accelerator = Accelerator.GPU.name,
                                inputElementCount = inputBufferSize(),
                                outputElementCounts = warmupOutputs.map { it.size },
                                warmupLatencyMs = warmupLatencyMs
                            )
                        )
                    )
                } catch (error: Exception) {
                    closeResources()
                    callback(Result.failure(error))
                }
            }
        } catch (error: RejectedExecutionException) {
            initializationStarted = false
            callback(Result.failure(error))
        }
    }

    /**
     * Acquire the fixed NCHW buffer on the camera/analyzer worker. Returns null while the
     * provider is uninitialized, closed, or processing another frame.
     */
    fun acquireInputBuffer(): FloatArray? {
        if (!ready || closed.get()) return null
        if (!inputLeaseActive.compareAndSet(false, true)) return null
        return inputScratch
    }

    /** Discard an acquired input lease when preprocessing fails. */
    fun discardInputBuffer() {
        inputLeaseActive.set(false)
    }

    /**
     * Submit the currently leased input buffer. It is released after decode or failure.
     * Callback executes on the LiteRT worker thread, not the Android main thread.
     */
    fun submit(
        crop: HandRoiCrop,
        frameTimestampMs: Long,
        roiConfidence: Float,
        callback: (Result<RtmposeInferenceResult>) -> Unit
    ) {
        if (closed.get() || !ready || !inputLeaseActive.get()) {
            inputLeaseActive.set(false)
            callback(Result.failure(IllegalStateException("RTMPose input buffer is not leased or runner is not ready.")))
            return
        }
        try {
            executor.execute {
                try {
                    check(!closed.get()) { "RTMPose runner was closed before inference." }
                    val activeModel = checkNotNull(model)
                    check(inputBufferSize() == INPUT_ELEMENT_COUNT) {
                        "RTMPose input tensor does not match [1,3,256,256]."
                    }
                    inputBuffers.single().writeFloat(inputScratch)

                    val startedAt = SystemClock.elapsedRealtimeNanos()
                    activeModel.run(inputBuffers, outputBuffers)
                    val simccX = outputBuffers[SIMCC_X_OUTPUT_INDEX].readFloat()
                    val simccY = outputBuffers[SIMCC_Y_OUTPUT_INDEX].readFloat()
                    val latencyMs =
                        ((SystemClock.elapsedRealtimeNanos() - startedAt) /
                            NANOS_PER_MILLISECOND).coerceAtLeast(0L)

                    val decoded = RtmposeHandOutputDecoder.decode(
                        outputs = RtmposeHandSimccTensors(
                            simccX = LiteRtFloatTensor(
                                intArrayOf(1, RtmposeHandOutputDecoder.LANDMARK_COUNT, RtmposeHandOutputDecoder.SIMCC_BIN_COUNT),
                                simccX
                            ),
                            simccY = LiteRtFloatTensor(
                                intArrayOf(1, RtmposeHandOutputDecoder.LANDMARK_COUNT, RtmposeHandOutputDecoder.SIMCC_BIN_COUNT),
                                simccY
                            )
                        ),
                        crop = crop,
                        frameTimestampMs = frameTimestampMs,
                        pipelineLatencyMs = latencyMs,
                        roiConfidence = roiConfidence
                    )
                    callback(
                        Result.success(
                            RtmposeInferenceResult(
                                decoded = decoded,
                                endToEndInferenceLatencyMs = latencyMs
                            )
                        )
                    )
                } catch (error: Exception) {
                    callback(Result.failure(error))
                } finally {
                    inputLeaseActive.set(false)
                }
            }
        } catch (error: RejectedExecutionException) {
            inputLeaseActive.set(false)
            callback(Result.failure(error))
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready = false
        try {
            executor.execute {
                closeResources()
                executor.shutdown()
            }
        } catch (_: RejectedExecutionException) {
            closeResources()
            executor.shutdown()
        }
    }

    private fun inputBufferSize(): Int =
        inputBuffers.singleOrNull()?.readFloat()?.size ?: 0

    /** Must be invoked on the owning executor after the model is no longer running. */
    private fun closeResources() {
        ready = false
        inputBuffers.forEach { runCatching { it.close() } }
        outputBuffers.forEach { runCatching { it.close() } }
        inputBuffers = emptyList()
        outputBuffers = emptyList()
        runCatching { model?.close() }
        model = null
        runCatching { environment?.close() }
        environment = null
    }

    companion object {
        const val INPUT_SIZE_PX = 256
        const val INPUT_ELEMENT_COUNT = 3 * INPUT_SIZE_PX * INPUT_SIZE_PX
        const val OUTPUT_ELEMENT_COUNT = 21 * 512
        const val EXPECTED_OUTPUT_COUNT = 2
        const val SIMCC_X_OUTPUT_INDEX = 0
        const val SIMCC_Y_OUTPUT_INDEX = 1
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
