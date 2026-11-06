package com.noyclic.endvr.client

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import kotlin.math.min

internal class StereoPreviewView(context: Context) : View(context) {
    @Volatile
    private var currentFrame: Bitmap? = null

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xA0FFFFFF.toInt()
        strokeWidth = resources.displayMetrics.density
    }

    init {
        isClickable = true
        setBackgroundColor(Color.BLACK)
    }

    fun setFrame(bitmap: Bitmap) {
        currentFrame = bitmap
        invalidate()
    }

    fun clearFrame() {
        currentFrame = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val bitmap = currentFrame
        if (bitmap == null || bitmap.width < 2 || bitmap.height < 1) {
            canvas.drawText("Waiting for EndVR host…", width / 2f, height / 2f, textPaint)
            return
        }
        val split = bitmap.width / 2
        drawEye(canvas, bitmap, Rect(0, 0, split, bitmap.height),
            RectF(0f, 0f, width / 2f, height.toFloat()))
        drawEye(canvas, bitmap, Rect(split, 0, bitmap.width, bitmap.height),
            RectF(width / 2f, 0f, width.toFloat(), height.toFloat()))
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), dividerPaint)
    }

    private fun drawEye(canvas: Canvas, bitmap: Bitmap, source: Rect, destination: RectF) {
        if (source.width() <= 0 || source.height() <= 0 ||
            destination.width() <= 0f || destination.height() <= 0f) return
        val scale = min(
            destination.width() / source.width().toFloat(),
            destination.height() / source.height().toFloat()
        )
        val drawWidth = source.width() * scale
        val drawHeight = source.height() * scale
        val fitted = RectF(
            destination.centerX() - drawWidth / 2f,
            destination.centerY() - drawHeight / 2f,
            destination.centerX() + drawWidth / 2f,
            destination.centerY() + drawHeight / 2f
        )
        canvas.drawBitmap(bitmap, source, fitted, imagePaint)
    }
}
