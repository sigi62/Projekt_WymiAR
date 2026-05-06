package com.example.pracazaliczeniowa.Overlays

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar

class RulerSeekBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatSeekBar(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
    }

    init {
        // Hide the default seekbar track and thumb so we can draw our own
        splitTrack = false
        thumb = null
        setPadding(60, 0, 60, 0)
    }

    override fun onDraw(canvas: Canvas) {
        // We do NOT call super.onDraw(canvas) because we want to completely
        // replace the visual look with the ruler from image_6a2ad2.png

        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2
        val usableWidth = width - paddingLeft - paddingRight

        // Draw the ruler horizontal base line
        paint.strokeWidth = 4f
        canvas.drawLine(paddingLeft.toFloat(), centerY, width - paddingRight, centerY, paint)

        val totalTicks = max // If max is 360, we have 360 intervals

        // Draw Ticks
        for (i in 0..totalTicks step 5) { // Step by 5 to avoid overcrowding
            val x = paddingLeft + (i.toFloat() / max) * usableWidth
            val isMajor = i % 30 == 0 // Major tick every 30 units

            val tickHeight = if (isMajor) 60f else 30f
            paint.strokeWidth = if (isMajor) 6f else 3f

            canvas.drawLine(x, centerY - tickHeight / 2, x, centerY + tickHeight / 2, paint)

            // Draw Labels (e.g., -30, 0, 30 logic based on your progress)
            if (isMajor) {
                paint.textSize = 35f
                // Offset labels to match your -30 to 30 or 0 to 360 scale
                val labelText = (i - (max / 2)).toString()
                canvas.drawText(labelText, x, centerY - 60f, paint)
            }
        }

        // Draw the Indicator (The "Thumb") based on current progress
        val thumbX = paddingLeft + (progress.toFloat() / max) * usableWidth
        paint.color = Color.RED
        canvas.drawCircle(thumbX, centerY, 15f, paint)

        // Draw current value text above indicator as seen in image_6a2ad2.png
        val displayVal = progress - (max / 2)
        canvas.drawText(displayVal.toString(), thumbX, centerY - 110f, paint)
        paint.color = Color.BLACK
    }
}