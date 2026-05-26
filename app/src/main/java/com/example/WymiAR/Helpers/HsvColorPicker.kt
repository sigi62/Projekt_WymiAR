package com.example.WymiAR.Helpers

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt

class HsvColorPicker @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val hsv = floatArrayOf(0f, 1f, 1f)
    var onColorChanged: ((Int) -> Unit)? = null

    private val satValPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.WHITE
    }

    private var satValRect = RectF()
    private var hueRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val padding = 20f
        val hueHeight = 40f
        val gap = 30f
        satValRect.set(padding, padding, w.toFloat() - padding, h - hueHeight - gap - padding)
        hueRect.set(padding, h - hueHeight - padding, w.toFloat() - padding, h - padding)
    }

    override fun onDraw(canvas: Canvas) {
        val valShader = LinearGradient(0f, satValRect.top, 0f, satValRect.bottom,
            Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
        val satShader = LinearGradient(satValRect.left, 0f, satValRect.right, 0f,
            Color.WHITE, Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)), Shader.TileMode.CLAMP)

        satValPaint.shader = ComposeShader(valShader, satShader, PorterDuff.Mode.MULTIPLY)
        canvas.drawRoundRect(satValRect, 12f, 12f, satValPaint)

        val hueColors = IntArray(361) { i -> Color.HSVToColor(floatArrayOf(i.toFloat(), 1f, 1f)) }
        huePaint.shader = LinearGradient(hueRect.left, hueRect.centerY(), hueRect.right, hueRect.centerY(),
            hueColors, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(hueRect, 10f, 10f, huePaint)

        val selX = satValRect.left + hsv[1] * satValRect.width()
        val selY = satValRect.top + (1f - hsv[2]) * satValRect.height()
        canvas.drawCircle(selX, selY, 20f, selectorPaint)

        val hueX = hueRect.left + (hsv[0] / 360f) * hueRect.width()
        canvas.drawCircle(hueX, hueRect.centerY(), 20f, selectorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (event.y < hueRect.top - 10f) {
                    hsv[1] = ((event.x - satValRect.left) / satValRect.width()).coerceIn(0f, 1f)
                    hsv[2] = (1f - (event.y - satValRect.top) / satValRect.height()).coerceIn(0f, 1f)
                } else {
                    hsv[0] = ((event.x - hueRect.left) / hueRect.width() * 360f).coerceIn(0f, 360f)
                }
                onColorChanged?.invoke(Color.HSVToColor(hsv))
                invalidate()
            }
        }
        return true
    }

    fun setColor(@ColorInt color: Int) {
        Color.colorToHSV(color, hsv)
        invalidate()
    }
}