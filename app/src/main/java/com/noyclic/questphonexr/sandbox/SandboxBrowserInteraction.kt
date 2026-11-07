package com.noyclic.questphonexr.sandbox

import kotlin.math.abs
import kotlin.math.roundToInt

/** Coordinates of the world-locked browser panel rendered by SandboxRenderer. */
internal object SandboxBrowserInteraction {
    const val TEXTURE_WIDTH = 960
    const val TEXTURE_HEIGHT = 640

    const val PANEL_CENTER_X = 0f
    const val PANEL_CENTER_Y = 0.28f
    const val PANEL_CENTER_Z = -2.72f
    const val PANEL_WIDTH = 2.45f
    const val PANEL_HEIGHT = 1.65f

    private const val MAX_DEPTH_DELTA = 1.25f

    data class PixelPoint(val x: Float, val y: Float)

    /**
     * Maps the camera-derived cursor onto the browser plane. Dragging can clamp
     * at the edge so a held pinch continues delivering a valid Android pointer.
     */
    /** Convert a texture coordinate back to a point just in front of the 3D panel. */
    fun toWorld(point: PixelPoint, forwardOffsetMeters: Float = 0.022f): SandboxVector3 {
        val u = (point.x / (TEXTURE_WIDTH - 1)).coerceIn(0f, 1f)
        val v = (point.y / (TEXTURE_HEIGHT - 1)).coerceIn(0f, 1f)
        return SandboxVector3(
            x = PANEL_CENTER_X - PANEL_WIDTH * 0.5f + u * PANEL_WIDTH,
            y = PANEL_CENTER_Y + PANEL_HEIGHT * 0.5f - v * PANEL_HEIGHT,
            z = PANEL_CENTER_Z + forwardOffsetMeters.coerceIn(0f, 0.08f)
        )
    }

    fun map(cursor: SandboxVector3, clampToPanel: Boolean = false): PixelPoint? {
        if (!cursor.x.isFinite() || !cursor.y.isFinite() || !cursor.z.isFinite()) return null
        if (abs(cursor.z - PANEL_CENTER_Z) > MAX_DEPTH_DELTA) return null

        val left = PANEL_CENTER_X - PANEL_WIDTH * 0.5f
        val top = PANEL_CENTER_Y + PANEL_HEIGHT * 0.5f
        val u = (cursor.x - left) / PANEL_WIDTH
        val v = (top - cursor.y) / PANEL_HEIGHT
        if (!clampToPanel && (u !in 0f..1f || v !in 0f..1f)) return null

        val safeU = u.coerceIn(0f, 1f)
        val safeV = v.coerceIn(0f, 1f)
        return PixelPoint(
            x = (safeU * (TEXTURE_WIDTH - 1)).roundToInt().toFloat(),
            y = (safeV * (TEXTURE_HEIGHT - 1)).roundToInt().toFloat()
        )
    }
}
