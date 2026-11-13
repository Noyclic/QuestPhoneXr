package com.noyclic.questphonexr.tracking

import kotlin.math.floor

/**
 * Reusable RGB input writer for top-down hand models.
 *
 * The source is an ARGB_8888 pixel array, such as one filled by Bitmap.getPixels(). The
 * destination is interleaved RGB float data in the 0..255 range. LiteRT's TensorBuffer
 * performs model-specific quantization when writeFloat() is used; do not hard-code an
 * int8 zero point here.
 *
 * The caller owns both arrays and should reuse them across frames to avoid per-frame
 * allocations. This class performs no Android Bitmap or CameraX work.
 */
object HandRoiRgbPreprocessor {

    fun writeRgbFloat(
        sourceArgb: IntArray,
        crop: HandRoiCrop,
        outputWidthPx: Int,
        outputHeightPx: Int,
        destinationRgb: FloatArray
    ): Boolean {
        if (outputWidthPx <= 0 || outputHeightPx <= 0) return false

        val requiredSourcePixels = crop.sourceWidthPx.toLong() * crop.sourceHeightPx.toLong()
        val requiredDestinationValues =
            outputWidthPx.toLong() * outputHeightPx.toLong() * CHANNELS_PER_PIXEL
        if (requiredSourcePixels > sourceArgb.size.toLong() ||
            requiredDestinationValues > destinationRgb.size.toLong()
        ) {
            return false
        }

        val cropRight = crop.leftPx + crop.sizePx - 1
        val cropBottom = crop.topPx + crop.sizePx - 1
        var outputIndex = 0

        for (outputY in 0 until outputHeightPx) {
            val sourceY = (
                crop.topPx +
                    (outputY + 0.5f) * crop.sizePx / outputHeightPx.toFloat() -
                    0.5f
                ).coerceIn(crop.topPx.toFloat(), cropBottom.toFloat())
            val y0 = floor(sourceY).toInt().coerceIn(crop.topPx, cropBottom)
            val y1 = (y0 + 1).coerceAtMost(cropBottom)
            val yWeight = (sourceY - y0).coerceIn(0f, 1f)

            for (outputX in 0 until outputWidthPx) {
                val sourceX = (
                    crop.leftPx +
                        (outputX + 0.5f) * crop.sizePx / outputWidthPx.toFloat() -
                        0.5f
                    ).coerceIn(crop.leftPx.toFloat(), cropRight.toFloat())
                val x0 = floor(sourceX).toInt().coerceIn(crop.leftPx, cropRight)
                val x1 = (x0 + 1).coerceAtMost(cropRight)
                val xWeight = (sourceX - x0).coerceIn(0f, 1f)

                val topLeft = sourceArgb[y0 * crop.sourceWidthPx + x0]
                val topRight = sourceArgb[y0 * crop.sourceWidthPx + x1]
                val bottomLeft = sourceArgb[y1 * crop.sourceWidthPx + x0]
                val bottomRight = sourceArgb[y1 * crop.sourceWidthPx + x1]

                destinationRgb[outputIndex++] = interpolateChannel(
                    topLeft ushr 16 and 0xff,
                    topRight ushr 16 and 0xff,
                    bottomLeft ushr 16 and 0xff,
                    bottomRight ushr 16 and 0xff,
                    xWeight,
                    yWeight
                )
                destinationRgb[outputIndex++] = interpolateChannel(
                    topLeft ushr 8 and 0xff,
                    topRight ushr 8 and 0xff,
                    bottomLeft ushr 8 and 0xff,
                    bottomRight ushr 8 and 0xff,
                    xWeight,
                    yWeight
                )
                destinationRgb[outputIndex++] = interpolateChannel(
                    topLeft and 0xff,
                    topRight and 0xff,
                    bottomLeft and 0xff,
                    bottomRight and 0xff,
                    xWeight,
                    yWeight
                )
            }
        }
        return true
    }

    private fun interpolateChannel(
        topLeft: Int,
        topRight: Int,
        bottomLeft: Int,
        bottomRight: Int,
        xWeight: Float,
        yWeight: Float
    ): Float {
        val top = topLeft + (topRight - topLeft) * xWeight
        val bottom = bottomLeft + (bottomRight - bottomLeft) * xWeight
        return top + (bottom - top) * yWeight
    }

    private const val CHANNELS_PER_PIXEL = 3
}
