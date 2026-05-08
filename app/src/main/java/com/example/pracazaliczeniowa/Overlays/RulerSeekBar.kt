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

    // --- New Dynamic Properties ---
    var minValue: Float = -180f
    var maxValue: Float = 180f
    var centerValue: Float = 0f

    var majorTickInterval: Float = 30f

    // How often to draw a small tick (e.g., every 10 units)
    var minorTickInterval: Float = 10f
    // Default interval set to 300 (Rotation). Can be changed to 20 for Position.

    private val currentRealValue: Float
        get() = minValue + (progress.toFloat() / max.toFloat()) * (maxValue - minValue)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
    }

    init {
        splitTrack = false
        thumb = null

        background = null           // Removes the ripple/highlight glow
        progressDrawable = null     // Removes the default blue/grey progress line
        // Ensure enough padding for labels and the indicator
        setPadding(60, 120, 60, 60)
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2
        val usableWidth = width - paddingLeft - paddingRight
        val totalRange = maxValue - minValue


        val rulerColor = androidx.core.content.ContextCompat.getColor(context, R.color.icon_tint)
        paint.color = rulerColor
        paint.isAntiAlias = true

        // 1. Draw Ticks
        var currentTick = minValue
        var currentVal = minValue
        while (currentVal <= maxValue + 0.001f) { // Small epsilon for float precision
            val relativePos = (currentVal - minValue) / totalRange
            val x = paddingLeft + (relativePos * usableWidth)

            // Determine if this is a Landmark (Start, End, or Center)
            val isBoundary = currentVal <= minValue + 0.001f || currentVal >= maxValue - 0.001f
            val isCenter = Math.abs(currentVal - centerValue) < 0.001f

            val isMajor = Math.abs(currentVal % majorTickInterval) < 0.001f ||
                    Math.abs(currentVal % majorTickInterval - majorTickInterval) < 0.001f

            val isMinor = Math.abs(currentVal % minorTickInterval) < 0.001f ||
                    Math.abs(currentVal % minorTickInterval - minorTickInterval) < 0.001f
            when {
                isBoundary || isCenter -> {
                    // Level 1: Landmarks - Boldest & Labeled
                    paint.strokeWidth = 6f
                    val tickHeight = 50f
                    canvas.drawLine(x, centerY - tickHeight / 2, x, centerY + tickHeight / 2, paint)

                    paint.textSize = 32f
                    paint.isFakeBoldText = true
                    val label = if (maxValue <= 10f) "%.1f".format(currentVal) else currentVal.toInt().toString()
                    canvas.drawText(label, x, centerY - (tickHeight / 2) - 20f, paint)
                }
                isMajor -> {
                    // Level 2: Major Ticks - Distinct but NO labels
                    paint.strokeWidth = 4f
                    val tickHeight = 30f
                    canvas.drawLine(x, centerY - tickHeight / 2, x, centerY + tickHeight / 2, paint)
                }
                isMinor -> {
                    // Level 3: Minor Ticks - Subtle background detail
                    paint.strokeWidth = 2f
                    val tickHeight = 15f
                    canvas.drawLine(x, centerY - tickHeight / 2, x, centerY + tickHeight / 2, paint)
                }
            }
            currentVal += minorTickInterval
        }

        val progressPercent = progress.toFloat() / max.toFloat()
        val thumbX = paddingLeft + (progressPercent * usableWidth)

        // Black "Outline" for the indicator
        paint.color = Color.BLACK
        paint.strokeWidth = 7f
        canvas.drawLine(thumbX, centerY - 40f, thumbX, centerY + 40f, paint)

        // White "Core" for the indicator
        paint.color = Color.WHITE
        paint.strokeWidth = 3f
        canvas.drawLine(thumbX, centerY - 38f, thumbX, centerY + 38f, paint)

        // 3. Current Value Text with Decimal
        paint.color = rulerColor
        paint.textSize = 40f
        paint.isFakeBoldText = true
        val realValue = minValue + (progressPercent * totalRange)
        val displayVal = "%.1f".format(realValue)
        canvas.drawText(displayVal, thumbX, centerY - 70f, paint)

        paint.isFakeBoldText = false
    }
}