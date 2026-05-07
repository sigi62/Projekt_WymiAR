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

    // Default interval set to 300 (Rotation). Can be changed to 20 for Position.
    var tickInterval: Int = 30
        set(value) {
            field = value
            invalidate() // Redraw when interval changes
        }

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

        val totalTicks = max.toFloat()
        val midPoint = totalTicks / 2f

        val rulerColor = androidx.core.content.ContextCompat.getColor(context, R.color.icon_tint)
        paint.color = rulerColor
        paint.isAntiAlias = true

        // 1. Draw Ticks
        for (i in 0..max step 100) {
            val x = paddingLeft + (i.toFloat() / totalTicks) * usableWidth
            val diffFromMid = i.toFloat() - midPoint

            val isZero = i.toFloat() == midPoint
            val isBoundary = isZero || i == 0 || i == max
            val isMajor30 = diffFromMid % 300f == 0f
            val isTenth10 = diffFromMid % 100f == 0f

            var tickHeight = 0f
            var currentStrokeWidth = 0f
            when {
                isBoundary -> {
                    tickHeight = 35f
                    currentStrokeWidth = 5f
                }
                isMajor30 -> {
                    tickHeight = 20f
                    currentStrokeWidth = 3f
                }
                isTenth10 -> {
                    tickHeight = 10f
                    currentStrokeWidth = 2f
                }
            }

            paint.strokeWidth = currentStrokeWidth
            canvas.drawLine(x, centerY - tickHeight / 2, x, centerY + tickHeight / 2, paint)

            if (isBoundary) {
                paint.textSize = 35f
                paint.isFakeBoldText = true
                val labelValue = (diffFromMid / 10f).toInt()
                canvas.drawText(labelValue.toString(), x, centerY - (tickHeight / 2) - 15f, paint)
                paint.isFakeBoldText = false
            }
        }

        // 2. Draw the Indicator (The vertical line indicating current progress)
        val thumbX = paddingLeft + (progress.toFloat() / totalTicks) * usableWidth
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
        val displayVal = (progress.toFloat() - midPoint) / 10f
        val decimalText = "%.1f".format(displayVal)

        paint.textSize = 42f
        paint.isFakeBoldText = true
        canvas.drawText(decimalText, thumbX, centerY - 70f, paint)

        paint.isFakeBoldText = false
    }
}