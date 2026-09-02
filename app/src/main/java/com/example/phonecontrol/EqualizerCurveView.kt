package com.example.phonecontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Custom Canvas View displaying a smooth Bézier spline frequency curve
 * across 20Hz to 20,000Hz on logarithmic frequency and linear dB scale (-15dB to +15dB).
 */
class EqualizerCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
    }

    private val pointGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8000E5FF")
        style = Paint.Style.FILL
    }

    private var bands: List<EqualizerBand> = emptyList()
    private val minDb = -15f
    private val maxDb = 15f
    private val minFreq = 20f
    private val maxFreq = 20000f

    fun setBands(newBands: List<EqualizerBand>) {
        // Sort by frequency
        this.bands = newBands.sortedBy { it.frequency }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Draw 0dB Center Line
        val zeroY = dbToY(0f, h)
        canvas.drawLine(0f, zeroY, w, zeroY, zeroLinePaint)

        // Draw +10dB and -10dB Subtle Grid Lines
        val plus10Y = dbToY(10f, h)
        val minus10Y = dbToY(-10f, h)
        canvas.drawLine(0f, plus10Y, w, plus10Y, gridPaint)
        canvas.drawLine(0f, minus10Y, w, minus10Y, gridPaint)

        if (bands.isEmpty()) return

        // 2. Map Bands to Screen Coordinates
        val points = mutableListOf<Pair<Float, Float>>()
        // Edge anchor at 20Hz
        val firstGain = bands.first().gain
        points.add(Pair(freqToX(minFreq, w), dbToY(firstGain, h)))

        for (band in bands) {
            val x = freqToX(band.frequency.toFloat(), w)
            val y = dbToY(band.gain, h)
            points.add(Pair(x, y))
        }

        // Edge anchor at 20kHz
        val lastGain = bands.last().gain
        points.add(Pair(freqToX(maxFreq, w), dbToY(lastGain, h)))

        // 3. Build Smooth Bézier Spline Path
        val path = Path()
        path.moveTo(points[0].first, points[0].second)

        for (i in 0 until points.size - 1) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val controlX = (p0.first + p1.first) / 2f
            path.cubicTo(controlX, p0.second, controlX, p1.second, p1.first, p1.second)
        }

        // 4. Fill Gradient Under Curve
        val fillPath = Path(path)
        fillPath.lineTo(w, h)
        fillPath.lineTo(0f, h)
        fillPath.close()

        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.parseColor("#5500E5FF"),
                Color.parseColor("#227C4DFF"),
                Color.parseColor("#000D1117")
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        // 5. Draw Stroke Curve
        canvas.drawPath(path, curvePaint)

        // 6. Draw glowing dots on user band points
        for (band in bands) {
            val px = freqToX(band.frequency.toFloat(), w)
            val py = dbToY(band.gain, h)
            canvas.drawCircle(px, py, 12f, pointGlowPaint)
            canvas.drawCircle(px, py, 6f, pointPaint)
        }
    }

    private fun freqToX(freq: Float, width: Float): Float {
        val clamped = freq.coerceIn(minFreq, maxFreq)
        val logMin = Math.log10(minFreq.toDouble())
        val logMax = Math.log10(maxFreq.toDouble())
        val logFreq = Math.log10(clamped.toDouble())
        return ((logFreq - logMin) / (logMax - logMin) * width).toFloat()
    }

    private fun dbToY(db: Float, height: Float): Float {
        val clamped = db.coerceIn(minDb, maxDb)
        // Invert Y because screen Y=0 is top (+15dB), Y=height is bottom (-15dB)
        return ((maxDb - clamped) / (maxDb - minDb) * height)
    }
}
