package com.example.pracazaliczeniowa.Overlays

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.google.ar.core.TrackingState
import com.example.pracazaliczeniowa.Objects.DistanceUnit
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import kotlin.math.abs
import kotlin.math.sqrt

class MeasureTapeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {


    private var sceneView: ARSceneView? = null

    private val measureLines = mutableListOf<Pair<AnchorNode, AnchorNode>>()

    private var pendingAnchor: AnchorNode? = null

    private var unit: DistanceUnit = DistanceUnit.METERS


    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(20f, 12f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 44f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val pointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val pointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp   = FloatArray(16)

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

    fun attach(sv: ARSceneView) {
        sceneView = sv
    }

    fun setMeasureData(
        lines: List<Pair<AnchorNode, AnchorNode>>,
        pending: AnchorNode?,
    ) {
        measureLines.clear()
        measureLines.addAll(lines)
        pendingAnchor = pending
    }

    fun setUnit(u: DistanceUnit) {
        unit = u
    }

    fun clear() {
        measureLines.clear()
        pendingAnchor = null
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val sv    = sceneView ?: return
        val frame = sv.frame  ?: return
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return

        camera.getProjectionMatrix(proj, 0, 0.05f, 100f)
        camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        val sw = width.toFloat()
        val sh = height.toFloat()

        for ((nodeA, nodeB) in measureLines) {
            val a = nodeA.worldPosition.let { Float3(it.x, it.y, it.z) }
            val b = nodeB.worldPosition.let { Float3(it.x, it.y, it.z) }

            val pa = project(a, sw, sh) ?: continue
            val pb = project(b, sw, sh) ?: continue

            canvas.drawLine(pa[0], pa[1], pb[0], pb[1], linePaint)
            drawPoint(canvas, pa[0], pa[1])
            drawPoint(canvas, pb[0], pb[1])

            val mid  = floatArrayOf((pa[0] + pb[0]) / 2f, (pa[1] + pb[1]) / 2f)
            val dist = distanceMeters(a, b)
            val (value, suffix) = unit.convert(dist)
            drawLabel(canvas, String.format("%.1f %s", value, suffix), mid)
        }

        pendingAnchor?.let { node ->
            val p = node.worldPosition.let { Float3(it.x, it.y, it.z) }
            val sp = project(p, sw, sh) ?: return@let
            drawPoint(canvas, sp[0], sp[1])
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun distanceMeters(a: Float3, b: Float3): Float {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun project(world: Float3, sw: Float, sh: Float): FloatArray? {
        val x = vp[0]*world.x + vp[4]*world.y + vp[8]*world.z  + vp[12]
        val y = vp[1]*world.x + vp[5]*world.y + vp[9]*world.z  + vp[13]
        val w = vp[3]*world.x + vp[7]*world.y + vp[11]*world.z + vp[15]
        if (abs(w) < 1e-6f || w < 0f) return null
        val ndcX = x / w
        val ndcY = -y / w
        return floatArrayOf(
            (ndcX + 1f) * 0.5f * sw,
            (ndcY + 1f) * 0.5f * sh,
        )
    }

    private fun drawLabel(canvas: Canvas, text: String, mid: FloatArray) {
        val tw  = textPaint.measureText(text)
        val th  = textPaint.textSize
        val pad = 10f
        val x   = mid[0] - tw / 2f
        val y   = mid[1]
        canvas.drawRoundRect(x - pad, y - th - pad, x + tw + pad, y + pad, 14f, 14f, bgPaint)
        canvas.drawText(text, x, y, textPaint)
    }

    private fun drawPoint(canvas: Canvas, x: Float, y: Float) {
        val r = 14f
        canvas.drawCircle(x, y, r, pointFillPaint)
        canvas.drawCircle(x, y, r, pointStrokePaint)
    }
}