package com.example.pracazaliczeniowa

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARSceneView
import kotlin.math.abs

/**
 * Transparent [View] overlay that draws three annotated dimension lines
 * (red = width/X, green = height/Y, blue = depth/Z) on top of [ARSceneView].
 *
 * Place it **after** the ARSceneView in the layout so it sits on top:
 *   <DimensionOverlayView android:id="@+id/dimensionOverlay" match_parent />
 *
 * Then call [attach] once both the sceneView and the node are ready.
 */
class DimensionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var sceneView: ARSceneView? = null
    private var node: MeasurableModelNode? = null

    // Cached metric values (updated via onDimensionsChanged)
    private var widthM  = 0f
    private var heightM = 0f
    private var depthM  = 0f

    // ── Paints ────────────────────────────────────────────────────────────

    private fun linePaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color  = color
        strokeWidth = 6f
        style       = Paint.Style.STROKE
        strokeCap   = Paint.Cap.ROUND
    }

    private val redPaint   = linePaint(Color.RED)
    private val greenPaint = linePaint(Color.GREEN)
    private val bluePaint  = linePaint(Color.rgb(80, 140, 255))

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.WHITE
        textSize  = 42f
        typeface  = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // ── Projection matrices ───────────────────────────────────────────────

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp   = FloatArray(16)

    // ── Choreographer (per-frame redraw) ──────────────────────────────────

    private val frameCallback: Choreographer.FrameCallback = Choreographer.FrameCallback {
        invalidate()
        if (isAttachedToWindow) Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Connect the overlay to the scene and the node to visualise.
     * Safe to call before or after the model finishes loading.
     */
    fun attach(sv: ARSceneView, measurableNode: MeasurableModelNode) {
        sceneView = sv
        node = measurableNode
        measurableNode.onDimensionsChanged = { w, h, d ->
            widthM  = w
            heightM = h
            depthM  = d
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val sv      = sceneView ?: return
        val mn      = node ?: return
        val endpoints = mn.getWorldAxisEndpoints() ?: return

        // Get ARCore matrices from the current session frame.
        val frame  = sv.frame ?: return
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return

        camera.getProjectionMatrix(proj, 0, 0.05f, 100f)
        camera.getViewMatrix(view, 0)
        android.opengl.Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        val sw = width.toFloat()
        val sh = height.toFloat()

        // endpoints: [0]=-X [1]=+X [2]=-Y [3]=+Y [4]=-Z [5]=+Z
        val pNX = project(endpoints[0], sw, sh) ?: return
        val pPX = project(endpoints[1], sw, sh) ?: return
        val pNY = project(endpoints[2], sw, sh) ?: return
        val pPY = project(endpoints[3], sw, sh) ?: return
        val pNZ = project(endpoints[4], sw, sh) ?: return
        val pPZ = project(endpoints[5], sw, sh) ?: return

        // Lines
        canvas.drawLine(pNX[0], pNX[1], pPX[0], pPX[1], redPaint)
        canvas.drawLine(pNY[0], pNY[1], pPY[0], pPY[1], greenPaint)
        canvas.drawLine(pNZ[0], pNZ[1], pPZ[0], pPZ[1], bluePaint)

        // Labels at midpoint of each line
        drawLabel(canvas, "W: ${"%.2f".format(widthM)} m",
            midpoint(pNX, pPX), redPaint.color)
        drawLabel(canvas, "H: ${"%.2f".format(heightM)} m",
            midpoint(pNY, pPY), greenPaint.color)
        drawLabel(canvas, "D: ${"%.2f".format(depthM)} m",
            midpoint(pNZ, pPZ), bluePaint.color)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Column-major VP multiply then perspective divide → screen pixels. */
    private fun project(world: Float3, sw: Float, sh: Float): FloatArray? {
        val x = vp[0]*world.x + vp[4]*world.y + vp[8]*world.z  + vp[12]
        val y = vp[1]*world.x + vp[5]*world.y + vp[9]*world.z  + vp[13]
        val w = vp[3]*world.x + vp[7]*world.y + vp[11]*world.z + vp[15]
        if (abs(w) < 1e-6f || w < 0f) return null          // behind camera
        val ndcX =  x / w
        val ndcY = -y / w                                   // flip Y for screen
        return floatArrayOf(
            (ndcX + 1f) * 0.5f * sw,
            (ndcY + 1f) * 0.5f * sh,
        )
    }

    private fun midpoint(a: FloatArray, b: FloatArray) =
        floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f)

    private fun drawLabel(canvas: Canvas, text: String, mid: FloatArray, color: Int) {
        val tw = textPaint.measureText(text)
        val th = textPaint.textSize
        val pad = 8f
        val x = mid[0] - tw / 2f
        val y = mid[1]

        // Background pill
        canvas.drawRoundRect(
            x - pad, y - th - pad,
            x + tw + pad, y + pad,
            12f, 12f, bgPaint
        )
        // Coloured text
        textPaint.color = color
        canvas.drawText(text, x, y, textPaint)
    }
}
