package com.example.pracazaliczeniowa.Overlays

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar
import com.example.pracazaliczeniowa.R

class RulerSeekBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatSeekBar(context, attrs) {

    // --- Dynamic Properties — each setter triggers a redraw ---

    var minValue: Float = -180f
        set(value) { field = value; invalidate() }

    var maxValue: Float = 180f
        set(value) { field = value; invalidate() }

    var centerValue: Float = 0f
        set(value) { field = value; invalidate() }

    var majorTickInterval: Float = 30f
        set(value) { field = value; invalidate() }

    var minorTickInterval: Float = 10f
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
    }

    init {
        splitTrack = false
        thumb = null
        background = null
        progressDrawable = null
        setPadding(60, 120, 60, 60)
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2
        val usableWidth = width - paddingLeft - paddingRight
        val totalRange = maxValue - minValue
        if (totalRange == 0f) return

        val rulerColor = androidx.core.content.ContextCompat.getColor(context, R.color.icon_tint)
        paint.color = rulerColor
        paint.isAntiAlias = true

        // 1. Draw Ticks
        var currentVal = minValue
        while (currentVal <= maxValue + 0.001f) {
            val relativePos = (currentVal - minValue) / totalRange
            val x = paddingLeft + (relativePos * usableWidth)

            val isBoundary = currentVal <= minValue + 0.001f || currentVal >= maxValue - 0.001f
            val isCenter   = Math.abs(currentVal - centerValue) < 0.001f
            val isMajor    = Math.abs(currentVal % majorTickInterval) < 0.001f ||
                    Math.abs(currentVal % majorTickInterval - majorTickInterval) < 0.001f
            val isMinor    = Math.abs(currentVal % minorTickInterval) < 0.001f ||
                    Math.abs(currentVal % minorTickInterval - minorTickInterval) < 0.001f

            when {
                isBoundary || isCenter -> {
                    paint.strokeWidth = 6f
                    val tickHeight = 50f
                    canvas.drawLine(x, centerY - tickHeight / 2, x, centerY + tickHeight / 2, paint)
                    paint.textSize = 32f
                    paint.isFakeBoldText = true
                    val label = if (maxValue <= 10f) "%.1f".format(currentVal) else currentVal.toInt().toString()
                    canvas.drawText(label, x, centerY - (tickHeight / 2) - 20f, paint)
                }
                isMajor -> {
                    paint.strokeWidth = 4f
                    val tickHeight = 30f
                    canvas.drawLine(x, centerY - tickHeight / 2, x, centerY + tickHeight / 2, paint)
                }
                isMinor -> {
                    paint.strokeWidth = 2f
                    val tickHeight = 15f
                    canvas.drawLine(x, centerY - tickHeight / 2, x, centerY + tickHeight / 2, paint)
                }
            }
            currentVal += minorTickInterval
        }

        // 2. Thumb indicator — derived from minValue/maxValue so it stays correct
        //    even when max or the visual range changes between frames.
        val progressPercent = if (max > 0) progress.toFloat() / max.toFloat() else 0f
        val thumbX = paddingLeft + (progressPercent * usableWidth)

        paint.color = Color.BLACK
        paint.strokeWidth = 7f
        canvas.drawLine(thumbX, centerY - 40f, thumbX, centerY + 40f, paint)

        paint.color = Color.WHITE
        paint.strokeWidth = 3f
        canvas.drawLine(thumbX, centerY - 38f, thumbX, centerY + 38f, paint)

        // 3. Current value label
        paint.color = rulerColor
        paint.textSize = 40f
        paint.isFakeBoldText = true
        val realValue = minValue + (progressPercent * totalRange)
        canvas.drawText("%.1f".format(realValue), thumbX, centerY - 70f, paint)
        paint.isFakeBoldText = false
    }
}