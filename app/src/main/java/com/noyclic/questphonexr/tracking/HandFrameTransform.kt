package com.noyclic.questphonexr.tracking

/**
 * Maps normalized model coordinates back to the normalized, rotated camera frame.
 * The camera image is letterboxed into the model input; black-bar coordinates are clamped.
 */
data class HandFrameTransform(
    val leftPx: Float,
    val topPx: Float,
    val scale: Float,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
    val rotatedWidthPx: Int,
    val rotatedHeightPx: Int
) {
    init {
        require(scale > 0f)
        require(targetWidthPx > 0 && targetHeightPx > 0)
        require(rotatedWidthPx > 0 && rotatedHeightPx > 0)
    }

    fun toSourceNormalized(x: Float, y: Float): Pair<Float, Float> {
        val rotatedX = ((x * targetWidthPx - leftPx) / scale)
            .coerceIn(0f, rotatedWidthPx.toFloat())
        val rotatedY = ((y * targetHeightPx - topPx) / scale)
            .coerceIn(0f, rotatedHeightPx.toFloat())
        return (rotatedX / rotatedWidthPx).coerceIn(0f, 1f) to
            (rotatedY / rotatedHeightPx).coerceIn(0f, 1f)
    }
}


/**
 * Normalizes CameraX's clockwise rotation to the four right-angle transforms supported
 * by the bitmap canvas pipeline. Unexpected values safely fall back to no rotation.
 */
internal fun normalizeCameraRotationDegrees(rotationDegrees: Int): Int {
    val normalized = ((rotationDegrees % 360) + 360) % 360
    return if (normalized % 90 == 0) normalized else 0
}
