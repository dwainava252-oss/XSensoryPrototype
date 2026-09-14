package com.k2e7.xsensory.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.k2e7.xsensory.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Custom radar-style scanning view shown while discovering nearby Wi-Fi Direct peers.
 * Idle by default — call [startScanning] when discovery begins and [stopScanning] once
 * it ends (peer connected, disconnected, or discovery not yet started). Draws concentric
 * range rings, a rotating sweep (only while scanning), and a tappable blip per peer with
 * the device's initial inside the circle and its full name below it.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Blip(val id: String, val label: String, val angleDeg: Float, val distanceFraction: Float)

    /** Invoked with the tapped blip's id (the peer's Wi-Fi Direct device address). */
    var onBlipTapped: ((String) -> Unit)? = null

    private val neonGreen = ContextCompat.getColor(context, R.color.neon_green)
    private val ringColor = ContextCompat.getColor(context, R.color.neon_green_dim)
    private val bgPage = ContextCompat.getColor(context, R.color.bg_page)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = ringColor
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = neonGreen
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val blipFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = neonGreen
    }
    private val blipRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = neonGreen
    }
    private val initialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgPage
        textSize = 20f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    private var sweepAngle = 0f
    private var pulse = 0f
    private var isScanning = false
    private val blips = mutableListOf<Blip>()
    private val blipRadius = 20f

    private val sweepAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweepAngle = it.animatedValue as Float
            invalidate()
        }
    }

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    /** Begin the sweep + pulse animation. Call when the user taps Discover. */
    fun startScanning() {
        if (isScanning) return
        isScanning = true
        sweepAnimator.start()
        pulseAnimator.start()
        invalidate()
    }

    /** Stop and freeze the radar. Call on connect, disconnect, or reset. */
    fun stopScanning() {
        if (!isScanning) return
        isScanning = false
        sweepAnimator.cancel()
        pulseAnimator.cancel()
        pulse = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        sweepAnimator.cancel()
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    fun setBlips(newBlips: List<Blip>) {
        blips.clear()
        blips.addAll(newBlips)
        invalidate()
    }

    fun clearBlips() {
        blips.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        // Leave headroom below each ring for name labels under the outermost blips.
        val maxRadius = min(cx, cy) - 40f
        if (maxRadius <= 0f) return

        // Range rings
        for (i in 1..3) {
            ringPaint.alpha = 90
            canvas.drawCircle(cx, cy, maxRadius * i / 3f, ringPaint)
        }

        // Rotating sweep — only while actively scanning
        if (isScanning) {
            val shader = SweepGradient(
                cx, cy,
                intArrayOf(Color.TRANSPARENT, neonGreen, Color.TRANSPARENT),
                floatArrayOf(0f, 0.02f, 0.28f)
            )
            val matrix = Matrix()
            matrix.postRotate(sweepAngle, cx, cy)
            shader.setLocalMatrix(matrix)
            sweepPaint.shader = shader
            sweepPaint.alpha = 160
            canvas.drawCircle(cx, cy, maxRadius, sweepPaint)
        }

        // Center dot — pulses while scanning, steady dim dot while idle
        centerPaint.alpha = if (isScanning) (140 + 80 * pulse).toInt() else 100
        canvas.drawCircle(cx, cy, 6f + 3f * pulse, centerPaint)

        // Peer blips: initial inside the circle, full name below it
        blips.forEach { blip ->
            val rad = Math.toRadians(blip.angleDeg.toDouble())
            val r = maxRadius * blip.distanceFraction
            val bx = (cx + r * cos(rad)).toFloat()
            val by = (cy + r * sin(rad)).toFloat()

            val pulseAmount = if (isScanning) pulse else 0f
            blipRingPaint.alpha = (255 * (0.5f + 0.5f * pulseAmount)).toInt()
            canvas.drawCircle(bx, by, blipRadius + 4f * pulseAmount, blipRingPaint)
            canvas.drawCircle(bx, by, blipRadius, blipFillPaint)

            val initial = blip.label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            val textY = by - (initialPaint.descent() + initialPaint.ascent()) / 2f
            canvas.drawText(initial, bx, textY, initialPaint)
            canvas.drawText(blip.label, bx, by + blipRadius + 26f, namePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val cx = width / 2f
            val cy = height / 2f
            val maxRadius = min(cx, cy) - 40f
            for (blip in blips) {
                val rad = Math.toRadians(blip.angleDeg.toDouble())
                val r = maxRadius * blip.distanceFraction
                val bx = cx + r * cos(rad)
                val by = cy + r * sin(rad)
                val dx = event.x - bx
                val dy = event.y - by
                if (sqrt(dx * dx + dy * dy) < blipRadius + 12f) {
                    onBlipTapped?.invoke(blip.id)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}