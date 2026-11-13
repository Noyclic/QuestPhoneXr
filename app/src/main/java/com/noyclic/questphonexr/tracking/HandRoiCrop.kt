package com.noyclic.questphonexr.tracking

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Square camera-frame crop for a top-down hand landmark model.
 *
 * Coordinates are relative to the upright, already-unmirrored camera frame. This class
 * describes crop geometry only; pixel resampling and model normalization belong to the
 * provider-specific preprocessing adapter.
 */
data class HandRoiCrop(
    val leftPx: Int,
    val topPx: Int,
    val sizePx: Int,
    val sourceWidthPx: Int,
    val sourceHeightPx: Int
) {
    init {
        require(sourceWidthPx > 0 && sourceHeightPx > 0)
        require(sizePx > 0 && sizePx <= sourceWidthPx && sizePx <= sourceHeightPx)
        require(leftPx in 0..(sourceWidthPx - sizePx))
        require(topPx in 0..(sourceHeightPx - sizePx))
    }

    /** Maps normalized model-output coordinates back into the original camera frame. */
    fun toSourceNormalized(modelX: Float, modelY: Float): Pair<Float, Float>? {
        if (!modelX.isFinite() || !modelY.isFinite()) return null
        val sourceX = (leftPx + modelX * sizePx) / sourceWidthPx.toFloat()
        val sourceY = (topPx + modelY * sizePx) / sourceHeightPx.toFloat()
        return sourceX to sourceY
    }

    /** Maps normalized camera-frame coordinates into the square model crop. */
    fun toCropNormalized(sourceX: Float, sourceY: Float): Pair<Float, Float>? {
        if (!sourceX.isFinite() || !sourceY.isFinite()) return null
        val cropX = (sourceX * sourceWidthPx - leftPx) / sizePx.toFloat()
        val cropY = (sourceY * sourceHeightPx - topPx) / sizePx.toFloat()
        return cropX to cropY
    }

    companion object {
        const val REQUIRED_HAND_LANDMARKS = 21
        const val DEFAULT_PADDING_FACTOR = 1.6f
        const val DEFAULT_MINIMUM_CROP_PX = 64

        /**
         * Builds a bounded square crop from a complete ordered hand observation.
         *
         * Coordinates are normalized in the source camera frame. Slightly out-of-frame
         * landmarks are clamped before ROI calculation. Invalid or degenerate observations
         * return null instead of manufacturing a crop from unusable coordinates.
         */
        fun fromLandmarks(
            points: List<HandPoint>,
            sourceWidthPx: Int,
            sourceHeightPx: Int,
            paddingFactor: Float = DEFAULT_PADDING_FACTOR,
            minimumCropPx: Int = DEFAULT_MINIMUM_CROP_PX
        ): HandRoiCrop? {
            if (sourceWidthPx <= 0 || sourceHeightPx <= 0) return null
            if (points.size < REQUIRED_HAND_LANDMARKS) return null
            if (!paddingFactor.isFinite() || paddingFactor !in 1f..3f) return null
            if (minimumCropPx <= 0) return null

            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY

            for (point in points.take(REQUIRED_HAND_LANDMARKS)) {
                if (!point.x.isFinite() || !point.y.isFinite()) return null
                val x = point.x.coerceIn(0f, 1f)
                val y = point.y.coerceIn(0f, 1f)
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }

            if (maxX <= minX || maxY <= minY) return null

            val boundsWidthPx = (maxX - minX) * sourceWidthPx
            val boundsHeightPx = (maxY - minY) * sourceHeightPx
            val requestedSizePx = ceil(
                max(boundsWidthPx, boundsHeightPx) * paddingFactor
            ).toInt()
            val maximumSizePx = minOf(sourceWidthPx, sourceHeightPx)
            val cropSizePx = requestedSizePx
                .coerceAtLeast(minimumCropPx)
                .coerceAtMost(maximumSizePx)
            if (cropSizePx <= 0) return null

            val centerX = (minX + maxX) * 0.5f * sourceWidthPx
            val centerY = (minY + maxY) * 0.5f * sourceHeightPx
            val leftPx = (centerX - cropSizePx * 0.5f)
                .roundToInt()
                .coerceIn(0, sourceWidthPx - cropSizePx)
            val topPx = (centerY - cropSizePx * 0.5f)
                .roundToInt()
                .coerceIn(0, sourceHeightPx - cropSizePx)

            return HandRoiCrop(
                leftPx = leftPx,
                topPx = topPx,
                sizePx = cropSizePx,
                sourceWidthPx = sourceWidthPx,
                sourceHeightPx = sourceHeightPx
            )
        }
    }
}
