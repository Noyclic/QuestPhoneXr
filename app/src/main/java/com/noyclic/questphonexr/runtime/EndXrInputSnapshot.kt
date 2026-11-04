package com.noyclic.questphonexr.runtime

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import kotlin.math.roundToInt

/**
 * A normalized orientation sample from the phone rotation-vector sensor.
 * It is orientation-only: it contains no absolute position or 6DoF translation.
 */
internal data class EndXrHeadOrientationSample(
    val active: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val w: Float = 1f
) {
    companion object {
        val EMPTY = EndXrHeadOrientationSample()

        fun fromQuaternion(x: Float, y: Float, z: Float, w: Float): EndXrHeadOrientationSample {
            if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !w.isFinite()) return EMPTY
            val magnitude = sqrt(
                x.toDouble() * x + y.toDouble() * y +
                    z.toDouble() * z + w.toDouble() * w
            )
            if (!magnitude.isFinite() || magnitude < 0.5 || magnitude > 1.5) return EMPTY
            return EndXrHeadOrientationSample(
                active = true,
                x = (x / magnitude).toFloat(),
                y = (y / magnitude).toFloat(),
                z = (z / magnitude).toFloat(),
                w = (w / magnitude).toFloat()
            )
        }
    }
}

/**
 * A short-lived, process-safe snapshot of the controls QuestPhoneXR currently
 * observes. The ContentProvider exposes this snapshot to the OpenXR runtime
 * after it has been loaded inside a different Android application process.
 */
internal data class EndXrControllerSample(
    val active: Boolean = false,
    val trigger: Float = 0f,
    val grip: Float = 0f,
    val stickX: Float = 0f,
    val stickY: Float = 0f,
    val buttons: Int = 0
) {
    companion object {
        fun fromNativeRow(row: FloatArray): EndXrControllerSample {
            require(row.size >= 6) { "EndXR controller row must contain six values." }
            fun finite(value: Float): Float = if (value.isFinite()) value else 0f
            return EndXrControllerSample(
                active = finite(row[0]) >= 0.5f,
                trigger = finite(row[1]).coerceIn(0f, 1f),
                grip = finite(row[2]).coerceIn(0f, 1f),
                stickX = finite(row[3]).coerceIn(-1f, 1f),
                stickY = finite(row[4]).coerceIn(-1f, 1f),
                buttons = finite(row[5]).coerceAtLeast(0f).roundToInt()
            ).let { sample ->
                if (sample.active) sample else EMPTY
            }
        }

        val EMPTY = EndXrControllerSample()
    }
}

internal data class EndXrInputSnapshot(
    val sequence: Long,
    val updatedAtElapsedRealtimeNanos: Long,
    val left: EndXrControllerSample,
    val right: EndXrControllerSample,
    val stale: Boolean,
    val headOrientation: EndXrHeadOrientationSample,
    val headOrientationUpdatedAtElapsedRealtimeNanos: Long,
    val headOrientationStale: Boolean
)

/**
 * This is intentionally in-memory: controller values must never survive a
 * process restart. Camera and sensor callbacks publish independently; CAS
 * updates prevent one producer from accidentally overwriting the other.
 */
internal object EndXrInputSnapshotStore {
    const val MAX_SAMPLE_AGE_NANOS = 300_000_000L
    const val MAX_HEAD_ORIENTATION_AGE_NANOS = 500_000_000L

    private val sequence = AtomicLong(0L)
    private val state = AtomicReference(
        EndXrInputSnapshot(
            sequence = 0L,
            updatedAtElapsedRealtimeNanos = 0L,
            left = EndXrControllerSample.EMPTY,
            right = EndXrControllerSample.EMPTY,
            stale = true,
            headOrientation = EndXrHeadOrientationSample.EMPTY,
            headOrientationUpdatedAtElapsedRealtimeNanos = 0L,
            headOrientationStale = true
        )
    )

    private inline fun update(transform: (EndXrInputSnapshot) -> EndXrInputSnapshot) {
        while (true) {
            val current = state.get()
            val next = transform(current).copy(sequence = sequence.incrementAndGet())
            if (state.compareAndSet(current, next)) return
        }
    }

    fun publish(
        leftRow: FloatArray,
        rightRow: FloatArray,
        nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ) {
        val timestamp = nowElapsedRealtimeNanos.coerceAtLeast(0L)
        val left = EndXrControllerSample.fromNativeRow(leftRow)
        val right = EndXrControllerSample.fromNativeRow(rightRow)
        update { current ->
            current.copy(
                updatedAtElapsedRealtimeNanos = timestamp,
                left = left,
                right = right,
                stale = false
            )
        }
    }

    fun publishHeadOrientation(
        x: Float,
        y: Float,
        z: Float,
        w: Float,
        nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ) {
        val timestamp = nowElapsedRealtimeNanos.coerceAtLeast(0L)
        val orientation = EndXrHeadOrientationSample.fromQuaternion(x, y, z, w)
        update { current ->
            current.copy(
                headOrientation = orientation,
                headOrientationUpdatedAtElapsedRealtimeNanos = timestamp,
                headOrientationStale = !orientation.active
            )
        }
    }

    fun clear(nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()) {
        val timestamp = nowElapsedRealtimeNanos.coerceAtLeast(0L)
        update {
            it.copy(
                updatedAtElapsedRealtimeNanos = timestamp,
                left = EndXrControllerSample.EMPTY,
                right = EndXrControllerSample.EMPTY,
                stale = false,
                headOrientation = EndXrHeadOrientationSample.EMPTY,
                headOrientationUpdatedAtElapsedRealtimeNanos = timestamp,
                headOrientationStale = true
            )
        }
    }

    fun snapshotForIpc(nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()): EndXrInputSnapshot {
        val current = state.get()
        val now = nowElapsedRealtimeNanos.coerceAtLeast(0L)
        val controllerAge = (now - current.updatedAtElapsedRealtimeNanos).coerceAtLeast(0L)
        val headAge = (now - current.headOrientationUpdatedAtElapsedRealtimeNanos).coerceAtLeast(0L)
        val controllerStale = controllerAge > MAX_SAMPLE_AGE_NANOS
        val headStale = current.headOrientationStale ||
            headAge > MAX_HEAD_ORIENTATION_AGE_NANOS
        return current.copy(
            left = if (controllerStale) EndXrControllerSample.EMPTY else current.left,
            right = if (controllerStale) EndXrControllerSample.EMPTY else current.right,
            stale = controllerStale,
            headOrientation = if (headStale) EndXrHeadOrientationSample.EMPTY else current.headOrientation,
            headOrientationStale = headStale
        )
    }
}
