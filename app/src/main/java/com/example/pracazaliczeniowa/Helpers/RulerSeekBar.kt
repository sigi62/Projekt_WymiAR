package com.example.pracazaliczeniowa.Helpers

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.content.ContextCompat
import com.example.pracazaliczeniowa.R

class RulerSeekBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatSeekBar(context, attrs) {

    // --- Dynamic Properties — each setter triggers a redraw ---

    var minValue: Float = -180f
    var maxValue: Float = 180f
    var centerValue: Float = 0f
    var majorTickInterval: Float = 30f
    var minorTickInterval: Float = 10f

    fun updateRange(
        min: Float,
        max: Float,
        center: Float,
        major: Float,
        minor: Float
    ) {
        minValue = min
        maxValue = max
        centerValue = center
        majorTickInterval = major
        minorTickInterval = minor
        invalidate()
    }

    /**
     * Call after updateRange() for POSITION mode only.
     * Sets seekBar.max = (maxValue - minValue) so 1 progress step = 1 display unit.
     * Do NOT call for ROTATE or SCALE — they manage their own max.
     */
    fun setStepsFromRange() {
        val steps = (maxValue - minValue).toInt().coerceAtLeast(1)
        this.max = steps
        this.progress = this.progress.coerceIn(0, steps)
    }

    var decimalPlaces: Int = 1
        set(value) { field = value; invalidate() }

