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

    /**
     * Writes RTMPose-Hand's ImageNet-normalized NCHW float input directly from the crop.
     *
     * The model expects [1,3,256,256], RGB values normalized with the MMPose mean/std.
     * Destination layout is channel-major R plane, G plane, B plane. Caller owns and reuses
     * this buffer; no intermediate HWC array is allocated.
     */
    fun writeRtmposeImageNetNchwFloat(
        sourceArgb: IntArray,
        crop: HandRoiCrop,
        outputSizePx: Int,
        destinationNchw: FloatArray
    ): Boolean {
        if (outputSizePx <= 0) return false
        val requiredSourcePixels = crop.sourceWidthPx.toLong() * crop.sourceHeightPx.toLong()
        val planeSizeLong = outputSizePx.toLong() * outputSizePx.toLong()
        val requiredDestinationValues = planeSizeLong * CHANNELS_PER_PIXEL
        if (requiredSourcePixels > sourceArgb.size.toLong() ||
            requiredDestinationValues > destinationNchw.size.toLong() ||
            planeSizeLong > Int.MAX_VALUE
        ) {
            return false
        }

        val planeSize = planeSizeLong.toInt()
        val cropRight = crop.leftPx + crop.sizePx - 1
        val cropBottom = crop.topPx + crop.sizePx - 1
        for (outputY in 0 until outputSizePx) {
            val sourceY = (
                crop.topPx +
                    (outputY + 0.5f) * crop.sizePx / outputSizePx.toFloat() -
                    0.5f
                ).coerceIn(crop.topPx.toFloat(), cropBottom.toFloat())
            val y0 = floor(sourceY).toInt().coerceIn(crop.topPx, cropBottom)
            val y1 = (y0 + 1).coerceAtMost(cropBottom)
            val yWeight = (sourceY - y0).coerceIn(0f, 1f)

            for (outputX in 0 until outputSizePx) {
                val sourceX = (
                    crop.leftPx +
                        (outputX + 0.5f) * crop.sizePx / outputSizePx.toFloat() -
                        0.5f
                    ).coerceIn(crop.leftPx.toFloat(), cropRight.toFloat())
                val x0 = floor(sourceX).toInt().coerceIn(crop.leftPx, cropRight)
                val x1 = (x0 + 1).coerceAtMost(cropRight)
                val xWeight = (sourceX - x0).coerceIn(0f, 1f)

                val topLeft = sourceArgb[y0 * crop.sourceWidthPx + x0]
                val topRight = sourceArgb[y0 * crop.sourceWidthPx + x1]
                val bottomLeft = sourceArgb[y1 * crop.sourceWidthPx + x0]
                val bottomRight = sourceArgb[y1 * crop.sourceWidthPx + x1]
                val pixelIndex = outputY * outputSizePx + outputX

                val red = interpolateChannel(
                    topLeft ushr 16 and 0xff,
                    topRight ushr 16 and 0xff,
                    bottomLeft ushr 16 and 0xff,
                    bottomRight ushr 16 and 0xff,
                    xWeight,
                    yWeight
                )
                val green = interpolateChannel(
                    topLeft ushr 8 and 0xff,
                    topRight ushr 8 and 0xff,
                    bottomLeft ushr 8 and 0xff,
                    bottomRight ushr 8 and 0xff,
                    xWeight,
                    yWeight
                )
                val blue = interpolateChannel(
                    topLeft and 0xff,
                    topRight and 0xff,
                    bottomLeft and 0xff,
                    bottomRight and 0xff,
                    xWeight,
                    yWeight
                )

                destinationNchw[pixelIndex] = (red - RTMPOSE_MEAN_R) / RTMPOSE_STD_R
                destinationNchw[planeSize + pixelIndex] =
                    (green - RTMPOSE_MEAN_G) / RTMPOSE_STD_G
                destinationNchw[2 * planeSize + pixelIndex] =
                    (blue - RTMPOSE_MEAN_B) / RTMPOSE_STD_B
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
    private const val RTMPOSE_MEAN_R = 123.675f
    private const val RTMPOSE_MEAN_G = 116.28f
    private const val RTMPOSE_MEAN_B = 103.53f
    private const val RTMPOSE_STD_R = 58.395f
    private const val RTMPOSE_STD_G = 57.12f
    private const val RTMPOSE_STD_B = 57.375f
}
