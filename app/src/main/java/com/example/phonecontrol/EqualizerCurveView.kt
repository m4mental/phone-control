package com.example.phonecontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Custom Canvas View displaying:
 * 1. Live 48-Band Real-Time FFT Spectrum Analyzer with neon gradient bars and peak decay.
 * 2. Smooth Bézier spline frequency response curve across 20Hz to 20,000Hz.
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

    // --- Live FFT Spectrum Properties ---
    private val NUM_SPECTRUM_BARS = 48
    private val spectrumLevels = FloatArray(NUM_SPECTRUM_BARS) { 0f }
    private val peakLevels = FloatArray(NUM_SPECTRUM_BARS) { 0f }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val peakBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8000E5FF")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var bands: List<EqualizerBand> = emptyList()
    private val minDb = -15f
    private val maxDb = 15f
    private val minFreq = 20f
    private val maxFreq = 20000f

    fun setBands(newBands: List<EqualizerBand>) {
        this.bands = newBands.sortedBy { it.frequency }
        invalidate()
    }

    fun updateFft(fft: ByteArray?) {
        if (fft == null || fft.isEmpty()) return
        val count = (fft.size / 2).coerceAtMost(NUM_SPECTRUM_BARS * 2)
        for (i in 0 until NUM_SPECTRUM_BARS) {
            val idx = (i * count) / NUM_SPECTRUM_BARS
            val r = fft[idx].toFloat()
            val im = if (idx + 1 < fft.size) fft[idx + 1].toFloat() else 0f
            val mag = Math.hypot(r.toDouble(), im.toDouble()).toFloat() / 110f
            val target = mag.coerceIn(0.02f, 0.95f)

            // Smooth attack and release
            if (target > spectrumLevels[i]) {
                spectrumLevels[i] = target
            } else {
                spectrumLevels[i] = (spectrumLevels[i] * 0.82f).coerceAtLeast(0.02f)
            }

            if (spectrumLevels[i] > peakLevels[i]) {
                peakLevels[i] = spectrumLevels[i]
            } else {
                peakLevels[i] = (peakLevels[i] - 0.015f).coerceAtLeast(0.02f)
            }
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Draw 0dB Center Line & Grid
        val zeroY = dbToY(0f, h)
        canvas.drawLine(0f, zeroY, w, zeroY, zeroLinePaint)

        val plus10Y = dbToY(10f, h)
        val minus10Y = dbToY(-10f, h)
        canvas.drawLine(0f, plus10Y, w, plus10Y, gridPaint)
        canvas.drawLine(0f, minus10Y, w, minus10Y, gridPaint)

        // 2. Draw Live FFT Neon Spectrum Bars
        val barSpacing = 3f
        val totalSpacing = (NUM_SPECTRUM_BARS + 1) * barSpacing
        val barWidth = ((w - totalSpacing) / NUM_SPECTRUM_BARS).coerceAtLeast(2f)

        barPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.parseColor("#AA00E5FF"), // Neon Cyan Top
                Color.parseColor("#777C4DFF"), // Purple Mid
                Color.parseColor("#1500E5FF")  // Translucent Bottom
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )

        for (i in 0 until NUM_SPECTRUM_BARS) {
            val left = barSpacing + i * (barWidth + barSpacing)
            val right = left + barWidth
            val level = spectrumLevels[i]
            val barHeight = level * (h * 0.75f)
            val top = h - barHeight
            val bottom = h

            // Draw rounded bar
            canvas.drawRoundRect(RectF(left, top, right, bottom), 4f, 4f, barPaint)

            // Draw floating peak line
            val peakTop = h - (peakLevels[i] * (h * 0.75f))
            canvas.drawLine(left, peakTop, right, peakTop, peakBarPaint)
        }

        if (bands.isEmpty()) return

        // 3. Map Bands to Screen Coordinates
        val points = mutableListOf<Pair<Float, Float>>()
        val firstGain = bands.first().gain
        points.add(Pair(freqToX(minFreq, w), dbToY(firstGain, h)))

        for (band in bands) {
            val x = freqToX(band.frequency.toFloat(), w)
            val y = dbToY(band.gain, h)
            points.add(Pair(x, y))
        }

        val lastGain = bands.last().gain
        points.add(Pair(freqToX(maxFreq, w), dbToY(lastGain, h)))

        // 4. Build Smooth Bézier Spline Path
        val path = Path()
        path.moveTo(points[0].first, points[0].second)

        for (i in 0 until points.size - 1) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val controlX = (p0.first + p1.first) / 2f
            path.cubicTo(controlX, p0.second, controlX, p1.second, p1.first, p1.second)
        }

        // 5. Fill Gradient Under Curve
        val fillPath = Path(path)
        fillPath.lineTo(w, h)
        fillPath.lineTo(0f, h)
        fillPath.close()

        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.parseColor("#4400E5FF"),
                Color.parseColor("#1A7C4DFF"),
                Color.parseColor("#000D1117")
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        // 6. Draw Stroke Curve
        canvas.drawPath(path, curvePaint)

        // 7. Draw glowing dots on user band points
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
        return ((maxDb - clamped) / (maxDb - minDb) * height)
    }
}