    // true  → draws vertically (track top→bottom, ticks protrude right, label on right)
    // false → draws horizontally (original behaviour)
    var vertical: Boolean = false
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
        setPadding(60, 0, 60, 0)
    }

    override fun onDraw(canvas: Canvas) {
        if (vertical) drawVertical(canvas) else drawHorizontal(canvas)
    }

    // ── Horizontal (original) ────────────────────────────────────────────

    private fun drawHorizontal(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height * 60f / 140f

        val usableWidth = width - paddingLeft - paddingRight
        val totalRange = maxValue - minValue
        if (totalRange == 0f) return

        val rulerColor = ContextCompat.getColor(context, R.color.icon_tint)
        paint.color = rulerColor
        paint.isAntiAlias = true
        paint.textAlign = Paint.Align.CENTER

        // 1. Ticks
        if (minorTickInterval <= 0f) return
        var currentVal = minValue
        while (currentVal <= maxValue + 0.001f) {
            val relPos = (currentVal - minValue) / totalRange
            val x = paddingLeft + relPos * usableWidth

            val isBoundary = currentVal <= minValue + 0.001f || currentVal >= maxValue - 0.001f
            val isCenter   = Math.abs(currentVal - centerValue) < 0.001f
            val isMajor    = currentVal.isNearMultipleOf(majorTickInterval)
            val isMinor    = currentVal.isNearMultipleOf(minorTickInterval)

            when {
                isBoundary -> {
                    paint.strokeWidth = 6f
                    val tickH = 50f
                    canvas.drawLine(x, centerY - tickH / 2, x, centerY + tickH / 2, paint)
                    paint.textSize = 32f
                    paint.isFakeBoldText = true
                    val label = if (maxValue <= 10f) "%.1f".format(currentVal) else currentVal.toInt().toString()
                    canvas.drawText(label, x, centerY - tickH / 2 - 20f, paint)
                    paint.isFakeBoldText = false
                }
                isCenter -> {
                    paint.strokeWidth = 6f
                    val tickH = 50f
                    canvas.drawLine(x, centerY - tickH / 2, x, centerY + tickH / 2, paint)
                }
                isMajor -> {
                    paint.strokeWidth = 4f
                    val tickH = 30f
                    canvas.drawLine(x, centerY - tickH / 2, x, centerY + tickH / 2, paint)
                }
                isMinor -> {
                    paint.strokeWidth = 2f
                    val tickH = 15f
                    canvas.drawLine(x, centerY - tickH / 2, x, centerY + tickH / 2, paint)
                }
            }
            currentVal += minorTickInterval
        }

        // 2. Thumb
        val progressPercent = if (max > 0) progress.toFloat() / max else 0f
        val thumbX = paddingLeft + progressPercent * usableWidth
        paint.color = Color.BLACK; paint.strokeWidth = 7f
        canvas.drawLine(thumbX, centerY - 40f, thumbX, centerY + 40f, paint)
        paint.color = Color.WHITE; paint.strokeWidth = 3f
        canvas.drawLine(thumbX, centerY - 38f, thumbX, centerY + 38f, paint)

        // 3. Value label
        paint.color = rulerColor; paint.textSize = 40f; paint.isFakeBoldText = true
        val realValue = minValue + progressPercent * totalRange
        canvas.drawText("%.${decimalPlaces}f".format(realValue), thumbX, centerY + 70f, paint)
        paint.isFakeBoldText = false
    }

    // ── Vertical (native, no rotation needed) ───────────────────────────
    //
    // Geometry constants (all in pixels, scaled at draw time):
    //
    //   |<-- TRACK_X -->|<-- ticks grow left from here
    //   |               |--- boundary/center tick: TICK_MAJOR_LEN
    //   |               |--  major tick:           TICK_MAJOR_LEN * 0.66
    //   |               |-   minor tick:           TICK_MINOR_LEN
    //   |
    //   MARGIN_TOP  ← top of usable track
    //   ...
    //   MARGIN_BOTTOM ← bottom of usable track
    //
    //   Value label sits to the RIGHT of the track at LABEL_X.
    //   Tick labels (boundary values) also sit at LABEL_X.

    private fun drawVertical(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val totalRange = maxValue - minValue
        if (totalRange == 0f) return

        val rulerColor = ContextCompat.getColor(context, R.color.icon_tint)

        // ── Geometry ─────────────────────────────────────────────────────
        val marginTop    = 16f                  // px above top tick
        val marginBottom = 16f                  // px below bottom tick
        val trackX       = w * 0.40f            // vertical track line X
        val tickMajorLen = 20f                  // boundary / center ticks
        val tickMidLen   = 13f                  // major interval ticks
        val tickMinorLen = 7f                   // minor interval ticks
        val thumbHalfW   = tickMajorLen + 4f    // thumb extends slightly past longest tick
        val labelX       = trackX - tickMajorLen - 10f
        val valueLabelX  = trackX + 15f
        val textSizeTick = 24f                  // tick label text size (px)
        val textSizeVal  = 34f                  // value label text size (px)


        val usableH = h - marginTop - marginBottom

        // ── Helper: value → Y (max at top, min at bottom) ────────────────
        fun valueToY(v: Float): Float {
            val rel = (v - minValue) / totalRange
            return marginTop + (1f - rel) * usableH
        }

        // ── 1. Ticks ──────────────────────────────────────────────────────
        paint.isAntiAlias = true
        paint.textAlign   = Paint.Align.RIGHT

        if (minorTickInterval <= 0f) return
        var currentVal = minValue
        while (currentVal <= maxValue + 0.001f) {
            val y = valueToY(currentVal)

            val isBoundary = currentVal <= minValue + 0.001f || currentVal >= maxValue - 0.001f
            val isCenter   = Math.abs(currentVal - centerValue) < 0.001f
            val isMajor    = currentVal.isNearMultipleOf(majorTickInterval)
            val isMinor    = currentVal.isNearMultipleOf(minorTickInterval)

            when {
                isBoundary -> {
                    paint.color = rulerColor; paint.strokeWidth = 5f
                    canvas.drawLine(trackX - tickMajorLen, y, trackX, y, paint)
                    // Boundary label to the right
                    paint.textSize = textSizeTick; paint.isFakeBoldText = true
                    val label = if (maxValue <= 10f) "%.1f".format(currentVal)
                    else currentVal.toInt().toString()
                    canvas.drawText(label, labelX, y + textSizeTick * 0.35f, paint)
                    paint.isFakeBoldText = false
                }
                isCenter -> {
                    paint.color = rulerColor; paint.strokeWidth = 5f
                    canvas.drawLine(trackX - tickMajorLen, y, trackX, y, paint)
                }
                isMajor -> {
                    paint.color = rulerColor; paint.strokeWidth = 3f
                    canvas.drawLine(trackX - tickMidLen, y, trackX, y, paint)
                }
                isMinor -> {
                    paint.color = rulerColor; paint.strokeWidth = 1.5f
                    canvas.drawLine(trackX - tickMinorLen, y, trackX, y, paint)
                }
            }
            currentVal += minorTickInterval
        }

        // ── 2. Thumb ──────────────────────────────────────────────────────
        val progressPercent = if (max > 0) progress.toFloat() / max else 0f
        val thumbY = valueToY(minValue + progressPercent * totalRange)

        paint.strokeWidth = 6f; paint.color = rulerColor
        canvas.drawLine(trackX - thumbHalfW, thumbY, trackX + 4f, thumbY, paint)
        paint.strokeWidth = 2f; paint.color = Color.WHITE
        canvas.drawLine(trackX - thumbHalfW + 2f, thumbY, trackX + 2f, thumbY, paint)

        // ── 3. Value label — right of thumb, vertically centred on it ────
        val realValue = minValue + progressPercent * totalRange
        paint.color = rulerColor
        paint.textSize = textSizeVal
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("%.${decimalPlaces}f".format(realValue),
            valueLabelX, thumbY + textSizeVal * 0.35f, paint)
        paint.isFakeBoldText = false
    }

    fun Float.isNearMultipleOf(interval: Float): Boolean {
        if (interval <= 0f) return false
        val remainder = Math.abs(this) % interval
        return remainder < 0.001f || (interval - remainder) < 0.001f
    }

    // ── Vertical touch handling ──────────────────────────────────────────
    // When vertical=true the parent SeekBar tracks finger X, which is useless.
    // We intercept all touch events and map finger Y → progress instead.
    // marginTop/Bottom must match drawVertical exactly so the thumb follows the finger.

    private var verticalListener: OnSeekBarChangeListener? = null

    // Override setOnSeekBarChangeListener so we hold our own ref for vertical mode
    override fun setOnSeekBarChangeListener(l: OnSeekBarChangeListener?) {
        verticalListener = l
        if (!vertical) super.setOnSeekBarChangeListener(l)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!vertical) return super.onTouchEvent(event)

        val marginTop    = 16f
        val marginBottom = 16f
        val usableH      = height.toFloat() - marginTop - marginBottom

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                verticalListener?.onStartTrackingTouch(this)
                updateProgressFromY(event.y, usableH, marginTop)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateProgressFromY(event.y, usableH, marginTop)
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                verticalListener?.onStopTrackingTouch(this)
                invalidate()
                return true
            }
        }
        return false
    }

    private fun updateProgressFromY(y: Float, usableH: Float, marginTop: Float) {
        val clampedY  = y.coerceIn(marginTop, marginTop + usableH)
        val fraction  = 1f - (clampedY - marginTop) / usableH   // invert: top = max
        val newProgress = (fraction * max).toInt().coerceIn(0, max)
        if (newProgress != progress) {
            progress = newProgress
            verticalListener?.onProgressChanged(this, newProgress, true)
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val minH = if (vertical) 0 else (160 + paddingTop + paddingBottom)
        val resolvedHeight = maxOf(measuredHeight, minH)
        setMeasuredDimension(measuredWidth, resolvedHeight)
    }
}