package com.noyclic.questphonexr.sandbox

/** Finite-safe low-pass filter for normalized camera-hand cursor motion. */
internal fun SandboxVector3.hasFiniteCoordinates(): Boolean =
    x.isFinite() && y.isFinite() && z.isFinite()
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
        if (!target.hasFiniteCoordinates()) {
            // Hold the last valid cursor instead of poisoning future frames.
            return current ?: SandboxVector3(0f, 0f, 0f)
        }
        val smoothed = if (current == null) {
            target
        } else {
            current + ((target - current) * response).clamped(MAX_STEP_PER_UPDATE)
        }
        previous = smoothed
        return smoothed
    }

    fun reset() {
        previous = null
    }

    private companion object {
        const val DEFAULT_RESPONSE = 0.45f
        const val MAX_STEP_PER_UPDATE = 0.75f
    }
}
