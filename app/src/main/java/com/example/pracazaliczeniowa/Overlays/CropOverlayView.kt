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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Full-screen overlay that draws:
 *  - a semi-transparent dark scrim over the whole screen
 *  - a transparent "punch-out" square showing exactly what will be captured
 *  - a white border around that square
 *  - drag handles (corner dots) so the user can reposition/resize the frame
 *
 * Gestures:
 *  - 1-finger drag anywhere inside the rect  → move
 *  - 2-finger pinch anywhere on the view     → resize (keeping the centre fixed)
 *
 * [getCropRect] returns the current rectangle in view coordinates.
 * ModelPreviewActivity converts these to bitmap pixels after PixelCopy.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Crop rect in view pixels ──────────────────────────────────────────
    private val cropRect = RectF()

    // ── Drag state (1-finger) ─────────────────────────────────────────────
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var isDragging  = false

    // ── Pinch-resize state (2-finger) ─────────────────────────────────────
    private var isPinching        = false
    private var pinchStartDist    = 0f   // finger spacing when pinch began
    private var pinchStartWidth   = 0f   // cropRect width  when pinch began
    private var pinchStartHeight  = 0f   // cropRect height when pinch began
    private var pinchCenterX      = 0f   // midpoint between fingers (view px)
    private var pinchCenterY      = 0f

    /** Minimum side length in px — prevents collapsing the rect to nothing. */
    private val minSizePx = 80f * resources.displayMetrics.density

    // ── Paints ────────────────────────────────────────────────────────────
    private val scrimPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
    }

    private val erasePaint = Paint().apply {
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
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // ── Layout ────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        if (w == 0 || h == 0) return

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

    // ── Drawing ───────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawRect(cropRect, erasePaint)
        canvas.drawRect(cropRect, borderPaint)

        val r = cornerRadius
        canvas.drawCircle(cropRect.left,  cropRect.top,    r, cornerPaint)
        canvas.drawCircle(cropRect.right, cropRect.top,    r, cornerPaint)
        canvas.drawCircle(cropRect.left,  cropRect.bottom, r, cornerPaint)
        canvas.drawCircle(cropRect.right, cropRect.bottom, r, cornerPaint)
    }

    // ── Touch ─────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {

            // ── First finger down → start potential drag ──────────────────
            MotionEvent.ACTION_DOWN -> {
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
                }
                return true          // always consume so ACTION_MOVE fires
            }

            // ── Second finger down → switch to pinch-resize ───────────────
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    isDragging       = false  // pause drag while pinching
                    isPinching       = true
                    pinchStartDist   = fingerSpacing(event)
                    pinchStartWidth  = cropRect.width()
                    pinchStartHeight = cropRect.height()
                    pinchCenterX     = (event.getX(0) + event.getX(1)) / 2f
                    pinchCenterY     = (event.getY(0) + event.getY(1)) / 2f
                }
                return true
            }

            // ── Move → either drag or pinch ───────────────────────────────
            MotionEvent.ACTION_MOVE -> {
                if (isPinching && event.pointerCount >= 2) {
                    val dist  = fingerSpacing(event)
                    if (pinchStartDist > 0f) {
                        val scale     = dist / pinchStartDist
                        val newWidth  = (pinchStartWidth  * scale).coerceAtLeast(minSizePx)
                        val newHeight = (pinchStartHeight * scale).coerceAtLeast(minSizePx)
                        resizeCropAroundCenter(
                            cx        = pinchCenterX,
                            cy        = pinchCenterY,
                            newWidth  = newWidth,
                            newHeight = newHeight
                        )
                    }
                } else if (isDragging) {
                    moveCropTo(event.x - dragOffsetX, event.y - dragOffsetY)
                }
                return true
            }

            // ── A finger lifted ───────────────────────────────────────────
            MotionEvent.ACTION_POINTER_UP -> {
                if (isPinching) {
                    isPinching  = false
                    // Resume drag from whichever finger remains
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    isDragging  = true
                    dragOffsetX = event.getX(remaining) - cropRect.centerX()
                    dragOffsetY = event.getY(remaining) - cropRect.centerY()
                }
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                isPinching = false
            }
        }
        return super.onTouchEvent(event)
    }

    // ── Geometry helpers ──────────────────────────────────────────────────

    /** Move the crop rect so its centre lands at (centerX, centerY), clamped to the view. */
    private fun moveCropTo(centerX: Float, centerY: Float) {
        val halfW = cropRect.width()  / 2f
        val halfH = cropRect.height() / 2f
        val cx    = centerX.coerceIn(halfW, width  - halfW)
        val cy    = centerY.coerceIn(halfH, height - halfH)
        cropRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        invalidate()
    }

    /**
     * Resize the crop rect to [newWidth] × [newHeight], keeping it centred on
     * ([cx], [cy]), then clamp the whole rect to stay inside the view.
     */
    private fun resizeCropAroundCenter(cx: Float, cy: Float, newWidth: Float, newHeight: Float) {
        // Clamp the size so it can't exceed the view
        val w = min(newWidth,  width.toFloat())
        val h = min(newHeight, height.toFloat())

        var left   = cx - w / 2f
        var top    = cy - h / 2f
        var right  = cx + w / 2f
        var bottom = cy + h / 2f

        // Slide to stay inside the screen
        if (left  < 0f)           { right  -= left;        left   = 0f }
        if (top   < 0f)           { bottom -= top;         top    = 0f }
        if (right  > width)       { left   -= right  - width;  right  = width.toFloat() }
        if (bottom > height)      { top    -= bottom - height; bottom = height.toFloat() }

        cropRect.set(left, top, right, bottom)
        invalidate()
    }

    private fun fingerSpacing(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    /** Returns the current crop rectangle in this view's coordinate space. */
    fun getCropRect(): RectF = RectF(cropRect)
}