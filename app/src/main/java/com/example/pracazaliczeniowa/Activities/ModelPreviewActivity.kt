package com.example.pracazaliczeniowa.Activities

import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.AlertDialog
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.lifecycle.lifecycleScope
import com.example.pracazaliczeniowa.Helpers.HsvColorPicker
import com.example.pracazaliczeniowa.Helpers.RulerSeekBar
import com.example.pracazaliczeniowa.Overlays.CropOverlayView
import com.example.pracazaliczeniowa.R
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
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
        const val EXTRA_SOURCE_FORMAT = "extra_source_format"
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

        private const val STUDIO_SCALE_FACTOR = 3f
        private val DEFAULT_VOID_COLOR = Color.parseColor("#DCDCDC")

        private const val PREFS_NAME            = "model_preview_prefs"
        private const val KEY_BG_MODE           = "bg_mode"
        private const val KEY_VOID_COLOR        = "void_color"

        private const val KEY_STUDIO_VOID       = "studio_void_color"
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
    private lateinit var btnAnimationToggle: ImageButton
    private lateinit var btnAnimationPause: ImageButton
    private lateinit var btnAnimationNext: ImageButton

    private var modelNode: ModelNode? = null
    private lateinit var profileKey: String
    private var currentBgMode: BgMode = BgMode.SolidColour(DEFAULT_VOID_COLOR)

    private lateinit var prefs: SharedPreferences

    private var studioFloorNode:    Node? = null
    private var studioBackWallNode: Node? = null
    private var studioSideWallNode: Node? = null

    @ColorInt
    private var studioVoidColor     = Color.parseColor("#DCDCDC")
    @ColorInt
    private var studioFloorColor    = Color.parseColor("#C8C8C8")
    @ColorInt
    private var studioBackWallColor = Color.parseColor("#E0E0E0")
    @ColorInt
    private var studioSideWallColor = Color.parseColor("#B0B0B0")

    private var modelRadius = 1f
    private var captureNextFrame = false

    private val studioVBufs = mutableListOf<VertexBuffer>()
    private val studioIBufs = mutableListOf<IndexBuffer>()
    private val studioMats  = mutableListOf<MaterialInstance>()

    private var floorMatInstance:    MaterialInstance? = null
    private var backWallMatInstance: MaterialInstance? = null
    private var sideWallMatInstance: MaterialInstance? = null

    private var camDist = CAM_DIST_INIT
    private var camElevDeg = CAM_ELEV_DEG_INIT
    private var camAzimDeg = CAM_AZIM_DEG_INIT

    private var modelRotationY = 0f
    private val ROT_MID = 1800f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var initialPinchDist = 0f
    private var initialCamDist = 0f
    private var isTwoFinger = false

    // ── Model colour override ─────────────────────────────────────────────────
    /** True when the loaded model came from a texture-less format (STL/PLY/3DS)
     *  and received Assimp's grey default PBR material. */
    private var modelNeedsColourPicker = false
    /** Currently applied override colour; null = no override (original GLB mats). */
    @ColorInt private var currentModelColour: Int? = null
    /** MaterialInstances we created for the colour override — destroyed on cleanup. */
    private val modelColourOverrideMats = mutableListOf<MaterialInstance>()
    /** Snapshot of the original MaterialInstances before any override. */
    private var modelOriginalMats: List<MaterialInstance>? = null

    private lateinit var btnModelColour: FrameLayout
    private lateinit var modelColourBgRect: View
    private lateinit var modelColourPatchRect: View

    // ── Animation state ───────────────────────────────────────────────────────
    private var isAnimationPlaying: Boolean = false
    private var isAnimationStarted: Boolean = false
    private var currentAnimationIndex: Int = 0
    private var activeAnimationIndex: Int = 0
    private val elapsedTimes = mutableMapOf<Int, Float>()
    private var animJob: Job? = null
    private var lastTickNanos: Long = 0L

    private fun srgbToLinear(channel: Int): Float {
        val c = channel / 255f
        return if (c <= 0.04045f) c / 12.92f
        else Math.pow((c + 0.055) / 1.055, 2.4).toFloat()
    }

    private fun @receiver:ColorInt Int.linearR() = srgbToLinear(this.red)
    private fun @receiver:ColorInt Int.linearG() = srgbToLinear(this.green)
    private fun @receiver:ColorInt Int.linearB() = srgbToLinear(this.blue)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_preview)

        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: run { finish(); return }
        val modelIsAsset = intent.getBooleanExtra(EXTRA_MODEL_IS_ASSET, true)
        profileKey = intent.getStringExtra(EXTRA_PROFILE_KEY) ?: run { finish(); return }
        // Null for bundled assets, direct GLB imports, and pre-existing models
        val sourceFormat = intent.getStringExtra(EXTRA_SOURCE_FORMAT)

        sceneView           = findViewById(R.id.previewSceneView)
        cropOverlay         = findViewById(R.id.cropOverlay)
        normalButtonBar     = findViewById(R.id.normalButtonBar)
        cropConfirmBar      = findViewById(R.id.cropConfirmBar)
        btnBackground       = findViewById(R.id.btnBackground)
        btnAnimationToggle  = findViewById(R.id.btnAnimationToggle)
        btnAnimationPause   = findViewById(R.id.btnAnimationPause)
        btnAnimationNext    = findViewById(R.id.btnAnimationNext)
        btnModelColour      = findViewById(R.id.btnModelColour)
        modelColourBgRect   = btnModelColour.findViewById(R.id.modelColourBgRect)
        modelColourPatchRect= btnModelColour.findViewById(R.id.modelColourPatchRect)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        studioVoidColor     = prefs.getInt(KEY_STUDIO_VOID,      Color.parseColor("#DCDCDC"))
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
        btnModelColour.setOnClickListener { showModelColourPicker() }
        findViewById<Button>(R.id.btnTakeScreenshot).setOnClickListener { showCropOverlay() }
        findViewById<Button>(R.id.btnCropCancel).setOnClickListener { hideCropOverlay() }
        findViewById<Button>(R.id.btnCropConfirm).setOnClickListener {
            pendingCropRect = cropOverlay.getCropRect()
            hideCropOverlay()
            captureNextFrame = true
        }
        findViewById<Button>(R.id.btnOpenInAR).setOnClickListener {
            val intent = Intent(this, ARActivity::class.java).apply {
                putExtra(LibraryActivity.EXTRA_MODEL_PATH,    modelPath)
                putExtra(LibraryActivity.EXTRA_MODEL_IS_ASSET, modelIsAsset)
            }
            startActivity(intent)
        }

        // ── Play / Stop ───────────────────────────────────────────────────────
        btnAnimationToggle.setOnClickListener {
            if (isAnimationStarted) {
                stopAllPreviewAnimations()
            } else {
                isAnimationStarted = true
                isAnimationPlaying = true
                resumePreviewAnimation(currentAnimationIndex)
            }
            refreshAnimationUI()
        }

        // ── Pause / Resume ────────────────────────────────────────────────────
        btnAnimationPause.setOnClickListener {
            if (isAnimationPlaying) {
                pausePreviewAnimation()
                isAnimationPlaying = false
            } else {
                resumePreviewAnimation(currentAnimationIndex)
                isAnimationPlaying = true
            }
            refreshAnimationUI()
        }

        // ── Next track ────────────────────────────────────────────────────────
        btnAnimationNext.setOnClickListener {
            val count = modelNode?.modelInstance?.animator?.animationCount ?: return@setOnClickListener
            if (count < 2) return@setOnClickListener
            currentAnimationIndex = (currentAnimationIndex + 1) % count
            resumePreviewAnimation(currentAnimationIndex)
        }

        val rotationSlider = findViewById<RulerSeekBar>(R.id.rotationSlider)
        rotationSlider.progress = ROT_MID.toInt()

        rotationSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // 1. Subtract the midpoint (1800) to get -1800 to 1800
                    // 2. Divide by 10f to get -180.0 to 180.0
                    modelRotationY = (progress.toFloat() - ROT_MID) / 10f

                    // Now modelRotationY is a proper degree value (e.g., 15.5)
                    modelNode?.rotation = io.github.sceneview.math.Rotation(0f, modelRotationY, 0f)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        lifecycleScope.launch { loadModel(modelPath, modelIsAsset, sourceFormat) }
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
                    clearColor[0] = mode.color.linearR()
                    clearColor[1] = mode.color.linearG()
                    clearColor[2] = mode.color.linearB()
                    clearColor[3] = 1f
                }
            }
            is BgMode.Studio -> {
                sceneView.skybox = null
                window.decorView.setBackgroundColor(studioVoidColor)
                sceneView.renderer.clearOptions = sceneView.renderer.clearOptions.apply {
                    clear = true
                    clearColor[0] = studioVoidColor.linearR()
                    clearColor[1] = studioVoidColor.linearG()
                    clearColor[2] = studioVoidColor.linearB()
                    clearColor[3] = 1f
                }
                buildStudioPlanes()
            }
        }
    }

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

        val dotFloor    = view.findViewById<View>(R.id.dotFloorColor)
        val dotBackWall = view.findViewById<View>(R.id.dotBackWallColor)
        val dotSideWall = view.findViewById<View>(R.id.dotSideWallColor)
        val dotVoid     = view.findViewById<View>(R.id.dotVoidColor)

        fun syncDots() {
            (dotFloor.background    as? GradientDrawable)?.setColor(studioFloorColor)
            (dotBackWall.background as? GradientDrawable)?.setColor(studioBackWallColor)
            (dotSideWall.background as? GradientDrawable)?.setColor(studioSideWallColor)
            (dotVoid.background     as? GradientDrawable)?.setColor(studioVoidColor)
        }
        syncDots()

        var pickedColor = when (val m = currentBgMode) {
            is BgMode.SolidColour -> m.color
            else -> DEFAULT_VOID_COLOR
        }
        listOf(R.id.rbModeHdr, R.id.rbModeStudio, R.id.rbModeVoid).forEach { id ->
            view.findViewById<RadioButton>(id).buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(
                    getColor(R.color.color_secondary),
                    getColor(R.color.text_secondary)
                )
            )
        }

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

        interactivePicker.onColorChanged = { color ->
            pickedColor = color
            updateColourPreview(vCurrentColour, color)
            refreshSwatchRings(swatchRow, color, resources.displayMetrics.density)
            applyBackground(BgMode.SolidColour(color))
            prefs.edit().putInt(KEY_VOID_COLOR, color).apply()
        }

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

        dotFloor.setOnClickListener {
            showSurfaceColorPicker(getString(R.string.studio_color_picker_floor), studioFloorColor) { newColor ->
                studioFloorColor = newColor
                syncDots()
                applyStudioSurfaceColor(SurfaceTarget.FLOOR, newColor)
                prefs.edit().putInt(KEY_STUDIO_FLOOR, newColor).apply()
            }
        }
        dotBackWall.setOnClickListener {
            showSurfaceColorPicker(getString(R.string.studio_color_picker_back_wall), studioBackWallColor) { newColor ->
                studioBackWallColor = newColor
                syncDots()
                applyStudioSurfaceColor(SurfaceTarget.BACK_WALL, newColor)
                prefs.edit().putInt(KEY_STUDIO_BACK_WALL, newColor).apply()
            }
        }
        dotSideWall.setOnClickListener {
            showSurfaceColorPicker(getString(R.string.studio_color_picker_side_wall), studioSideWallColor) { newColor ->
                studioSideWallColor = newColor
                syncDots()
                applyStudioSurfaceColor(SurfaceTarget.SIDE_WALL, newColor)
                prefs.edit().putInt(KEY_STUDIO_SIDE_WALL, newColor).apply()
            }
        }
        dotVoid.setOnClickListener {
            showSurfaceColorPicker(getString(R.string.studio_color_picker_void), studioVoidColor) { newColor ->
                studioVoidColor = newColor
                syncDots()
                applyBackground(BgMode.Studio)
                prefs.edit().putInt(KEY_STUDIO_VOID, newColor).apply()
            }
        }

        sheet.show()
    }

    private enum class SurfaceTarget { FLOOR, BACK_WALL, SIDE_WALL }

    private fun showSurfaceColorPicker(title: String, @ColorInt initial: Int, onPick: (Int) -> Unit) {
        val inner = Dialog(this)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (32 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(getColor(R.color.screen_background))
                cornerRadii = floatArrayOf(20 * dp, 20 * dp, 20 * dp, 20 * dp, 0f, 0f, 0f, 0f)
            }
        }
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (4 * dp).toInt()).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = (16 * dp).toInt()
            }
            setBackgroundColor(getColor(R.color.background_tint))
        })
        root.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (12 * dp).toInt() }
        })
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
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (16 * dp).toInt() }
            addView(previewDot)
            addView(TextView(this@ModelPreviewActivity).apply {
                text = getString(R.string.studio_color_picker_live_preview)
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
            })
        }
        root.addView(previewRow)
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
        inner.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(android.view.Gravity.BOTTOM)
            attributes = attributes.also { it.windowAnimations = android.R.style.Animation_InputMethod }
        }
    }

    private fun applyStudioSurfaceColor(target: SurfaceTarget, @ColorInt color: Int) {
        if (currentBgMode !is BgMode.Studio) return
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

    private fun studioHalfExtent(): Float = modelRadius * STUDIO_SCALE_FACTOR

    private fun buildStudioPlanes() {
        val engine = sceneView.engine
        val h = studioHalfExtent()

        val floorY = -(modelRadius * 0.55f)
        val wallBaseY = floorY
        val wallTopY = floorY + (h * 2)

        val backWallZ = -h
        val sideWallX = -h

        val indices = shortArrayOf(0, 1, 2, 0, 2, 3)

        val floorVerts = floatArrayOf(
            -h, floorY,  h,
            h, floorY,  h,
            h, floorY, -h,
            -h, floorY, -h
        )

        val backWallVerts = floatArrayOf(
            -h, wallBaseY, backWallZ,
            h, wallBaseY, backWallZ,
            h, wallTopY,  backWallZ,
            -h, wallTopY,  backWallZ
        )

        val sideWallVerts = floatArrayOf(
            sideWallX, wallBaseY,  h,
            sideWallX, wallBaseY,  backWallZ,
            sideWallX, wallTopY,   backWallZ,
            sideWallX, wallTopY,   h
        )

        studioFloorNode = buildPlaneNode(engine, floorVerts, indices, studioFloorColor,
            io.github.sceneview.math.Position(0f, 0f, 0f),
            io.github.sceneview.math.Rotation(0f, 0f, 0f), "floor")?.also { sceneView.addChildNode(it) }

        studioBackWallNode = buildPlaneNode(engine, backWallVerts, indices, studioBackWallColor,
            io.github.sceneview.math.Position(0f, 0f, 0f),
            io.github.sceneview.math.Rotation(0f, 0f, 0f), "backWall")?.also { sceneView.addChildNode(it) }

        studioSideWallNode = buildPlaneNode(engine, sideWallVerts, indices, studioSideWallColor,
            io.github.sceneview.math.Position(0f, 0f, 0f),
            io.github.sceneview.math.Rotation(0f, 0f, 0f), "sideWall")?.also { sceneView.addChildNode(it) }
    }

    private fun buildPlaneNode(engine: Engine, vertices: FloatArray, indices: ShortArray, @ColorInt color: Int, pos: Position, rot: Rotation, label: String): Node? {
        val r = color.red / 255f; val g = color.green / 255f; val b = color.blue / 255f
        return try {
            val vBuf = VertexBuffer.Builder().vertexCount(vertices.size / 3).bufferCount(1).attribute(
                VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12).build(engine)
            val vData = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).apply { vertices.forEach { putFloat(it) }; flip() }
            vBuf.setBufferAt(engine, 0, vData)
            val iBuf = IndexBuffer.Builder().indexCount(indices.size).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
            val iData = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).apply { indices.forEach { putShort(it) }; flip() }
            iBuf.setBuffer(engine, iData)
            val mat = sceneView.materialLoader.createColorInstance(Color.argb(255, (r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt()))
            when (label) { "floor" -> floorMatInstance = mat; "backWall" -> backWallMatInstance = mat; "sideWall" -> sideWallMatInstance = mat }
            studioVBufs.add(vBuf); studioIBufs.add(iBuf); studioMats.add(mat)
            val node = Node(sceneView.engine)
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (i in vertices.indices step 3) {
                if (vertices[i] < minX) minX = vertices[i]; if (vertices[i] > maxX) maxX = vertices[i]
                if (vertices[i + 1] < minY) minY = vertices[i + 1]; if (vertices[i + 1] > maxY) maxY = vertices[i + 1]
                if (vertices[i + 2] < minZ) minZ = vertices[i + 2]; if (vertices[i + 2] > maxZ) maxZ = vertices[i + 2]
            }
            RenderableManager.Builder(1).geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vBuf, iBuf).material(0, mat).boundingBox(
                Box(
                    (minX + maxX) / 2f,
                    (minY + maxY) / 2f,
                    (minZ + maxZ) / 2f,
                    (maxX - minX) / 2f,
                    (maxY - minY) / 2f,
                    (maxZ - minZ) / 2f
                )
            ).culling(false).receiveShadows(true).castShadows(false).build(engine, node.entity)
            node.position = pos; node.rotation = rot; node
        } catch (e: Exception) { null }
    }

    private fun removeStudioPlanes() {
        val engine = try { sceneView.engine } catch (e: Exception) { null }

        // 1. Remove nodes from scene first
        studioFloorNode?.let    { sceneView.removeChildNode(it) }
        studioBackWallNode?.let { sceneView.removeChildNode(it) }
        studioSideWallNode?.let { sceneView.removeChildNode(it) }

        // 2. Destroy the renderable component from each entity BEFORE
        //    destroying the MaterialInstance that it still references
        engine?.let { eng ->
            val rm = eng.renderableManager
            listOfNotNull(studioFloorNode, studioBackWallNode, studioSideWallNode)
                .forEach { node ->
                    val instance = rm.getInstance(node.entity)
                    if (instance != 0) rm.destroy(node.entity)
                }
        }

        // 3. Now it's safe to destroy the nodes themselves
        studioFloorNode?.destroy()
        studioBackWallNode?.destroy()
        studioSideWallNode?.destroy()
        studioFloorNode    = null
        studioBackWallNode = null
        studioSideWallNode = null
        floorMatInstance    = null
        backWallMatInstance = null
        sideWallMatInstance = null

        // 4. Only now destroy GPU resources — renderable no longer holds refs
        engine?.let { eng ->
            studioVBufs.forEach { runCatching { eng.destroyVertexBuffer(it) } }
            studioIBufs.forEach { runCatching { eng.destroyIndexBuffer(it) } }
        }
        studioMats.forEach  { runCatching { sceneView.materialLoader.destroyMaterialInstance(it) } }
        studioVBufs.clear()
        studioIBufs.clear()
        studioMats.clear()
    }

    private suspend fun loadModel(path: String, isAsset: Boolean = true, sourceFormat: String? = null) {
        try {
            val instance = if (isAsset) {
                sceneView.modelLoader.createModelInstance(path)
            } else {
                val bytes = withContext(Dispatchers.IO) { File(path).readBytes() }
                sceneView.modelLoader.createModelInstance(buffer = ByteBuffer.wrap(bytes))
            }

            modelNode = ModelNode(
                instance!!,
                false,
                1.0f,
                io.github.sceneview.math.Position(0f, 0f, 0f)
            ).apply {
                isScaleEditable    = false
                isRotationEditable = false
                rotation           = io.github.sceneview.math.Rotation(0f, modelRotationY, 0f)
                // autoAnimate is not set here — ModelNode defaults vary by SceneView version.
                // We control playback explicitly via resumePreviewAnimation / pausePreviewAnimation.
            }
            sceneView.addChildNode(modelNode!!)

            // ── Detect whether this model needs a colour picker ────────────────
            // We check the path extension — the three formats that never carry
            // real textures and always receive Assimp's grey default PBR material.
            detectAndShowColourButton(path, sourceFormat)

            // ── Show/hide animation buttons based on track count ──────────────
            val animCount = modelNode!!.modelInstance.animator?.animationCount ?: 0
            Log.d(TAG, "Model has $animCount animation track(s)")
            refreshAnimationUI()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
        }
    }

    // ── Model colour helpers ──────────────────────────────────────────────────

    /**
     * Shows the colour button when the model's source format never has textures.
     * The three formats that always receive Assimp's injected grey default PBR
     * material are STL, PLY, and 3DS — everything else carries its own materials.
     */
    private fun detectAndShowColourButton(modelPath: String, sourceFormat: String?) {
        // Use the source format recorded at import time — the modelPath is always
        // .glb at this point so extension-sniffing would never match.
        modelNeedsColourPicker = sourceFormat in setOf("stl", "ply", "3ds")
        btnModelColour.visibility = if (modelNeedsColourPicker) View.VISIBLE else View.GONE
        if (modelNeedsColourPicker) {
            // Seed the swatch with the Assimp default grey so it looks right
            // before the user picks anything
            val defaultGrey = Color.parseColor("#B2B2B2")
            updateModelColourSwatch(defaultGrey)
        }
    }

    /** Repaints both swatch views to reflect [color]. */
    private fun updateModelColourSwatch(@ColorInt color: Int) {
        (modelColourBgRect.background as? GradientDrawable)?.setColor(color)
            ?: modelColourBgRect.setBackgroundColor(color)
        (modelColourPatchRect.background as? GradientDrawable)?.setColor(color)
            ?: modelColourPatchRect.setBackgroundColor(color)
    }

    /**
     * Replaces every primitive's material on the loaded [modelNode] with a flat
     * colour instance. Snapshots the originals on first call so [restoreModelMaterials]
     * can undo it.
     */
    private fun applyModelColour(@ColorInt color: Int) {
        val node = modelNode ?: return
        val rm = sceneView.engine.renderableManager
        val ri = rm.getInstance(node.entity)
        if (ri == 0) return

        if (modelOriginalMats == null) {
            modelOriginalMats = (0 until rm.getPrimitiveCount(ri))
                .map { rm.getMaterialInstanceAt(ri, it) }
        }

        modelColourOverrideMats.forEach {
            runCatching { sceneView.materialLoader.destroyMaterialInstance(it) }
        }
        modelColourOverrideMats.clear()

        for (i in 0 until rm.getPrimitiveCount(ri)) {
            val mat = sceneView.materialLoader.createColorInstance(color)
            rm.setMaterialInstanceAt(ri, i, mat)
            modelColourOverrideMats.add(mat)
        }

        currentModelColour = color
        updateModelColourSwatch(color)
    }

    /** Restores the original GLB materials, removing any colour override. */
    private fun restoreModelMaterials() {
        val node = modelNode ?: return
        val originals = modelOriginalMats ?: return
        val rm = sceneView.engine.renderableManager
        val ri = rm.getInstance(node.entity)
        if (ri == 0) return

        originals.forEachIndexed { i, mat -> rm.setMaterialInstanceAt(ri, i, mat) }
        modelColourOverrideMats.forEach {
            runCatching { sceneView.materialLoader.destroyMaterialInstance(it) }
        }
        modelColourOverrideMats.clear()
        modelOriginalMats = null
        currentModelColour = null
        updateModelColourSwatch(Color.parseColor("#B2B2B2"))
    }

    /**
     * Shows a bottom-sheet colour picker for the model's surface colour.
     * Reuses the same [showSurfaceColorPicker] infrastructure already in place
     * for studio-wall colours, with a "Restore original" option added.
     */
    private fun showModelColourPicker() {
        val initial = currentModelColour ?: Color.parseColor("#B2B2B2")
        val inner = android.app.Dialog(this)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (32 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(getColor(R.color.screen_background))
                cornerRadii = floatArrayOf(20 * dp, 20 * dp, 20 * dp, 20 * dp, 0f, 0f, 0f, 0f)
            }
        }
        // Drag handle
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (4 * dp).toInt()).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = (16 * dp).toInt()
            }
            setBackgroundColor(getColor(R.color.background_tint))
        })
        // Title
        root.addView(TextView(this).apply {
            text = getString(R.string.model_colour_picker_title)
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (12 * dp).toInt() }
        })
        // Live preview dot + label
        val previewDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).also {
                it.marginEnd = (12 * dp).toInt()
            }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(initial) }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (16 * dp).toInt() }
            addView(previewDot)
            addView(TextView(this@ModelPreviewActivity).apply {
                text = getString(R.string.studio_color_picker_live_preview)
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
            })
        })

        // Colour picker wheel
        val picker = HsvColorPicker(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (200 * dp).toInt()
            ).also { it.bottomMargin = (8 * dp).toInt() }
            setColor(initial)
        }
        root.addView(picker)

        // ── FIX: Ensure we use the correct method listener definition ──
        // If HsvColorPicker uses onColorChanged function property:
        picker.onColorChanged = { color ->
            (previewDot.background as? GradientDrawable)?.setColor(color)
            applyModelColour(color) // Uses the exact method in your file
        }

        // NOTE: If the wheel still doesn't update, your HsvColorPicker view might rely on
        // an explicit listener pattern instead. If 'onColorChanged' doesn't fire, replace the block above with:
        /*
        picker.setOnColorChangedListener { color ->
            (previewDot.background as? GradientDrawable)?.setColor(color)
            applyModelColour(color)
        }
        */

        // Restore button
        root.addView(Button(this).apply {
            text = getString(R.string.model_colour_restore)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (8 * dp).toInt() }
            setOnClickListener {
                restoreModelMaterials() // Uses the exact method in your file
                inner.dismiss()
            }
        })
        inner.setContentView(root)
        inner.show()
        inner.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(android.view.Gravity.BOTTOM)
            attributes = attributes.also { it.windowAnimations = android.R.style.Animation_InputMethod }
        }
    }


    private fun updateCamera() {
        val elev = Math.toRadians(camElevDeg).toFloat(); val azim = Math.toRadians(camAzimDeg).toFloat()
        sceneView.cameraNode.position = io.github.sceneview.math.Position(
            camDist * cos(elev) * sin(azim),
            camDist * sin(elev),
            camDist * cos(elev) * cos(azim)
        )
        sceneView.cameraNode.lookAt(io.github.sceneview.math.Position(0f, 0f, 0f))
    }

    private fun resumePreviewAnimation(index: Int) {
        val node = modelNode ?: return
        val animator = node.modelInstance.animator ?: return
        val duration = animator.getAnimationDuration(index)
        if (duration <= 0f) return

        animJob?.cancel()
        activeAnimationIndex = index
        lastTickNanos = System.nanoTime()

        animJob = lifecycleScope.launch {
            while (isActive) {
                val now = System.nanoTime()
                val delta = (now - lastTickNanos) / 1_000_000_000f
                lastTickNanos = now

                val elapsed = (elapsedTimes[index] ?: 0f) + delta
                elapsedTimes[index] = elapsed % duration

                animator.applyAnimation(index, elapsedTimes[index]!!)
                animator.updateBoneMatrices()

                delay(16L)
            }
        }
    }

    private fun pausePreviewAnimation() {
        animJob?.cancel()
        animJob = null
        // pose stays frozen at the last applyAnimation call
    }

    private fun stopAllPreviewAnimations() {
        animJob?.cancel()
        animJob = null
        elapsedTimes.clear()
        activeAnimationIndex = 0
        currentAnimationIndex = 0
        isAnimationStarted = false
        isAnimationPlaying = false
        val animator = modelNode?.modelInstance?.animator ?: return
        if (animator.animationCount > 0) {
            animator.applyAnimation(0, 0f)
            animator.updateBoneMatrices()
        }
    }

    private fun refreshAnimationUI() {
        val animCount = modelNode?.modelInstance?.animator?.animationCount ?: 0
        val hasAnim   = animCount > 0
        val multiAnim = animCount > 1

        btnAnimationToggle.visibility = if (hasAnim) View.VISIBLE else View.GONE
        btnAnimationToggle.alpha      = if (isAnimationStarted) 1.0f else 0.5f

        btnAnimationPause.visibility  = if (hasAnim && isAnimationStarted) View.VISIBLE else View.GONE
        btnAnimationPause.alpha       = if (isAnimationPlaying) 1.0f else 0.5f

        btnAnimationNext.visibility   = if (hasAnim && isAnimationStarted && multiAnim) View.VISIBLE else View.GONE
    }
    private fun setupTouchListener() {
        sceneView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastTouchX = event.x; lastTouchY = event.y; isTwoFinger = false }
                MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) { isTwoFinger = true; initialPinchDist = fingerSpacing(event); initialCamDist = camDist }
                MotionEvent.ACTION_MOVE -> {
                    if (isTwoFinger && event.pointerCount >= 2) {
                        val dist = fingerSpacing(event)
                        if (initialPinchDist > 0f) { camDist = (initialCamDist * initialPinchDist / dist).coerceIn(CAM_DIST_MIN, CAM_DIST_MAX); updateCamera() }
                    } else {
                        camAzimDeg -= (event.x - lastTouchX) * 0.3
                        camElevDeg = (camElevDeg + (event.y - lastTouchY) * 0.3).coerceIn(CAM_ELEV_MIN, CAM_ELEV_MAX)
                        lastTouchX = event.x; lastTouchY = event.y; updateCamera()
                    }
                }
            }
            true
        }
    }

    private fun fingerSpacing(e: MotionEvent): Float {
        val x = e.getX(0) - e.getX(1); val y = e.getY(0) - e.getY(1); return sqrt(x * x + y * y)
    }

    private fun showCropOverlay() {
        cropOverlay.visibility = View.VISIBLE
        cropConfirmBar.visibility = View.VISIBLE
        normalButtonBar.visibility = View.GONE
        btnBackground.visibility = View.GONE
        btnAnimationToggle.visibility = View.GONE
        btnAnimationPause.visibility = View.GONE
        btnAnimationNext.visibility = View.GONE
        sceneView.setOnTouchListener(null)
    }

    private fun hideCropOverlay() {
        cropOverlay.visibility = View.GONE
        cropConfirmBar.visibility = View.GONE
        normalButtonBar.visibility = View.VISIBLE
        btnBackground.visibility = View.VISIBLE
        refreshAnimationUI()
        setupTouchListener()
    }

    private var pendingCropRect: RectF? = null
    private fun captureAndSaveThumbnail() {
        val w = sceneView.width; val h = sceneView.height; if (w == 0 || h == 0) return
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sceneView, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) { val cropped = pendingCropRect?.let { cropBitmap(bitmap, it, w, h) } ?: bitmap; saveThumbnailToDisk(scaledForCard(cropped)) }
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
        } catch (e: Exception) {}
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onStop() {
        if (currentBgMode is BgMode.Studio) {
            removeStudioPlanes()
        }
        super.onStop()
    }

    override fun onDestroy() {
        try {
            // Stop animation before touching Filament resources
            stopAllPreviewAnimations()
            // Cancel frame callback so no screenshot fires after destroy
            sceneView.onFrame = null

            modelNode?.let {
                sceneView.removeChildNode(it)
                // Destroy colour-override MaterialInstances before the node goes away
                modelColourOverrideMats.forEach { mat ->
                    runCatching { sceneView.materialLoader.destroyMaterialInstance(mat) }
                }
                modelColourOverrideMats.clear()
                it.destroy()
            }
            sceneView.engine.flushAndWait()
            sceneView.destroy()

        } catch (e: Exception) {
            Log.e(TAG, "onDestroy cleanup error", e)  // don't silently swallow this
        } finally {
            super.onDestroy()  // ✅ called exactly once, at the very end
        }
    }
}