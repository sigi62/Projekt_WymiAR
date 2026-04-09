package com.example.pracazaliczeniowa.Overlays

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Full-screen overlay that draws:
 *  - a semi-transparent dark scrim over the whole screen
 *  - a transparent "punch-out" square showing exactly what will be captured
 *  - a white border around that square
 *  - drag handles (corner dots) so the user can reposition the frame
 *
 * The square is draggable anywhere on screen.
 * [getCropRect] returns the current square position in view coordinates,
 * which ModelPreviewActivity uses to crop the PixelCopy bitmap.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // The crop square in view pixels — centred by default, sized to 75% of
    // the shorter screen dimension so it's large but leaves room to drag.
    private val cropRect = RectF()

    // How far the touch landed from the crop rect centre when drag started
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var isDragging  = false

    // ── Paints ────────────────────────────────────────────────────────────
    private val scrimPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)   // dark semi-transparent scrim
    }

    private val erasePaint = Paint().apply {
        // "Punch out" the square from the scrim so the 3D scene shows through
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val borderPaint = Paint().apply {
        color       = Color.WHITE
        style       = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        color       = Color.WHITE
        style       = Paint.Style.FILL
        isAntiAlias = true
    }

    private val cornerRadius = 6f * resources.displayMetrics.density

    init {
        // Required so PorterDuff CLEAR works correctly on a hardware layer
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        if (w == 0 || h == 0) return

        // Default: centred square = 75% of the shorter side
        val side    = minOf(w, h) * 0.75f
        val centerX = w / 2f
        val centerY = h / 2f
        cropRect.set(
            centerX - side / 2f,
            centerY - side / 2f,
            centerX + side / 2f,
            centerY + side / 2f
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw scrim over the full view
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        // 2. Punch out the crop square so the SceneView is visible beneath
        canvas.drawRect(cropRect, erasePaint)

        // 3. White border
        canvas.drawRect(cropRect, borderPaint)

        // 4. Corner dots for visual affordance
        val r = cornerRadius
        canvas.drawCircle(cropRect.left,  cropRect.top,    r, cornerPaint)
        canvas.drawCircle(cropRect.right, cropRect.top,    r, cornerPaint)
        canvas.drawCircle(cropRect.left,  cropRect.bottom, r, cornerPaint)
        canvas.drawCircle(cropRect.right, cropRect.bottom, r, cornerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Accept drag anywhere inside the crop rect (plus a small margin)
                val margin = 48f * resources.displayMetrics.density
                val expanded = RectF(
                    cropRect.left   - margin,
                    cropRect.top    - margin,
                    cropRect.right  + margin,
                    cropRect.bottom + margin
                )
                if (expanded.contains(event.x, event.y)) {
                    isDragging  = true
                    dragOffsetX = event.x - cropRect.centerX()
                    dragOffsetY = event.y - cropRect.centerY()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    moveCropTo(event.x - dragOffsetX, event.y - dragOffsetY)
                    return true
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return super.onTouchEvent(event)
    }

    private fun moveCropTo(centerX: Float, centerY: Float) {
        val halfW  = cropRect.width()  / 2f
        val halfH  = cropRect.height() / 2f

        // Clamp so the square never leaves the screen
        val clampedX = centerX.coerceIn(halfW, width  - halfW)
        val clampedY = centerY.coerceIn(halfH, height - halfH)

        cropRect.set(
            clampedX - halfW,
            clampedY - halfH,
            clampedX + halfW,
            clampedY + halfH
        )
        invalidate()
    }

    /**
     * Returns the current crop square in this view's coordinate space.
     * ModelPreviewActivity converts these to bitmap pixels after PixelCopy.
     */
    fun getCropRect(): RectF = RectF(cropRect)
}