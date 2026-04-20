package com.example.pracazaliczeniowa

import android.content.SharedPreferences
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
import android.widget.TextView
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
import com.google.android.filament.Box
import com.google.android.filament.MaterialInstance
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        const val EXTRA_MODEL_PATH    = "extra_model_path"
        const val EXTRA_MODEL_IS_ASSET = "extra_model_is_asset"
        const val EXTRA_PROFILE_KEY   = "extra_profile_key"
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

        // How many times larger than the model's bounding-sphere radius the studio
        // surfaces should be. 3× means they always extend well beyond the model.
        private const val STUDIO_SCALE_FACTOR = 3f

        private val DEFAULT_VOID_COLOR = Color.parseColor("#DCDCDC")

        // SharedPreferences
        private const val PREFS_NAME            = "model_preview_prefs"
        private const val KEY_BG_MODE           = "bg_mode"          // "hdr" | "void" | "studio"
        private const val KEY_VOID_COLOR        = "void_color"
        private const val KEY_STUDIO_FLOOR      = "studio_floor_color"
        private const val KEY_STUDIO_BACK_WALL  = "studio_back_wall_color"
        private const val KEY_STUDIO_SIDE_WALL  = "studio_side_wall_color"

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

    private lateinit var prefs: SharedPreferences

    // --- Studio nodes ---
    private var studioFloorNode:    Node? = null
    private var studioBackWallNode: Node? = null
    private var studioSideWallNode: Node? = null

    // --- Studio surface colours (persisted across sheet open/close) ---
    // Three distinct shades of gray: light floor, mid back wall, slightly darker side wall
    @ColorInt private var studioFloorColor    = Color.parseColor("#C8C8C8")
    @ColorInt private var studioBackWallColor = Color.parseColor("#E0E0E0")
    @ColorInt private var studioSideWallColor = Color.parseColor("#B0B0B0")

    // Approximate bounding-sphere radius of the loaded model (default 1 m).
    // Updated in loadModel() once the model node exists.
    private var modelRadius = 1f

    private var captureNextFrame = false

    // Track GPU resources so we can destroy them properly and avoid leaks
    private val studioVBufs = mutableListOf<VertexBuffer>()
    private val studioIBufs = mutableListOf<IndexBuffer>()
    private val studioMats  = mutableListOf<MaterialInstance>()

    // Material-instance references kept separately so we can tint them live
    // without tearing down and rebuilding the whole plane geometry.
    private var floorMatInstance:    MaterialInstance? = null
    private var backWallMatInstance: MaterialInstance? = null
    private var sideWallMatInstance: MaterialInstance? = null

    private var camDist = CAM_DIST_INIT
    private var camElevDeg = CAM_ELEV_DEG_INIT
    private var camAzimDeg = CAM_AZIM_DEG_INIT
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var initialPinchDist = 0f
    private var initialCamDist = 0f
    private var isTwoFinger = false

    // -------------------------------------------------------------------------
    // sRGB → linear conversion for Filament clearColor
    // -------------------------------------------------------------------------

    private fun srgbToLinear(channel: Int): Float {
        val c = channel / 255f
        return if (c <= 0.04045f) c / 12.92f
        else Math.pow((c + 0.055) / 1.055, 2.4).toFloat()
    }

    private fun @receiver:ColorInt Int.linearR() = srgbToLinear(this.red)
    private fun @receiver:ColorInt Int.linearG() = srgbToLinear(this.green)
    private fun @receiver:ColorInt Int.linearB() = srgbToLinear(this.blue)

    // -------------------------------------------------------------------------
    // onCreate
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: start")
        setContentView(R.layout.activity_model_preview)

        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: run {
            Log.e(TAG, "onCreate: EXTRA_MODEL_PATH is null — finishing")
            finish(); return
        }

        val modelIsAsset = intent.getBooleanExtra(EXTRA_MODEL_IS_ASSET, true)
        profileKey = intent.getStringExtra(EXTRA_PROFILE_KEY) ?: run {
            Log.e(TAG, "onCreate: EXTRA_PROFILE_KEY is null — finishing")
            finish(); return
        }

        sceneView        = findViewById(R.id.previewSceneView)
        cropOverlay      = findViewById(R.id.cropOverlay)
        normalButtonBar  = findViewById(R.id.normalButtonBar)
        cropConfirmBar   = findViewById(R.id.cropConfirmBar)
        btnBackground    = findViewById(R.id.btnBackground)

        // Restore persisted colors and bg mode
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        studioFloorColor    = prefs.getInt(KEY_STUDIO_FLOOR,     Color.parseColor("#C8C8C8"))
        studioBackWallColor = prefs.getInt(KEY_STUDIO_BACK_WALL, Color.parseColor("#E0E0E0"))
        studioSideWallColor = prefs.getInt(KEY_STUDIO_SIDE_WALL, Color.parseColor("#B0B0B0"))
        val savedVoidColor  = prefs.getInt(KEY_VOID_COLOR, DEFAULT_VOID_COLOR)
        currentBgMode = when (prefs.getString(KEY_BG_MODE, "void")) {
            "hdr"    -> BgMode.Hdr
            "studio" -> BgMode.Studio
            else     -> BgMode.SolidColour(savedVoidColor)
        }

        applyBackground(currentBgMode)

        lifecycleScope.launch {
            try {
                sceneView.environmentLoader.loadHDREnvironment("envs/environment.hdr")?.let {
                    sceneView.environment = it
                    applyBackground(currentBgMode)
                }
            } catch (e: Exception) {
                Log.e(TAG, "HDR env: load failed", e)
            }
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

        lifecycleScope.launch { loadModel(modelPath, modelIsAsset) }
        sceneView.onFrame = { if (captureNextFrame) { captureNextFrame = false; captureAndSaveThumbnail() } }
        setupTouchListener()
        Log.d(TAG, "onCreate: complete")
    }

    // -------------------------------------------------------------------------
    // Background
    // -------------------------------------------------------------------------

    private fun applyBackground(mode: BgMode) {
        Log.d(TAG, "applyBackground: mode=${mode::class.simpleName}")
        removeStudioPlanes()
        currentBgMode = mode
        when (mode) {
            is BgMode.Hdr -> {
                sceneView.environment?.let { sceneView.skybox = it.skybox }
                    ?: Log.w(TAG, "applyBackground: Hdr mode but environment is null")
                sceneView.renderer.clearOptions = sceneView.renderer.clearOptions.apply { clear = false }
            }
            is BgMode.SolidColour -> {
                sceneView.skybox = null
                window.decorView.setBackgroundColor(mode.color)
                sceneView.renderer.clearOptions = sceneView.renderer.clearOptions.apply {
                    clear = true
                    clearColor[0] = mode.color.linearR()
                    clearColor[1] = mode.color.linearG()
                    clearColor[2] = mode.color.linearB()
                    clearColor[3] = 1f
                }
            }
            is BgMode.Studio -> {
                sceneView.skybox = null
                // Use the back-wall color for the clear color so the infinite void
                // behind and above the walls blends naturally instead of showing the floor tint.
                window.decorView.setBackgroundColor(studioBackWallColor)
                sceneView.renderer.clearOptions = sceneView.renderer.clearOptions.apply {
                    clear = true
                    clearColor[0] = studioBackWallColor.linearR()
                    clearColor[1] = studioBackWallColor.linearG()
                    clearColor[2] = studioBackWallColor.linearB()
                    clearColor[3] = 1f
                }
                buildStudioPlanes()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Background picker bottom sheet
    // -------------------------------------------------------------------------

    private fun showBackgroundPicker() {
        val sheet = BottomSheetDialog(this)
        val view  = layoutInflater.inflate(R.layout.dialog_background_color_picker, null)
        sheet.setContentView(view)

        val rgMode         = view.findViewById<RadioGroup>(R.id.rgBackgroundMode)
        val layoutColour   = view.findViewById<View>(R.id.layoutColourPicker)
        val layoutStudio   = view.findViewById<View>(R.id.layoutStudioColors)
        val swatchRow      = view.findViewById<LinearLayout>(R.id.swatchRow)
        val vCurrentColour = view.findViewById<View>(R.id.vCurrentColour)
        val interactivePicker = view.findViewById<HsvColorPicker>(R.id.interactiveColorPicker)

        // Studio surface color dot buttons
        val dotFloor    = view.findViewById<View>(R.id.dotFloorColor)
        val dotBackWall = view.findViewById<View>(R.id.dotBackWallColor)
        val dotSideWall = view.findViewById<View>(R.id.dotSideWallColor)

        // Sync dot backgrounds to current colours
        fun syncDots() {
            (dotFloor.background    as? GradientDrawable)?.setColor(studioFloorColor)
            (dotBackWall.background as? GradientDrawable)?.setColor(studioBackWallColor)
            (dotSideWall.background as? GradientDrawable)?.setColor(studioSideWallColor)
        }
        syncDots()

        var pickedColor = when (val m = currentBgMode) {
            is BgMode.SolidColour -> m.color
            else -> DEFAULT_VOID_COLOR
        }

        // Setup initial UI state
        when (currentBgMode) {
            is BgMode.Hdr -> {
                view.findViewById<RadioButton>(R.id.rbModeHdr).isChecked = true
                layoutColour.visibility = View.GONE
                layoutStudio.visibility = View.GONE
            }
            is BgMode.Studio -> {
                view.findViewById<RadioButton>(R.id.rbModeStudio).isChecked = true
                layoutColour.visibility = View.GONE
                layoutStudio.visibility = View.VISIBLE
            }
            is BgMode.SolidColour -> {
                view.findViewById<RadioButton>(R.id.rbModeVoid).isChecked = true
                layoutColour.visibility = View.VISIBLE
                layoutStudio.visibility = View.GONE
            }
        }

        interactivePicker.setColor(pickedColor)
        updateColourPreview(vCurrentColour, pickedColor)

        // Mode switching
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbModeHdr    -> {
                    layoutColour.visibility = View.GONE
                    layoutStudio.visibility = View.GONE
                    applyBackground(BgMode.Hdr)
                    prefs.edit().putString(KEY_BG_MODE, "hdr").apply()
                }
                R.id.rbModeStudio -> {
                    layoutColour.visibility = View.GONE
                    layoutStudio.visibility = View.VISIBLE
                    applyBackground(BgMode.Studio)
                    prefs.edit().putString(KEY_BG_MODE, "studio").apply()
                }
                R.id.rbModeVoid   -> {
                    layoutColour.visibility = View.VISIBLE
                    layoutStudio.visibility = View.GONE
                    applyBackground(BgMode.SolidColour(pickedColor))
                    prefs.edit().putString(KEY_BG_MODE, "void")
                        .putInt(KEY_VOID_COLOR, pickedColor).apply()
                }
            }
        }

        // Live interactive picker (Void mode)
        interactivePicker.onColorChanged = { color ->
            pickedColor = color
            updateColourPreview(vCurrentColour, color)
            refreshSwatchRings(swatchRow, color, resources.displayMetrics.density)
            applyBackground(BgMode.SolidColour(color))
            prefs.edit().putInt(KEY_VOID_COLOR, color).apply()
        }

        // Preset swatches
        val dp = resources.displayMetrics.density
        PRESET_COLORS.forEach { presetColor ->
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
                    .also { it.marginEnd = (8 * dp).toInt() }
                background = GradientDrawable().apply {
                    shape        = GradientDrawable.RECTANGLE
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
                    prefs.edit().putInt(KEY_VOID_COLOR, presetColor).apply()
                }
            }
            swatchRow.addView(swatch)
        }

        // Studio surface colour dot taps — open inline HSV mini-picker
        dotFloor.setOnClickListener {
            showSurfaceColorPicker("Floor Color", studioFloorColor) { newColor ->
                studioFloorColor = newColor
                syncDots()
                applyStudioSurfaceColor(SurfaceTarget.FLOOR, newColor)
                prefs.edit().putInt(KEY_STUDIO_FLOOR, newColor).apply()
            }
        }
        dotBackWall.setOnClickListener {
            showSurfaceColorPicker("Back Wall Color", studioBackWallColor) { newColor ->
                studioBackWallColor = newColor
                syncDots()
                applyStudioSurfaceColor(SurfaceTarget.BACK_WALL, newColor)
                prefs.edit().putInt(KEY_STUDIO_BACK_WALL, newColor).apply()
            }
        }
        dotSideWall.setOnClickListener {
            showSurfaceColorPicker("Side Wall Color", studioSideWallColor) { newColor ->
                studioSideWallColor = newColor
                syncDots()
                applyStudioSurfaceColor(SurfaceTarget.SIDE_WALL, newColor)
                prefs.edit().putInt(KEY_STUDIO_SIDE_WALL, newColor).apply()
            }
        }

        sheet.show()
    }

    // Which studio surface to recolor
    private enum class SurfaceTarget { FLOOR, BACK_WALL, SIDE_WALL }

    /**
     * Opens a compact HSV picker bottom sheet for a single studio surface.
     * On every color change the material instance is updated live — no geometry rebuild needed.
     */
    private fun showSurfaceColorPicker(
        title: String,
        @ColorInt initial: Int,
        onPick: (Int) -> Unit
    ) {
        val inner = BottomSheetDialog(this)
        // Build the UI programmatically so we don't need a separate layout file
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (32 * dp).toInt())
        }

        // Drag handle
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (4 * dp).toInt()).also {
                it.gravity = android.view.Gravity.CENTER_HORIZONTAL
                it.bottomMargin = (16 * dp).toInt()
            }
            setBackgroundColor(Color.parseColor("#44888888"))
        })

        // Title
        root.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (12 * dp).toInt() }
        })

        // Preview dot + label row
        val previewDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).also {
                it.marginEnd = (12 * dp).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(initial)
            }
        }
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (16 * dp).toInt() }
            addView(previewDot)
            addView(TextView(this@ModelPreviewActivity).apply {
                text = "Live preview"
                textSize = 13f
                setTextColor(Color.parseColor("#AAAAAA"))
            })
        }
        root.addView(previewRow)

        // HSV picker
        val picker = HsvColorPicker(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (200 * dp).toInt()
            ).also { it.bottomMargin = (8 * dp).toInt() }
            setColor(initial)
        }
        root.addView(picker)

        picker.onColorChanged = { color ->
            (previewDot.background as? GradientDrawable)?.setColor(color)
            onPick(color)
        }

        inner.setContentView(root)
        inner.show()
    }

    /**
     * Updates the color of a specific studio surface and rebuilds the studio planes.
     *
     * The previous implementation attempted a live material-parameter update via
     * mat.setParameter("baseColorFactor", ...). SceneView's createColorInstance wraps
     * a default material whose parameter name does not match that string, so the call
     * always threw, landed in the catch block, and called applyBackground(BgMode.Studio)
     * while the MaterialInstance was still tracked in studioMats — causing Filament to
     * crash when removeStudioPlanes then tried to destroyMaterialInstance on a resource
     * that the RenderableManager still held. The fix is to simply update the cached
     * color fields (already done by the caller) and do one clean rebuild.
     */
    private fun applyStudioSurfaceColor(target: SurfaceTarget, @ColorInt color: Int) {
        if (currentBgMode !is BgMode.Studio) return
        // studioFloorColor / studioBackWallColor / studioSideWallColor have already
        // been updated by the caller. A full rebuild is the only reliable path because
        // SceneView's material-instance API does not expose a stable parameter name for
        // tinting a plain colour material without reflection or internal access.
        applyBackground(BgMode.Studio)
    }

    private fun refreshSwatchRings(row: LinearLayout, @ColorInt selected: Int, dp: Float) {
        for (i in 0 until row.childCount) {
            val color = PRESET_COLORS.getOrNull(i) ?: continue
            (row.getChildAt(i).background as? GradientDrawable)?.apply {
                setColor(color)
                setStroke(
                    if (color == selected) (2 * dp).toInt() else 0,
                    if (color == selected) Color.WHITE else Color.TRANSPARENT
                )
            }
        }
    }

    private fun updateColourPreview(view: View, @ColorInt color: Int) {
        (view.background as? GradientDrawable)?.setColor(color) ?: view.setBackgroundColor(color)
    }

    // -------------------------------------------------------------------------
    // Studio planes
    // -------------------------------------------------------------------------

    /**
     * Computes a half-extent for the studio planes based on the model radius.
     * Floor and walls are STUDIO_SCALE_FACTOR × larger than the model's
     * bounding sphere, so they always extend well beyond any loaded model.
     */
    private fun studioHalfExtent(): Float = modelRadius * STUDIO_SCALE_FACTOR

    private fun buildStudioPlanes() {
        Log.d(TAG, "buildStudioPlanes: start  modelRadius=$modelRadius")
        val engine = sceneView.engine
        val h = studioHalfExtent()

        val floorY = -(modelRadius * 0.55f)   // sit just below the model centroid
        val wallZ  = -(h + 0.05f)             // back wall just behind the floor edge
        val wallX  = -(h + 0.05f)             // side wall just to the left of the floor edge

        // CCW indices shared by all quads
        val indices = shortArrayOf(0, 1, 2, 0, 2, 3)

        // ── Floor (XZ plane, normal +Y) ──────────────────────────────────────
        //   3----0
        //   |  / |   viewed from above (+Y), CCW
        //   | /  |
        //   2----1
        val floorVerts = floatArrayOf(
            -h,  floorY,  h,   // 0 front-left
            h,  floorY,  h,   // 1 front-right
            h,  floorY, -h,   // 2 back-right
            -h,  floorY, -h    // 3 back-left
        )

        // ── Back wall (XY plane, normal +Z, facing camera) ───────────────────
        //   3----2
        //   |  / |   viewed from front (-Z→+Z), CCW
        //   | /  |
        //   0----1
        val backWallVerts = floatArrayOf(
            -h, floorY, wallZ,   // 0 bottom-left
            h, floorY, wallZ,   // 1 bottom-right
            h,  h,     wallZ,   // 2 top-right
            -h,  h,     wallZ    // 3 top-left
        )

        // ── Side wall (YZ plane, normal +X, facing right toward camera) ───────
        //   3----2
        //   |  / |   viewed from the right (+X→-X), CCW
        //   | /  |
        //   0----1
        val sideWallVerts = floatArrayOf(
            wallX, floorY,  h,   // 0 bottom-front
            wallX, floorY, -h,   // 1 bottom-back   (note: reversed Z so CCW from +X)
            wallX,  h,     -h,   // 2 top-back
            wallX,  h,      h    // 3 top-front
        )

        studioFloorNode = buildPlaneNode(
            engine, floorVerts, indices,
            studioFloorColor,
            Position(0f, 0f, 0f), Rotation(0f, 0f, 0f), "floor"
        )?.also { sceneView.addChildNode(it) }

        studioBackWallNode = buildPlaneNode(
            engine, backWallVerts, indices,
            studioBackWallColor,
            Position(0f, 0f, 0f), Rotation(0f, 0f, 0f), "backWall"
        )?.also { sceneView.addChildNode(it) }

        studioSideWallNode = buildPlaneNode(
            engine, sideWallVerts, indices,
            studioSideWallColor,
            Position(0f, 0f, 0f), Rotation(0f, 0f, 0f), "sideWall"
        )?.also { sceneView.addChildNode(it) }

        if (studioFloorNode    == null) Log.e(TAG, "buildStudioPlanes: floor node is NULL")
        if (studioBackWallNode == null) Log.e(TAG, "buildStudioPlanes: backWall node is NULL")
        if (studioSideWallNode == null) Log.e(TAG, "buildStudioPlanes: sideWall node is NULL")
        Log.d(TAG, "buildStudioPlanes: done  halfExtent=$h")
    }

    /**
     * Builds a plain coloured quad Node using raw Filament APIs.
     * Returns the built Node on success, null on failure.
     * Also caches the MaterialInstance in [floorMatInstance] / [backWallMatInstance] /
     * [sideWallMatInstance] so it can be updated live without a geometry rebuild.
     */
    private fun buildPlaneNode(
        engine: Engine,
        vertices: FloatArray,
        indices: ShortArray,
        @ColorInt color: Int,
        pos: Position,
        rot: Rotation,
        label: String = "plane"
    ): Node? {
        val r = color.red   / 255f
        val g = color.green / 255f
        val b = color.blue  / 255f
        Log.d(TAG, "buildPlaneNode[$label]: vertexCount=${vertices.size / 3}  indexCount=${indices.size}")
        return try {
            val vertCount = vertices.size / 3

            val vBuf = VertexBuffer.Builder()
                .vertexCount(vertCount)
                .bufferCount(1)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
                .build(engine)

            val vData = ByteBuffer.allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .apply { vertices.forEach { putFloat(it) }; flip() }
            vBuf.setBufferAt(engine, 0, vData)

            val iBuf = IndexBuffer.Builder()
                .indexCount(indices.size)
                .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                .build(engine)
            val iData = ByteBuffer.allocateDirect(indices.size * 2)
                .order(ByteOrder.nativeOrder())
                .apply { indices.forEach { putShort(it) }; flip() }
            iBuf.setBuffer(engine, iData)

            val mat = sceneView.materialLoader.createColorInstance(
                Color.argb(255, (r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
            )

            // Cache the MaterialInstance for live color updates
            when (label) {
                "floor"    -> floorMatInstance    = mat
                "backWall" -> backWallMatInstance = mat
                "sideWall" -> sideWallMatInstance = mat
            }

            studioVBufs.add(vBuf)
            studioIBufs.add(iBuf)
            studioMats.add(mat)

            val node = Node(sceneView.engine)
            Log.d(TAG, "buildPlaneNode[$label]: Node entity=${node.entity}")

            if (node.entity == 0) {
                Log.e(TAG, "buildPlaneNode[$label]: node.entity is 0 — cannot build renderable")
                return null
            }

            // AABB
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (i in vertices.indices step 3) {
                if (vertices[i]     < minX) minX = vertices[i]
                if (vertices[i]     > maxX) maxX = vertices[i]
                if (vertices[i + 1] < minY) minY = vertices[i + 1]
                if (vertices[i + 1] > maxY) maxY = vertices[i + 1]
                if (vertices[i + 2] < minZ) minZ = vertices[i + 2]
                if (vertices[i + 2] > maxZ) maxZ = vertices[i + 2]
            }
            if (minX == maxX) { minX -= 0.001f; maxX += 0.001f }
            if (minY == maxY) { minY -= 0.001f; maxY += 0.001f }
            if (minZ == maxZ) { minZ -= 0.001f; maxZ += 0.001f }

            RenderableManager.Builder(1)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vBuf, iBuf)
                .material(0, mat)
                .boundingBox(Box(
                    (minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f,
                    (maxX - minX) / 2f, (maxY - minY) / 2f, (maxZ - minZ) / 2f
                ))
                .culling(false)
                .receiveShadows(true)
                .castShadows(false)
                .build(engine, node.entity)

            node.position = pos
            node.rotation = rot
            Log.d(TAG, "buildPlaneNode[$label]: success")
            node

        } catch (e: Exception) {
            Log.e(TAG, "buildPlaneNode[$label]: EXCEPTION — ${e::class.simpleName}: ${e.message}", e)
            null
        }
    }

    private fun removeStudioPlanes() {
        Log.d(TAG, "removeStudioPlanes: floor=${studioFloorNode != null}  " +
                "backWall=${studioBackWallNode != null}  sideWall=${studioSideWallNode != null}")

        studioFloorNode?.let    { sceneView.removeChildNode(it); it.destroy() }
        studioBackWallNode?.let { sceneView.removeChildNode(it); it.destroy() }
        studioSideWallNode?.let { sceneView.removeChildNode(it); it.destroy() }
        studioFloorNode    = null
        studioBackWallNode = null
        studioSideWallNode = null

        floorMatInstance    = null
        backWallMatInstance = null
        sideWallMatInstance = null

        try {
            val engine = sceneView.engine
            studioVBufs.forEach { engine.destroyVertexBuffer(it) }
            studioIBufs.forEach { engine.destroyIndexBuffer(it) }
            studioMats.forEach  { engine.destroyMaterialInstance(it) }
            Log.d(TAG, "removeStudioPlanes: GPU resources destroyed OK")
        } catch (e: Exception) {
            Log.e(TAG, "removeStudioPlanes: error destroying GPU resources", e)
        }
        studioVBufs.clear()
        studioIBufs.clear()
        studioMats.clear()
    }

    // -------------------------------------------------------------------------
    // Model loading
    // -------------------------------------------------------------------------

    private suspend fun loadModel(path: String, isAsset: Boolean = true) {
        Log.d(TAG, "loadModel: path=$path  isAsset=$isAsset")
        try {
            val instance = if (isAsset) {
                sceneView.modelLoader.createModelInstance(path)
            } else {
                val file = File(path)
                if (!file.exists()) {
                    Log.e(TAG, "loadModel: file not found at $path")
                    return
                }
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                sceneView.modelLoader.createModelInstance(buffer = ByteBuffer.wrap(bytes))
            }

            if (instance == null) {
                Log.e(TAG, "loadModel: createModelInstance returned null for path=$path")
                return
            }

            modelNode = ModelNode(instance, true, 1.0f, Position(0f, 0f, 0f)).apply {
                isScaleEditable    = false
                isRotationEditable = false
            }
            sceneView.addChildNode(modelNode!!)

            // Compute model bounding-sphere radius from its AABB, then rebuild
            // studio planes (if active) so they scale to the actual model size.
            computeModelRadius()
            if (currentBgMode is BgMode.Studio) {
                removeStudioPlanes()
                buildStudioPlanes()
            }

            updateCamera()
        } catch (e: Exception) {
            Log.e(TAG, "loadModel: EXCEPTION — ${e::class.simpleName}: ${e.message}", e)
        }
    }

    /**
     * Estimates the bounding-sphere radius of the loaded model by reading the
     * AABB half-extents from the ModelNode.  Falls back to 1 m if unavailable.
     */
    private fun computeModelRadius() {
        try {
            val node = modelNode ?: return
            // SceneView exposes the Filament AABB via node.boundingBox (a dev.romainguy.kotlin.math.Box3).
            // The half-extents give us the largest dimension.
            val box = node.boundingBox
            // box.halfExtent is a Float3; use the max component as the radius
            val hx = box.halfExtent[0]
            val hy = box.halfExtent[1]
            val hz = box.halfExtent[2]
            val radius = sqrt(hx*hx + hy*hy + hz*hz).coerceAtLeast(0.1f)
            modelRadius = radius
            Log.d(TAG, "computeModelRadius: halfExtent=($hx,$hy,$hz)  radius=$radius")
        } catch (e: Exception) {
            Log.w(TAG, "computeModelRadius: could not read AABB, using default — ${e.message}")
            modelRadius = 1f
        }
    }

    // -------------------------------------------------------------------------
    // Camera
    // -------------------------------------------------------------------------

    private fun updateCamera() {
        val elev = Math.toRadians(camElevDeg).toFloat()
        val azim = Math.toRadians(camAzimDeg).toFloat()
        sceneView.cameraNode.position = Position(
            camDist * cos(elev) * sin(azim),
            camDist * sin(elev),
            camDist * cos(elev) * cos(azim)
        )
        sceneView.cameraNode.lookAt(Position(0f, 0f, 0f))
    }

    // -------------------------------------------------------------------------
    // Touch
    // -------------------------------------------------------------------------

    private fun setupTouchListener() {
        sceneView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x; lastTouchY = event.y; isTwoFinger = false
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                    isTwoFinger = true
                    initialPinchDist = fingerSpacing(event)
                    initialCamDist   = camDist
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (isTwoFinger && event.pointerCount >= 2) {
                        val dist = fingerSpacing(event)
                        if (initialPinchDist > 0f) {
                            camDist = (initialCamDist * initialPinchDist / dist)
                                .coerceIn(CAM_DIST_MIN, CAM_DIST_MAX)
                            updateCamera()
                        }
                    } else {
                        camAzimDeg -= (event.x - lastTouchX) * 0.3
                        camElevDeg  = (camElevDeg + (event.y - lastTouchY) * 0.3)
                            .coerceIn(CAM_ELEV_MIN, CAM_ELEV_MAX)
                        lastTouchX = event.x; lastTouchY = event.y
                        updateCamera()
                    }
                }
            }
            true
        }
    }

    private fun fingerSpacing(e: android.view.MotionEvent): Float {
        val x = e.getX(0) - e.getX(1)
        val y = e.getY(0) - e.getY(1)
        return sqrt(x * x + y * y)
    }

    // -------------------------------------------------------------------------
    // Crop / screenshot
    // -------------------------------------------------------------------------

    private fun showCropOverlay() {
        cropOverlay.visibility     = View.VISIBLE
        cropConfirmBar.visibility  = View.VISIBLE
        normalButtonBar.visibility = View.GONE
        btnBackground.visibility   = View.GONE
        sceneView.setOnTouchListener(null)
    }

    private fun hideCropOverlay() {
        cropOverlay.visibility     = View.GONE
        cropConfirmBar.visibility  = View.GONE
        normalButtonBar.visibility = View.VISIBLE
        btnBackground.visibility   = View.VISIBLE
        setupTouchListener()
    }

    private var pendingCropRect: RectF? = null

    private fun captureAndSaveThumbnail() {
        val w = sceneView.width; val h = sceneView.height
        if (w == 0 || h == 0) return
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sceneView, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                val cropped = pendingCropRect?.let { cropBitmap(bitmap, it, w, h) } ?: bitmap
                saveThumbnailToDisk(scaledForCard(cropped))
            } else {
                Log.e(TAG, "captureAndSaveThumbnail: PixelCopy FAILED result=$result")
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun cropBitmap(b: Bitmap, r: RectF, vw: Int, vh: Int): Bitmap {
        val sx = b.width.toFloat()  / vw
        val sy = b.height.toFloat() / vh
        return Bitmap.createBitmap(
            b,
            (r.left   * sx).toInt().coerceIn(0, b.width),
            (r.top    * sy).toInt().coerceIn(0, b.height),
            ((r.right  - r.left) * sx).toInt().coerceAtLeast(1),
            ((r.bottom - r.top)  * sy).toInt().coerceAtLeast(1)
        )
    }

    private fun scaledForCard(src: Bitmap): Bitmap {
        val side   = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(square, THUMB_PX, THUMB_PX, true)
    }

    private fun saveThumbnailToDisk(bitmap: Bitmap) {
        try {
            val file = File(filesDir, "thumbnails/$profileKey.jpg").apply { parentFile?.mkdirs() }
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, it) }
            Log.d(TAG, "saveThumbnailToDisk: saved to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "saveThumbnailToDisk: FAILED", e)
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
        removeStudioPlanes()
        sceneView.destroy()
    }
}