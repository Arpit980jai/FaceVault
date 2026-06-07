package com.facevault.sample

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.facevault.core.matching.MatchResult

/**
 * Draws labelled bounding boxes over a searched image.
 *
 * Set the image's displayed size with [setImageBounds] (the rectangle the bitmap
 * actually occupies inside the ImageView, accounting for letterboxing), the
 * bitmap's intrinsic size with [setSourceSize], and the results with
 * [setResults]. Boxes (given in source-image coordinates) are mapped into view
 * coordinates and tinted green for matches / red for non-matches.
 */
class ResultOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        isFakeBoldText = true
    }

    private var results: List<MatchResult> = emptyList()
    private var sourceW = 1f
    private var sourceH = 1f
    private val imageBounds = RectF()

    /** Sets the matches to render, in source-image coordinates. */
    fun setResults(results: List<MatchResult>) {
        this.results = results
        invalidate()
    }

    /** Sets the intrinsic (bitmap) dimensions used to scale the boxes. */
    fun setSourceSize(width: Int, height: Int) {
        sourceW = width.coerceAtLeast(1).toFloat()
        sourceH = height.coerceAtLeast(1).toFloat()
        invalidate()
    }

    /** Sets the on-screen rectangle the bitmap occupies (after letterboxing). */
    fun setImageBounds(bounds: RectF) {
        imageBounds.set(bounds)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (results.isEmpty()) return

        val drawRect = if (imageBounds.isEmpty) {
            RectF(0f, 0f, width.toFloat(), height.toFloat())
        } else {
            imageBounds
        }
        val scaleX = drawRect.width() / sourceW
        val scaleY = drawRect.height() / sourceH

        for (result in results) {
            val src = result.faceBounds
            val left = drawRect.left + src.left * scaleX
            val top = drawRect.top + src.top * scaleY
            val right = drawRect.left + src.right * scaleX
            val bottom = drawRect.top + src.bottom * scaleY

            val color = if (result.matched) Color.parseColor("#2ECC71") else Color.parseColor("#E74C3C")
            boxPaint.color = color
            labelBg.color = color
            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = when {
                result.matched -> "${result.person?.name ?: "?"} ${(result.confidence * 100).toInt()}%"
                else -> "Unknown"
            }
            val textWidth = labelText.measureText(label)
            val labelTop = (top - 44f).coerceAtLeast(0f)
            canvas.drawRect(left, labelTop, left + textWidth + 20f, labelTop + 44f, labelBg)
            canvas.drawText(label, left + 10f, labelTop + 34f, labelText)
        }
    }
}
