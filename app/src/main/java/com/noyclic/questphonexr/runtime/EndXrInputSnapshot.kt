package com.noyclic.questphonexr.runtime

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

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
    val stale: Boolean
)

/**
 * This is intentionally in-memory: controller values must never survive a
 * process restart. The store is updated on the Sandbox frame/input path and
 * read by the exported provider on Binder threads.
 */
internal object EndXrInputSnapshotStore {
    const val MAX_SAMPLE_AGE_NANOS = 300_000_000L

    private val sequence = AtomicLong(0L)
    private val state = AtomicReference(
        EndXrInputSnapshot(
            sequence = 0L,
            updatedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            left = EndXrControllerSample.EMPTY,
            right = EndXrControllerSample.EMPTY,
            stale = false
        )
    )

    fun publish(leftRow: FloatArray, rightRow: FloatArray) {
        val timestamp = SystemClock.elapsedRealtimeNanos()
        val nextSequence = sequence.incrementAndGet()
        state.set(
            EndXrInputSnapshot(
                sequence = nextSequence,
                updatedAtElapsedRealtimeNanos = timestamp,
                left = EndXrControllerSample.fromNativeRow(leftRow),
                right = EndXrControllerSample.fromNativeRow(rightRow),
                stale = false
            )
        )
    }

    fun clear() {
        val timestamp = SystemClock.elapsedRealtimeNanos()
        val nextSequence = sequence.incrementAndGet()
        state.set(
            EndXrInputSnapshot(
                sequence = nextSequence,
                updatedAtElapsedRealtimeNanos = timestamp,
                left = EndXrControllerSample.EMPTY,
                right = EndXrControllerSample.EMPTY,
                stale = false
            )
        )
    }

    fun snapshotForIpc(nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()): EndXrInputSnapshot {
        val current = state.get()
        val age = (nowElapsedRealtimeNanos - current.updatedAtElapsedRealtimeNanos)
            .coerceAtLeast(0L)
        val stale = age > MAX_SAMPLE_AGE_NANOS
        if (!stale) return current
        return current.copy(
            left = EndXrControllerSample.EMPTY,
            right = EndXrControllerSample.EMPTY,
            stale = true
        )
    }
}
