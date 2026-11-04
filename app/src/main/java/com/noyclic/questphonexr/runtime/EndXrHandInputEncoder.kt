package com.noyclic.questphonexr.runtime

import com.noyclic.questphonexr.input.ControllerInputSource
import com.noyclic.questphonexr.input.VirtualControllerMapper
import com.noyclic.questphonexr.input.VirtualControllerState
import com.noyclic.questphonexr.tracking.HandTrackingFrame

/** Converts camera-only MediaPipe results into the stable six-value EndXR IPC row. */
internal object EndXrHandInputEncoder {
    private val mapper = VirtualControllerMapper()

    fun encode(frame: HandTrackingFrame): Pair<FloatArray, FloatArray> {
        val mapped = mapper.map(frame, emptyList())
        return encodeState(mapped.left) to encodeState(mapped.right)
    }

    private fun encodeState(state: VirtualControllerState): FloatArray = floatArrayOf(
        if (state.source != ControllerInputSource.NONE) 1f else 0f,
        state.trigger.coerceIn(0f, 1f),
        state.grip.coerceIn(0f, 1f),
        0f,
        0f,
        0f
    )
}
