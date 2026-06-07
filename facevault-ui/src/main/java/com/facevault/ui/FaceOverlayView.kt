package com.facevault.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * A full-screen overlay drawn on top of the camera preview during enrollment.
 *
 * It dims the screen except for an oval "face window", draws the live face
 * bounding box inside that window, recolours the oval to signal alignment state,
 * and plays a brief checkmark animation when a pose is captured.
 *
 * Colours follow the state contract:
 *  * [State.IDLE] — gray oval (no face).
 *  * [State.DETECTED] — yellow oval (face present, not yet aligned/captured).
 *  * [State.CAPTURED] — green oval + animated checkmark.
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Visual states the overlay can render. */
    enum class State { IDLE, DETECTED, CAPTURED }

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val ovalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.GRAY
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 16f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#2ECC71")
    }

    private val ovalRect = RectF()
    private var faceBox: RectF? = null
    private var state: State = State.IDLE

    /** Animated checkmark progress in 0f..1f; <0 means no checkmark. */
    private var checkProgress = -1f
    private var checkAnimator: ValueAnimator? = null

    init {
        // CLEAR xfermode requires a software-rendered offscreen layer.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** Updates the overlay state and triggers the checkmark on capture. */
    fun setState(newState: State) {
        if (newState == state) return
        state = newState
        ovalPaint.color = when (state) {
            State.IDLE -> Color.GRAY
            State.DETECTED -> Color.parseColor("#F1C40F")
            State.CAPTURED -> Color.parseColor("#2ECC71")
        }
        if (state == State.CAPTURED) playCheckmark()
        invalidate()
    }

    /**
     * Sets the detected face box in view coordinates, or null to clear it.
     * Callers are responsible for mapping analyzer coordinates to view space.
     */
    fun setFaceBox(box: RectF?) {
        faceBox = box
        invalidate()
    }

    /** Returns the oval guide rectangle in view coordinates. */
    fun ovalBounds(): RectF = RectF(ovalRect)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val ovalW = w * 0.70f
        val ovalH = min(h * 0.55f, ovalW * 1.3f)
        val cx = w / 2f
        val cy = h * 0.42f
        ovalRect.set(cx - ovalW / 2f, cy - ovalH / 2f, cx + ovalW / 2f, cy + ovalH / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Scrim with an oval cut-out.
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawOval(ovalRect, clearPaint)
        // Oval guide stroke.
        canvas.drawOval(ovalRect, ovalPaint)
        // Live face box.
        faceBox?.let { canvas.drawRect(it, boxPaint) }
        // Checkmark animation.
        if (checkProgress >= 0f) drawCheckmark(canvas)
    }

    private fun drawCheckmark(canvas: Canvas) {
        val cx = ovalRect.centerX()
        val cy = ovalRect.centerY()
        val s = min(ovalRect.width(), ovalRect.height()) * 0.18f

        // Two-segment tick; the first segment draws, then the second.
        val p1x = cx - s
        val p1y = cy
        val p2x = cx - s * 0.2f
        val p2y = cy + s * 0.8f
        val p3x = cx + s
        val p3y = cy - s * 0.8f

        val firstLen = 0.4f
        if (checkProgress <= firstLen) {
            val t = checkProgress / firstLen
            canvas.drawLine(p1x, p1y, p1x + (p2x - p1x) * t, p1y + (p2y - p1y) * t, checkPaint)
        } else {
            canvas.drawLine(p1x, p1y, p2x, p2y, checkPaint)
            val t = ((checkProgress - firstLen) / (1f - firstLen)).coerceIn(0f, 1f)
            canvas.drawLine(p2x, p2y, p2x + (p3x - p2x) * t, p2y + (p3y - p2y) * t, checkPaint)
        }
    }

    private fun playCheckmark() {
        checkAnimator?.cancel()
        checkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            addUpdateListener {
                checkProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Briefly hold the full tick, then clear it.
                    postDelayed({ checkProgress = -1f; invalidate() }, 400)
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        checkAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
