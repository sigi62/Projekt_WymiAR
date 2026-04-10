package com.example.pracazaliczeniowa

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.lifecycle.lifecycleScope
import com.example.pracazaliczeniowa.Helpers.HsvColorPicker
import com.example.pracazaliczeniowa.Overlays.CropOverlayView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import com.google.android.filament.Engine
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class ModelPreviewActivity : AppCompatActivity() {

    private sealed class BgMode {
        object Hdr : BgMode()
        data class SolidColour(@ColorInt val color: Int) : BgMode()
        object Studio : BgMode()
    }

    companion object {
        const val EXTRA_MODEL_PATH = "extra_model_path"
        const val EXTRA_PROFILE_KEY = "extra_profile_key"
        private const val TAG = "ModelPreviewActivity"
        private const val CAM_DIST_INIT = 2.5f
        private const val CAM_ELEV_DEG_INIT = 35.0
        private const val CAM_AZIM_DEG_INIT = 45.0
        private const val CAM_DIST_MIN = 0.5f
        private const val CAM_DIST_MAX = 10f
        private const val CAM_ELEV_MIN = -89.0
        private const val CAM_ELEV_MAX = 89.0
        private const val THUMB_PX = 360
        private const val THUMB_QUALITY = 85
        private val DEFAULT_VOID_COLOR = Color.parseColor("#DCDCDC")
        private val STUDIO_PLANE_COLOR = Color.parseColor("#E8E8E8")

        private val PRESET_COLORS = listOf(
            Color.parseColor("#DCDCDC"),
            Color.parseColor("#1A1A2E"),
            Color.parseColor("#2D2D2D"),
            Color.parseColor("#FFFFFF"),
            Color.parseColor("#0D3B66"),
            Color.parseColor("#3D5A3E"),
        )
    }

    private lateinit var sceneView: SceneView
    private lateinit var cropOverlay: CropOverlayView
    private lateinit var normalButtonBar: LinearLayout
    private lateinit var cropConfirmBar: LinearLayout
    private lateinit var btnBackground: ImageButton

    private var modelNode: ModelNode? = null
    private lateinit var profileKey: String
    private var currentBgMode: BgMode = BgMode.SolidColour(DEFAULT_VOID_COLOR)
    private var studioFloorNode: Node? = null
    private var studioWallNode: Node? = null
    private var captureNextFrame = false

    private var camDist = CAM_DIST_INIT
    private var camElevDeg = CAM_ELEV_DEG_INIT
    private var camAzimDeg = CAM_AZIM_DEG_INIT
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var initialPinchDist = 0f
    private var initialCamDist = 0f
    private var isTwoFinger = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_preview)

        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: run { finish(); return }
        profileKey = intent.getStringExtra(EXTRA_PROFILE_KEY) ?: run { finish(); return }

        sceneView = findViewById(R.id.previewSceneView)
        cropOverlay = findViewById(R.id.cropOverlay)
        normalButtonBar = findViewById(R.id.normalButtonBar)
        cropConfirmBar = findViewById(R.id.cropConfirmBar)
        btnBackground = findViewById(R.id.btnBackground)

        applyBackground(currentBgMode)

        lifecycleScope.launch {
            try {
                sceneView.environmentLoader.loadHDREnvironment("envs/environment.hdr")?.let {
                    sceneView.environment = it
                    applyBackground(currentBgMode)
                }
            } catch (e: Exception) { Log.w(TAG, "Env load failed: ${e.message}") }
        }

        findViewById<ImageButton>(R.id.btnPreviewBack).setOnClickListener { finish() }
        btnBackground.setOnClickListener { showBackgroundPicker() }
        findViewById<Button>(R.id.btnTakeScreenshot).setOnClickListener { showCropOverlay() }
        findViewById<Button>(R.id.btnCropCancel).setOnClickListener { hideCropOverlay() }
        findViewById<Button>(R.id.btnCropConfirm).setOnClickListener {
            pendingCropRect = cropOverlay.getCropRect()
            hideCropOverlay()
            captureNextFrame = true
        }

        lifecycleScope.launch { loadModel(modelPath) }
        sceneView.onFrame = { if (captureNextFrame) { captureNextFrame = false; captureAndSaveThumbnail() } }
        setupTouchListener()
    }

    private fun applyBackground(mode: BgMode) {
        removeStudioPlanes()
        currentBgMode = mode
        when (mode) {
            is BgMode.Hdr -> {
                sceneView.environment?.let { sceneView.skybox = it.skybox }
                sceneView.renderer.clearOptions = sceneView.renderer.clearOptions.apply { clear = false }
            }
            is BgMode.SolidColour -> {
                sceneView.skybox = null
                window.decorView.setBackgroundColor(mode.color)
                sceneView.renderer.clearOptions = sceneView.renderer.clearOptions.apply {
                    clear = true
                    clearColor[0] = mode.color.red / 255f
                    clearColor[1] = mode.color.green / 255f
                    clearColor[2] = mode.color.blue / 255f
                    clearColor[3] = 1f
                }
            }
            is BgMode.Studio -> {
                sceneView.skybox = null
                window.decorView.setBackgroundColor(STUDIO_PLANE_COLOR)
                sceneView.renderer.clearOptions = sceneView.renderer.clearOptions.apply {
                    clear = true
                    clearColor[0] = STUDIO_PLANE_COLOR.red / 255f
                    clearColor[1] = STUDIO_PLANE_COLOR.green / 255f
                    clearColor[2] = STUDIO_PLANE_COLOR.blue / 255f
                    clearColor[3] = 1f
                }
                buildStudioPlanes()
            }
        }
    }

    private fun showBackgroundPicker() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_background_color_picker, null)
        sheet.setContentView(view)

        val rgMode = view.findViewById<RadioGroup>(R.id.rgBackgroundMode)
        val layoutColour = view.findViewById<View>(R.id.layoutColourPicker)
        val swatchRow = view.findViewById<LinearLayout>(R.id.swatchRow)
        val vCurrentColour = view.findViewById<View>(R.id.vCurrentColour)
        val interactivePicker = view.findViewById<HsvColorPicker>(R.id.interactiveColorPicker)

        var pickedColor = when (val m = currentBgMode) {
            is BgMode.SolidColour -> m.color
            else -> DEFAULT_VOID_COLOR
        }

        // Setup Initial UI State
        when (currentBgMode) {
            is BgMode.Hdr -> {
                view.findViewById<RadioButton>(R.id.rbModeHdr).isChecked = true
                layoutColour.visibility = View.GONE
            }
            is BgMode.Studio -> {
                view.findViewById<RadioButton>(R.id.rbModeStudio).isChecked = true
                layoutColour.visibility = View.GONE
            }
            is BgMode.SolidColour -> {
                view.findViewById<RadioButton>(R.id.rbModeVoid).isChecked = true
                layoutColour.visibility = View.VISIBLE
            }
        }

        interactivePicker.setColor(pickedColor)
        updateColourPreview(vCurrentColour, pickedColor)

        // Mode Switching
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbModeHdr -> { layoutColour.visibility = View.GONE; applyBackground(BgMode.Hdr) }
                R.id.rbModeStudio -> { layoutColour.visibility = View.GONE; applyBackground(BgMode.Studio) }
                R.id.rbModeVoid -> { layoutColour.visibility = View.VISIBLE; applyBackground(BgMode.SolidColour(pickedColor)) }
            }
        }

        // Live Interactive Picker
        interactivePicker.onColorChanged = { color ->
            pickedColor = color
            updateColourPreview(vCurrentColour, color)
            refreshSwatchRings(swatchRow, color, resources.displayMetrics.density)
            applyBackground(BgMode.SolidColour(color))
        }

        // Preset Swatches
        val dp = resources.displayMetrics.density
        PRESET_COLORS.forEach { presetColor ->
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()).also { it.marginEnd = (8 * dp).toInt() }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6 * dp
                    setColor(presetColor)
                    if (presetColor == pickedColor) setStroke((2 * dp).toInt(), Color.WHITE)
                }
                setOnClickListener {
                    pickedColor = presetColor
                    interactivePicker.setColor(presetColor)
                    updateColourPreview(vCurrentColour, presetColor)
                    refreshSwatchRings(swatchRow, presetColor, dp)
                    applyBackground(BgMode.SolidColour(presetColor))
                }
            }
            swatchRow.addView(swatch)
        }
        sheet.show()
    }

    private fun refreshSwatchRings(row: LinearLayout, @ColorInt selected: Int, dp: Float) {
        for (i in 0 until row.childCount) {
            val color = PRESET_COLORS.getOrNull(i) ?: continue
            (row.getChildAt(i).background as? GradientDrawable)?.apply {
                setColor(color)
                setStroke(if (color == selected) (2 * dp).toInt() else 0, if (color == selected) Color.WHITE else Color.TRANSPARENT)
            }
        }
    }

    private fun updateColourPreview(view: View, @ColorInt color: Int) {
        (view.background as? GradientDrawable)?.setColor(color) ?: view.setBackgroundColor(color)
    }

    // --- Studio Plane and Mesh Building (Simplified for brevity, matches original logic) ---
    private fun buildStudioPlanes() {
        val engine = sceneView.engine
        val r = STUDIO_PLANE_COLOR.red / 255f
        val g = STUDIO_PLANE_COLOR.green / 255f
        val b = STUDIO_PLANE_COLOR.blue / 255f

        val floorVerts = floatArrayOf(-3f, 0f, -3f, 3f, 0f, -3f, 3f, 0f, 3f, -3f, 0f, 3f)
        val wallVerts = floatArrayOf(-3f, -2f, 0f, 3f, -2f, 0f, 3f, 2f, 0f, -3f, 2f, 0f)
        val indices = shortArrayOf(0, 1, 2, 0, 2, 3)

        studioFloorNode = buildPlaneNode(engine, floorVerts, indices, r, g, b, Position(0f, -0.55f, 0f), Rotation(0f, 0f, 0f))?.also { sceneView.addChildNode(it) }
        studioWallNode = buildPlaneNode(engine, wallVerts, indices, r, g, b, Position(0f, 2.0f, -1.8f), Rotation(0f, 0f, 0f))?.also { sceneView.addChildNode(it) }
    }

    private fun buildPlaneNode(engine: Engine, vertices: FloatArray, indices: ShortArray, r: Float, g: Float, b: Float, pos: Position, rot: Rotation): Node? {
        return try {
            val vBuf = VertexBuffer.Builder().vertexCount(vertices.size / 3).bufferCount(1).attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 0).build(engine)
            val vData = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).apply { vertices.forEach { putFloat(it) }; flip() }
            vBuf.setBufferAt(engine, 0, vData)

            val iBuf = IndexBuffer.Builder().indexCount(indices.size).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
            val iData = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).apply { indices.forEach { putShort(it) }; flip() }
            iBuf.setBuffer(engine, iData)

            val mat = sceneView.materialLoader.createColorInstance(Color.argb(255, (r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt()))
            Node(engine).also { node ->
                RenderableManager.Builder(1).geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vBuf, iBuf).material(0, mat).culling(false).receiveShadows(true).build(engine, node.entity)
                node.position = pos; node.rotation = rot
            }
        } catch (e: Exception) { null }
    }

    private fun removeStudioPlanes() {
        studioFloorNode?.let { sceneView.removeChildNode(it) }; studioWallNode?.let { sceneView.removeChildNode(it) }
        studioFloorNode = null; studioWallNode = null
    }

    private suspend fun loadModel(path: String) {
        try {
            val instance = sceneView.modelLoader.createModelInstance(path)
            modelNode = ModelNode(instance, true,1.0f, Position(0f, 0f, 0f)).apply { isScaleEditable = false; isRotationEditable = false }
            sceneView.addChildNode(modelNode!!)
            updateCamera()
        } catch (e: Exception) { Log.e(TAG, "Model load failed", e) }
    }

    private fun updateCamera() {
        val elev = Math.toRadians(camElevDeg).toFloat(); val azim = Math.toRadians(camAzimDeg).toFloat()
        sceneView.cameraNode.position = Position(camDist * cos(elev) * sin(azim), camDist * sin(elev), camDist * cos(elev) * cos(azim))
        sceneView.cameraNode.lookAt(Position(0f, 0f, 0f))
    }

    private fun setupTouchListener() {
        sceneView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { lastTouchX = event.x; lastTouchY = event.y; isTwoFinger = false }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) { isTwoFinger = true; initialPinchDist = fingerSpacing(event); initialCamDist = camDist }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (isTwoFinger && event.pointerCount >= 2) {
                        val dist = fingerSpacing(event)
                        if (initialPinchDist > 0f) { camDist = (initialCamDist * initialPinchDist / dist).coerceIn(CAM_DIST_MIN, CAM_DIST_MAX); updateCamera() }
                    } else {
                        camAzimDeg -= (event.x - lastTouchX) * 0.3; camElevDeg = (camElevDeg + (event.y - lastTouchY) * 0.3).coerceIn(CAM_ELEV_MIN, CAM_ELEV_MAX)
                        lastTouchX = event.x; lastTouchY = event.y; updateCamera()
                    }
                }
            }
            true
        }
    }

    private fun fingerSpacing(e: android.view.MotionEvent): Float {
        val x = e.getX(0) - e.getX(1); val y = e.getY(0) - e.getY(1); return sqrt(x * x + y * y)
    }

    private fun showCropOverlay() { cropOverlay.visibility = View.VISIBLE; cropConfirmBar.visibility = View.VISIBLE; normalButtonBar.visibility = View.GONE; btnBackground.visibility = View.GONE; sceneView.setOnTouchListener(null) }
    private fun hideCropOverlay() { cropOverlay.visibility = View.GONE; cropConfirmBar.visibility = View.GONE; normalButtonBar.visibility = View.VISIBLE; btnBackground.visibility = View.VISIBLE; setupTouchListener() }

    private var pendingCropRect: RectF? = null
    private fun captureAndSaveThumbnail() {
        val w = sceneView.width; val h = sceneView.height; val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sceneView, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                val cropped = pendingCropRect?.let { cropBitmap(bitmap, it, w, h) } ?: bitmap
                saveThumbnailToDisk(scaledForCard(cropped))
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun cropBitmap(b: Bitmap, r: RectF, vw: Int, vh: Int): Bitmap {
        val sx = b.width.toFloat() / vw; val sy = b.height.toFloat() / vh
        return Bitmap.createBitmap(b, (r.left * sx).toInt().coerceIn(0, b.width), (r.top * sy).toInt().coerceIn(0, b.height), ((r.right - r.left) * sx).toInt().coerceAtLeast(1), ((r.bottom - r.top) * sy).toInt().coerceAtLeast(1))
    }

    private fun scaledForCard(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height); val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(square, THUMB_PX, THUMB_PX, true)
    }

    private fun saveThumbnailToDisk(bitmap: Bitmap) {
        try {
            val file = File(filesDir, "thumbnails/$profileKey.jpg").apply { parentFile?.mkdirs() }
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, it) }
        } catch (e: Exception) { Log.e(TAG, "Save failed", e) }
    }

    override fun onDestroy() { super.onDestroy(); removeStudioPlanes(); sceneView.destroy() }
}