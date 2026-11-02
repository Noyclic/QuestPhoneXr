package com.noyclic.questphonexr.sandbox

/** Low-pass filter for normalized camera-hand cursor motion. */
class SandboxCursorSmoother(
    private val response: Float = DEFAULT_RESPONSE
) {
    private var previous: SandboxVector3? = null

    init {
        require(response > 0f && response <= 1f) {
            "Cursor smoothing response must be in the range (0, 1]."
        }
    }

    fun update(target: SandboxVector3): SandboxVector3 {
        val current = previous
        val smoothed = if (current == null) {
            target
        } else {
            current + (target - current) * response
        }
        previous = smoothed
        return smoothed
    }

    fun reset() {
        previous = null
    }

    private companion object {
        const val DEFAULT_RESPONSE = 0.45f
    }
}
