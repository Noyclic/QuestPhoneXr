package com.noyclic.questphonexr.input

/**
 * Normalizes an Android analog trigger according to the MotionRange reported by its device.
 * Supports both the common 0..1 trigger range and HID devices that report -1..1.
 */
object GamepadAxisNormalizer {
    fun normalizeTrigger(value: Float, min: Float, max: Float): Float {
        if (!value.isFinite() || !min.isFinite() || !max.isFinite()) return 0f
        val span = max - min
        if (!span.isFinite() || span <= MIN_RANGE) return 0f
        return ((value.coerceIn(min, max) - min) / span).coerceIn(0f, 1f)
    }

    private const val MIN_RANGE = 0.0001f
}
