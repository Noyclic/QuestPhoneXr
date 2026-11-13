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
        destinationRgb: FloatArray,
        sourceRotationDegrees: Int = 0
    ): Boolean {
        val rotation = normalizeRightAngleRotation(sourceRotationDegrees) ?: return false
        if (outputWidthPx <= 0 || outputHeightPx <= 0) return false
        val rawDimensions = rawDimensionsFor(crop, rotation)
        val requiredSourcePixels = rawDimensions.first.toLong() * rawDimensions.second.toLong()
        val requiredDestinationValues =
            outputWidthPx.toLong() * outputHeightPx.toLong() * CHANNELS_PER_PIXEL
        if (requiredSourcePixels > sourceArgb.size.toLong() ||
            requiredDestinationValues > destinationRgb.size.toLong()
        ) return false

        val cropRight = crop.leftPx + crop.sizePx - 1
        val cropBottom = crop.topPx + crop.sizePx - 1
        var outputIndex = 0
        for (outputY in 0 until outputHeightPx) {
            val sourceY = (
                crop.topPx + (outputY + 0.5f) * crop.sizePx / outputHeightPx.toFloat() - 0.5f
                ).coerceIn(crop.topPx.toFloat(), cropBottom.toFloat())
            val y0 = floor(sourceY).toInt().coerceIn(crop.topPx, cropBottom)
            val y1 = (y0 + 1).coerceAtMost(cropBottom)
            val yWeight = (sourceY - y0).coerceIn(0f, 1f)
            for (outputX in 0 until outputWidthPx) {
                val sourceX = (
                    crop.leftPx + (outputX + 0.5f) * crop.sizePx / outputWidthPx.toFloat() - 0.5f
                    ).coerceIn(crop.leftPx.toFloat(), cropRight.toFloat())
                val x0 = floor(sourceX).toInt().coerceIn(crop.leftPx, cropRight)
                val x1 = (x0 + 1).coerceAtMost(cropRight)
                val xWeight = (sourceX - x0).coerceIn(0f, 1f)
                val pixels = sampleFourRotatedPixels(
                    sourceArgb, rawDimensions.first, rawDimensions.second,
                    x0, x1, y0, y1, rotation
                )
                destinationRgb[outputIndex++] = interpolateChannel(
                    pixels[0] ushr 16 and 0xff, pixels[1] ushr 16 and 0xff,
                    pixels[2] ushr 16 and 0xff, pixels[3] ushr 16 and 0xff,
                    xWeight, yWeight
                )
                destinationRgb[outputIndex++] = interpolateChannel(
                    pixels[0] ushr 8 and 0xff, pixels[1] ushr 8 and 0xff,
                    pixels[2] ushr 8 and 0xff, pixels[3] ushr 8 and 0xff,
                    xWeight, yWeight
                )
                destinationRgb[outputIndex++] = interpolateChannel(
                    pixels[0] and 0xff, pixels[1] and 0xff,
                    pixels[2] and 0xff, pixels[3] and 0xff,
                    xWeight, yWeight
                )
            }
        }
        return true
    }

    /** Writes ImageNet-normalized RGB NCHW values, sampling raw CameraX pixels at any right-angle rotation. */
    fun writeRtmposeImageNetNchwFloat(
        sourceArgb: IntArray,
        crop: HandRoiCrop,
        outputSizePx: Int,
        destinationNchw: FloatArray,
        sourceRotationDegrees: Int = 0
    ): Boolean {
        val rotation = normalizeRightAngleRotation(sourceRotationDegrees) ?: return false
        if (outputSizePx <= 0) return false
        val rawDimensions = rawDimensionsFor(crop, rotation)
        val requiredSourcePixels = rawDimensions.first.toLong() * rawDimensions.second.toLong()
        val planeSizeLong = outputSizePx.toLong() * outputSizePx.toLong()
        if (requiredSourcePixels > sourceArgb.size.toLong() ||
            planeSizeLong * CHANNELS_PER_PIXEL > destinationNchw.size.toLong() ||
            planeSizeLong > Int.MAX_VALUE
        ) return false

        val planeSize = planeSizeLong.toInt()
        val cropRight = crop.leftPx + crop.sizePx - 1
        val cropBottom = crop.topPx + crop.sizePx - 1
        for (outputY in 0 until outputSizePx) {
            val sourceY = (
                crop.topPx + (outputY + 0.5f) * crop.sizePx / outputSizePx.toFloat() - 0.5f
                ).coerceIn(crop.topPx.toFloat(), cropBottom.toFloat())
            val y0 = floor(sourceY).toInt().coerceIn(crop.topPx, cropBottom)
            val y1 = (y0 + 1).coerceAtMost(cropBottom)
            val yWeight = (sourceY - y0).coerceIn(0f, 1f)
            for (outputX in 0 until outputSizePx) {
                val sourceX = (
                    crop.leftPx + (outputX + 0.5f) * crop.sizePx / outputSizePx.toFloat() - 0.5f
                    ).coerceIn(crop.leftPx.toFloat(), cropRight.toFloat())
                val x0 = floor(sourceX).toInt().coerceIn(crop.leftPx, cropRight)
                val x1 = (x0 + 1).coerceAtMost(cropRight)
                val xWeight = (sourceX - x0).coerceIn(0f, 1f)
                val pixels = sampleFourRotatedPixels(
                    sourceArgb, rawDimensions.first, rawDimensions.second,
                    x0, x1, y0, y1, rotation
                )
                val pixelIndex = outputY * outputSizePx + outputX
                val red = interpolateChannel(
                    pixels[0] ushr 16 and 0xff, pixels[1] ushr 16 and 0xff,
                    pixels[2] ushr 16 and 0xff, pixels[3] ushr 16 and 0xff,
                    xWeight, yWeight
                )
                val green = interpolateChannel(
                    pixels[0] ushr 8 and 0xff, pixels[1] ushr 8 and 0xff,
                    pixels[2] ushr 8 and 0xff, pixels[3] ushr 8 and 0xff,
                    xWeight, yWeight
                )
                val blue = interpolateChannel(
                    pixels[0] and 0xff, pixels[1] and 0xff,
                    pixels[2] and 0xff, pixels[3] and 0xff,
                    xWeight, yWeight
                )
                destinationNchw[pixelIndex] = (red - RTMPOSE_MEAN_R) / RTMPOSE_STD_R
                destinationNchw[planeSize + pixelIndex] = (green - RTMPOSE_MEAN_G) / RTMPOSE_STD_G
                destinationNchw[2 * planeSize + pixelIndex] = (blue - RTMPOSE_MEAN_B) / RTMPOSE_STD_B
            }
        }
        return true
    }

    private fun normalizeRightAngleRotation(rotationDegrees: Int): Int? {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        return normalized.takeIf { it == 0 || it == 90 || it == 180 || it == 270 }
    }

    private fun rawDimensionsFor(crop: HandRoiCrop, rotation: Int): Pair<Int, Int> =
        when (rotation) {
            0, 180 -> crop.sourceWidthPx to crop.sourceHeightPx
            90, 270 -> crop.sourceHeightPx to crop.sourceWidthPx
            else -> error("Unsupported source rotation: $rotation")
        }

    private fun sampleFourRotatedPixels(
        sourceArgb: IntArray,
        rawWidth: Int,
        rawHeight: Int,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
        rotation: Int
    ): IntArray = intArrayOf(
        argbAtRotated(sourceArgb, rawWidth, rawHeight, x0, y0, rotation),
        argbAtRotated(sourceArgb, rawWidth, rawHeight, x1, y0, rotation),
        argbAtRotated(sourceArgb, rawWidth, rawHeight, x0, y1, rotation),
        argbAtRotated(sourceArgb, rawWidth, rawHeight, x1, y1, rotation)
    )

    private fun argbAtRotated(
        sourceArgb: IntArray,
        rawWidth: Int,
        rawHeight: Int,
        rotatedX: Int,
        rotatedY: Int,
        rotation: Int
    ): Int {
        val (rawX, rawY) = when (rotation) {
            0 -> rotatedX to rotatedY
            90 -> rotatedY to (rawHeight - 1 - rotatedX)
            180 -> (rawWidth - 1 - rotatedX) to (rawHeight - 1 - rotatedY)
            270 -> (rawWidth - 1 - rotatedY) to rotatedX
            else -> error("Unsupported source rotation: $rotation")
        }
        return sourceArgb[rawY * rawWidth + rawX]
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
    private const val RTMPOSE_MEAN_R = 123.675f
    private const val RTMPOSE_MEAN_G = 116.28f
    private const val RTMPOSE_MEAN_B = 103.53f
    private const val RTMPOSE_STD_R = 58.395f
    private const val RTMPOSE_STD_G = 57.12f
    private const val RTMPOSE_STD_B = 57.375f
}
