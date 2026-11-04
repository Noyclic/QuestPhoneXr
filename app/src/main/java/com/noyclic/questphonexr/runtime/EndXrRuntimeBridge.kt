package com.noyclic.questphonexr.runtime

data class EndXrFrameTiming(
    val frameIndex: Long,
    val predictedDisplayTimeNanos: Long,
    val predictedDisplayPeriodNanos: Long,
    val frameDeadlineNanos: Long
)
data class EndXrRuntimeSnapshot(
    val state: EndXrRuntimeState,
    /** Recommended render target width for one eye. */
    val eyeWidth: Int,
    /** Recommended render target height for one eye. */
    val eyeHeight: Int,
    val targetFps: Int,
    val framesStarted: Long,
    val submittedFrames: Long,
    val droppedFrames: Long,
    val lateFrames: Long,
    val lastLatenessNanos: Long
) {
    /** Backwards-compatible alias used by the Sandbox status panel. */
    val completedPhoneFrames: Long get() = submittedFrames
}
enum class EndXrRuntimeState {
    COLD, INITIALIZED, SESSION_READY, RUNNING, STOPPING, LOST;
    companion object {
        fun fromNativeCode(code: Long): EndXrRuntimeState =
            values().getOrNull(code.toInt()) ?: COLD
    }
}
data class EndXrFrameWaitResult(val timing: EndXrFrameTiming?, val error: String?) {
    val isSuccess: Boolean get() = timing != null && error == null
}
/**
 * Thin JNI boundary for the app-owned EndXR core.
 * State-changing calls run on the Sandbox GL thread; the native core is
 * synchronized so the UI can safely read a diagnostic snapshot.
 */
class EndXrRuntimeBridge {
    private val nativeLoadError: String?
    init {
        nativeLoadError = try {
            System.loadLibrary("questphonexr_native")
            null
        } catch (error: LinkageError) {
            error.message ?: error.javaClass.simpleName
        } catch (error: SecurityException) {
            error.message ?: error.javaClass.simpleName
        }
    }
    val isAvailable: Boolean get() = nativeLoadError == null
    fun initialize(width: Int, height: Int, targetFps: Int): String? =
        invokeNative { nativeInitialize(width, height, targetFps) }
    fun createSession(): String? = invokeNative { nativeCreateSession() }
    fun beginSession(): String? = invokeNative { nativeBeginSession() }
    fun waitFrame(monotonicNowNanos: Long): EndXrFrameWaitResult {
        if (!isAvailable) return EndXrFrameWaitResult(
            null, nativeLoadError ?: "Native EndXR library is unavailable."
        )
        return try {
            val values = nativeWaitFrame(monotonicNowNanos)
            if (values == null || values.size < 4) {
                EndXrFrameWaitResult(null, nativeLastError().orEmpty().ifBlank {
                    "EndXR returned an invalid frame timing result."
                })
            } else {
                EndXrFrameWaitResult(
                    EndXrFrameTiming(values[0], values[1], values[2], values[3]), null
                )
            }
        } catch (error: LinkageError) {
            EndXrFrameWaitResult(null, error.message ?: error.javaClass.simpleName)
        } catch (error: RuntimeException) {
            EndXrFrameWaitResult(null, error.message ?: error.javaClass.simpleName)
        }
    }
    fun endFrame(timing: EndXrFrameTiming, renderedSuccessfully: Boolean): String? =
        invokeNative {
            nativeEndFrame(
                timing.frameIndex, timing.predictedDisplayTimeNanos,
                timing.predictedDisplayPeriodNanos, timing.frameDeadlineNanos,
                renderedSuccessfully
            )
        }
    fun requestStop(): String? = invokeNative { nativeRequestStop() }
    fun destroySession(): String? = invokeNative { nativeDestroySession() }
    fun markLost(reason: String): String? = invokeNative { nativeMarkLost(reason) }
    fun shutdown() {
        if (!isAvailable) return
        runCatching { nativeShutdown() }
    }
    fun snapshot(): EndXrRuntimeSnapshot? {
        if (!isAvailable) return null
        return try { parseEndXrRuntimeSnapshot(nativeSnapshot()) }
        catch (_: LinkageError) { null }
        catch (_: RuntimeException) { null }
    }
    private inline fun invokeNative(operation: () -> String?): String? {
        if (!isAvailable) return nativeLoadError ?: "Native EndXR library is unavailable."
        return try { operation() }
        catch (error: LinkageError) { error.message ?: error.javaClass.simpleName }
        catch (error: RuntimeException) { error.message ?: error.javaClass.simpleName }
    }
    private external fun nativeInitialize(width: Int, height: Int, targetFps: Int): String?
    private external fun nativeCreateSession(): String?
    private external fun nativeBeginSession(): String?
    private external fun nativeWaitFrame(monotonicNowNanos: Long): LongArray?
    private external fun nativeEndFrame(
        frameIndex: Long, predictedDisplayTimeNanos: Long,
        predictedDisplayPeriodNanos: Long, frameDeadlineNanos: Long,
        renderedSuccessfully: Boolean
    ): String?
    private external fun nativeRequestStop(): String?
    private external fun nativeDestroySession(): String?
    private external fun nativeMarkLost(reason: String): String?
    private external fun nativeShutdown()
    private external fun nativeSnapshot(): LongArray?
    private external fun nativeLastError(): String?
}
/** Defensive parser shared by the bridge and its JVM tests. */
internal fun parseEndXrRuntimeSnapshot(values: LongArray?): EndXrRuntimeSnapshot? {
    if (values == null || values.size < 9) return null
    return EndXrRuntimeSnapshot(
        state = EndXrRuntimeState.fromNativeCode(values[0]),
        eyeWidth = values[1].toInt(),
        eyeHeight = values[2].toInt(),
        targetFps = values[3].toInt(),
        framesStarted = values[4],
        submittedFrames = values[5],
        droppedFrames = values[6],
        lateFrames = values[7],
        lastLatenessNanos = values[8]
    )
}
