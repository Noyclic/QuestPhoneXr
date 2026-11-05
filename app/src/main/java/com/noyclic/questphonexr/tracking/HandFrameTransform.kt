package com.noyclic.questphonexr.tracking

/**
 * Maps normalized model coordinates back to the original CameraX image.
 *
 * The model image is letterboxed, rotated and optionally mirrored. Undo those
 * operations in reverse order so downstream tracking always receives coordinates
 * in the same source-image coordinate system, regardless of device orientation.
 */
data class HandFrameTransform(
    val leftPx: Float,
    val topPx: Float,
    val scale: Float,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
    val rotatedWidthPx: Int,
    val rotatedHeightPx: Int,
    val mirroredHorizontally: Boolean = false,
    val rotationDegrees: Int = 0
) {
    init {
        require(scale > 0f)
        require(targetWidthPx > 0 && targetHeightPx > 0)
        require(rotatedWidthPx > 0 && rotatedHeightPx > 0)
    }

    fun toSourceNormalized(x: Float, y: Float): Pair<Float, Float> {
        val rotatedX = ((x * targetWidthPx - leftPx) / scale)
            .coerceIn(0f, rotatedWidthPx.toFloat()) / rotatedWidthPx
        val rotatedY = ((y * targetHeightPx - topPx) / scale)
            .coerceIn(0f, rotatedHeightPx.toFloat()) / rotatedHeightPx

        // Mirroring is applied after rotation when drawing the model bitmap.
        val unmirroredX = if (mirroredHorizontally) 1f - rotatedX else rotatedX
        return when (normalizeCameraRotationDegrees(rotationDegrees)) {
            90 -> rotatedY to (1f - unmirroredX)
            180 -> (1f - unmirroredX) to (1f - rotatedY)
            270 -> (1f - rotatedY) to unmirroredX
            else -> unmirroredX to rotatedY
        }.let { (sourceX, sourceY) ->
            sourceX.coerceIn(0f, 1f) to sourceY.coerceIn(0f, 1f)
        }
    }
}

/** Normalize CameraX rotation to the right-angle transforms supported by the bitmap pipeline. */
internal fun normalizeCameraRotationDegrees(rotationDegrees: Int): Int {
    val normalized = ((rotationDegrees % 360) + 360) % 360
    return if (normalized % 90 == 0) normalized else 0
}
