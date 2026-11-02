package com.noyclic.questphonexr.input

import kotlin.math.sqrt

data class StickAxes(val x: Float, val y: Float)

/**
 * Applies a radial dead zone and rescales the remaining range back to 0..1.
 *
 * Unlike independent per-axis dead zones, this keeps diagonal movement circular
 * and avoids losing most of the usable stick travel after the dead zone.
 */
object StickAxisFilter {
    const val DEFAULT_DEAD_ZONE = 0.14f

    fun normalize(
        x: Float,
        y: Float,
        deadZone: Float = DEFAULT_DEAD_ZONE
    ): StickAxes {
        require(deadZone >= 0f && deadZone < 1f) {
            "Dead zone must be in the range [0, 1)."
        }

        val safeX = x.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        val safeY = y.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        val magnitude = sqrt(safeX * safeX + safeY * safeY)

        if (magnitude <= deadZone || magnitude <= EPSILON) return StickAxes(0f, 0f)

        val normalizedMagnitude = ((magnitude - deadZone) / (1f - deadZone))
            .coerceIn(0f, 1f)
        val scale = normalizedMagnitude / magnitude
        return StickAxes(
            x = (safeX * scale).coerceIn(-1f, 1f),
            y = (safeY * scale).coerceIn(-1f, 1f)
        )
    }

    private const val EPSILON = 0.00001f
}
