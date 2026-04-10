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
import android.widget.RadioGroup
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.lifecycle.lifecycleScope
import com.example.pracazaliczeniowa.Overlays.CropOverlayView
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class ModelPreviewActivity : AppCompatActivity() {

    // ── Background mode ───────────────────────────────────────────────────

    private sealed class BgMode {
        /** HDR environment skybox fully visible. */
        object Hdr : BgMode()
        /** Plain solid colour void; [color] is an ARGB int. */
        data class SolidColour(@ColorInt val color: Int) : BgMode()
    }

    companion object {
        const val EXTRA_MODEL_PATH  = "extra_model_path"
        const val EXTRA_PROFILE_KEY = "extra_profile_key"

        private const val TAG = "ModelPreviewActivity"

        private const val CAM_DIST_INIT     = 2.5f
        private const val CAM_ELEV_DEG_INIT = 35.0
        private const val CAM_AZIM_DEG_INIT = 45.0

        private const val CAM_DIST_MIN = 0.5f
        private const val CAM_DIST_MAX = 10f
        private const val CAM_ELEV_MIN = -89.0
        private const val CAM_ELEV_MAX =  89.0

        private const val THUMB_PX      = 360
        private const val THUMB_QUALITY = 85

        /** Default void colour — matches the FrameLayout background (#DCDCDC). */
        private val DEFAULT_VOID_COLOR = Color.parseColor("#DCDCDC")

        /** Preset swatches shown in the bottom sheet. */
        private val PRESET_COLORS = listOf(
            Color.parseColor("#DCDCDC"),   // light grey  (default)
            Color.parseColor("#1A1A2E"),   // dark navy
            Color.parseColor("#2D2D2D"),   // charcoal
            Color.parseColor("#FFFFFF"),   // white
            Color.parseColor("#0D3B66"),   // deep blue
            Color.parseColor("#3D5A3E"),   // forest green
        )
    }

    private lateinit var sceneView:       SceneView
    private lateinit var cropOverlay:     CropOverlayView
    private lateinit var normalButtonBar: LinearLayout
    private lateinit var cropConfirmBar:  LinearLayout
    private lateinit var btnBackground:   ImageButton

    private var modelNode: ModelNode? = null
    private lateinit var profileKey: String

    /** Current background state — starts as solid grey void. */
    private var currentBgMode: BgMode = BgMode.SolidColour(DEFAULT_VOID_COLOR)

    private var captureNextFrame = false

    private var camDist    = CAM_DIST_INIT
    private var camElevDeg = CAM_ELEV_DEG_INIT
    private var camAzimDeg = CAM_AZIM_DEG_INIT

    private var lastTouchX       = 0f
    private var lastTouchY       = 0f
    private var initialPinchDist = 0f
    private var initialCamDist   = 0f
    private var isTwoFinger      = false

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_preview)

        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: run {
            Log.e(TAG, "No model path provided"); finish(); return
        }
        profileKey = intent.getStringExtra(EXTRA_PROFILE_KEY) ?: run {
            Log.e(TAG, "No profile key provided"); finish(); return
        }

        sceneView       = findViewById(R.id.previewSceneView)
        cropOverlay     = findViewById(R.id.cropOverlay)
        normalButtonBar = findViewById(R.id.normalButtonBar)
        cropConfirmBar  = findViewById(R.id.cropConfirmBar)
        btnBackground   = findViewById(R.id.btnBackground)

        // Apply initial background (solid grey void) before the first frame
        applyBackground(currentBgMode)

        // Load HDR for lighting only — skybox suppressed until user picks HDR mode
        lifecycleScope.launch {
            try {
                val env = sceneView.environmentLoader.loadHDREnvironment("envs/environment.hdr")
                if (env != null) {
                    sceneView.environment = env
                    // Re-apply current mode — loading the env may have restored its skybox
                    applyBackground(currentBgMode)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not load environment.hdr: ${e.message}")
            }
        }

        // ── Buttons ───────────────────────────────────────────────────────
        findViewById<ImageButton>(R.id.btnPreviewBack).setOnClickListener { finish() }
        btnBackground.setOnClickListener { showBackgroundPicker() }

        findViewById<Button>(R.id.btnOpenInAR).setOnClickListener {
            // TODO: launch ARActivity with modelPath
        }
        findViewById<Button>(R.id.btnTakeScreenshot).setOnClickListener { showCropOverlay() }

        // ── Crop buttons ──────────────────────────────────────────────────
        findViewById<Button>(R.id.btnCropCancel).setOnClickListener { hideCropOverlay() }
        findViewById<Button>(R.id.btnCropConfirm).setOnClickListener {
            pendingCropRect = cropOverlay.getCropRect()
            hideCropOverlay()
            captureNextFrame = true
        }

        // ── Model + frame loop ────────────────────────────────────────────
        lifecycleScope.launch { loadModel(modelPath) }
        sceneView.onFrame = { _ ->
            if (captureNextFrame) {
                captureNextFrame = false
                captureAndSaveThumbnail()
            }
        }

        setupTouchListener()
    }

    // ── Background ────────────────────────────────────────────────────────

    /**
     * Applies [mode] to the SceneView:
     *  - [BgMode.Hdr]         → restores the environment skybox; disables Filament clear
     *  - [BgMode.SolidColour] → nulls the skybox; sets Filament clear colour + root view tint
     */
    private fun applyBackground(mode: BgMode) {
        currentBgMode = mode
        when (mode) {
            is BgMode.Hdr -> {
                // Restore the skybox that came with the loaded environment
                sceneView.environment?.let { env ->
                    // SceneView's IndirectLight / Environment carries a skybox reference
                    sceneView.skybox = env.skybox
                }
                // Let the skybox fill every pixel — no need for Filament to clear
                sceneView.renderer.clearOptions =
                    sceneView.renderer.clearOptions.apply { clear = false }
            }
            is BgMode.SolidColour -> {
                sceneView.skybox = null
                // Paint the Android root so there's no gap between view layers
                window.decorView.setBackgroundColor(mode.color)
                // Filament clear colour must match exactly to avoid flash
                val r = mode.color.red   / 255f
                val g = mode.color.green / 255f
                val b = mode.color.blue  / 255f
                sceneView.renderer.clearOptions =
                    sceneView.renderer.clearOptions.apply {
                        clear = true
                        clearColor[0] = r
                        clearColor[1] = g
                        clearColor[2] = b
                        clearColor[3] = 1f
                    }
            }
        }
    }

    // ── Background picker bottom sheet ────────────────────────────────────

    private fun showBackgroundPicker() {
        val sheet = BottomSheetDialog(this)
        val view  = layoutInflater.inflate(R.layout.dialog_background_color_picker, null)
        sheet.setContentView(view)

        val rgMode         = view.findViewById<RadioGroup>(R.id.rgBackgroundMode)
        val layoutColour   = view.findViewById<View>(R.id.layoutColourPicker)
        val swatchRow      = view.findViewById<LinearLayout>(R.id.swatchRow)
        val vCurrentColour = view.findViewById<View>(R.id.vCurrentColour)
        val btnCustom      = view.findViewById<Button>(R.id.btnCustomColour)

        // Starting colour for the swatch/preview row
        var pickedColor = when (val m = currentBgMode) {
            is BgMode.SolidColour -> m.color
            else                  -> DEFAULT_VOID_COLOR
        }

        // Pre-select the right radio button and hide/show colour section
        when (currentBgMode) {
            is BgMode.Hdr -> {
                view.findViewById<android.widget.RadioButton>(R.id.rbModeHdr).isChecked = true
                layoutColour.visibility = View.GONE
            }
            is BgMode.SolidColour -> {
                view.findViewById<android.widget.RadioButton>(R.id.rbModeVoid).isChecked = true
                layoutColour.visibility = View.VISIBLE
            }
        }
        updateColourPreview(vCurrentColour, pickedColor)

        // ── Mode radio ────────────────────────────────────────────────────
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbModeHdr -> {
                    layoutColour.visibility = View.GONE
                    applyBackground(BgMode.Hdr)
                    sheet.dismiss()
                }
                R.id.rbModeVoid -> {
                    layoutColour.visibility = View.VISIBLE
                    applyBackground(BgMode.SolidColour(pickedColor))
                }
            }
        }

        // ── Preset swatches ───────────────────────────────────────────────
        val dp = resources.displayMetrics.density
        PRESET_COLORS.forEach { presetColor ->
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (36 * dp).toInt(), (36 * dp).toInt()
                ).also { lp -> lp.marginEnd = (8 * dp).toInt() }
                background = GradientDrawable().apply {
                    shape        = GradientDrawable.RECTANGLE
                    cornerRadius = 6 * dp
                    setColor(presetColor)
                    if (presetColor == pickedColor) setStroke((2 * dp).toInt(), Color.WHITE)
                }
                setOnClickListener {
                    pickedColor = presetColor
                    updateColourPreview(vCurrentColour, pickedColor)
                    refreshSwatchRings(swatchRow, pickedColor, dp)
                    applyBackground(BgMode.SolidColour(pickedColor))
                }
            }
            swatchRow.addView(swatch)
        }

        // ── Custom colour ─────────────────────────────────────────────────
        btnCustom.setOnClickListener {
            sheet.dismiss()
            showCustomColourPicker(pickedColor) { chosen ->
                pickedColor = chosen
                applyBackground(BgMode.SolidColour(chosen))
            }
        }

        sheet.show()
    }

    private fun refreshSwatchRings(row: LinearLayout, @ColorInt selected: Int, dp: Float) {
        for (i in 0 until row.childCount) {
            val color = PRESET_COLORS.getOrNull(i) ?: continue
            (row.getChildAt(i).background as? GradientDrawable)?.apply {
                setColor(color)
                if (color == selected) setStroke((2 * dp).toInt(), Color.WHITE)
                else                   setStroke(0, Color.TRANSPARENT)
            }
        }
    }

    private fun updateColourPreview(view: View, @ColorInt color: Int) {
        (view.background as? GradientDrawable)?.setColor(color)
            ?: view.setBackgroundColor(color)
    }

    /**
     * Simple HSV colour picker built from three SeekBars (Hue / Saturation / Value).
     * Swap [buildSimpleHsvPicker] for any third-party ColourPickerView if preferred —
     * the [onPicked] callback contract is unchanged.
     */
    private fun showCustomColourPicker(@ColorInt initial: Int, onPicked: (Int) -> Unit) {
        // Track what the user has built so far inside the dialog
        var liveColor = initial

        val pickerView = buildSimpleHsvPicker(initial) { live ->
            liveColor = live
            applyBackground(BgMode.SolidColour(live))   // live preview
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Custom colour")
            .setView(pickerView)
            .setPositiveButton("OK")     { _, _ -> onPicked(liveColor) }
            .setNegativeButton("Cancel") { _, _ -> applyBackground(BgMode.SolidColour(initial)) }
            .show()
    }

    /**
     * Builds a minimal HSV picker: three SeekBars (Hue 0–360, Sat 0–100, Val 0–100)
     * plus a live colour preview swatch. [onLiveChange] is called on every slider move.
     */
    private fun buildSimpleHsvPicker(
        @ColorInt initial: Int,
        onLiveChange: (Int) -> Unit
    ): View {
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)
        val dp = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }

        fun label(text: String) = android.widget.TextView(this).apply {
            this.text     = text
            textSize      = 12f
            setTextColor(Color.DKGRAY)
            layoutParams  = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (8 * dp).toInt() }
        }

        fun seekBar(max: Int, progress: Int) = android.widget.SeekBar(this).apply {
            this.max      = max
            this.progress = progress
        }

        container.addView(label("Hue (0 – 360)"))
        val hueBar = seekBar(360, hsv[0].toInt()).also { container.addView(it) }

        container.addView(label("Saturation"))
        val satBar = seekBar(100, (hsv[1] * 100).toInt()).also { container.addView(it) }

        container.addView(label("Value / Brightness"))
        val valBar = seekBar(100, (hsv[2] * 100).toInt()).also { container.addView(it) }

        // Live preview swatch
        val preview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (48 * dp).toInt()
            ).also { it.topMargin = (16 * dp).toInt() }
            background = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = 6 * dp
                setColor(initial)
            }
        }
        container.addView(preview)

        fun rebuild() {
            hsv[0] = hueBar.progress.toFloat()
            hsv[1] = satBar.progress / 100f
            hsv[2] = valBar.progress / 100f
            val color = Color.HSVToColor(hsv)
            (preview.background as GradientDrawable).setColor(color)
            onLiveChange(color)
        }

        val listener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) rebuild()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(sb: android.widget.SeekBar)  = Unit
        }
        hueBar.setOnSeekBarChangeListener(listener)
        satBar.setOnSeekBarChangeListener(listener)
        valBar.setOnSeekBarChangeListener(listener)

        return container
    }

    // ── Crop overlay visibility ───────────────────────────────────────────

    private fun showCropOverlay() {
        cropOverlay.visibility     = View.VISIBLE
        cropConfirmBar.visibility  = View.VISIBLE
        normalButtonBar.visibility = View.GONE
        btnBackground.visibility   = View.GONE          // hide during crop
        sceneView.setOnTouchListener(null)
    }

    private fun hideCropOverlay() {
        cropOverlay.visibility     = View.GONE
        cropConfirmBar.visibility  = View.GONE
        normalButtonBar.visibility = View.VISIBLE
        btnBackground.visibility   = View.VISIBLE
        setupTouchListener()
    }

    // ── Model loading ─────────────────────────────────────────────────────

    private suspend fun loadModel(modelPath: String) {
        try {
            val instance = sceneView.modelLoader.createModelInstance(modelPath)
            val node = ModelNode(
                modelInstance = instance,
                scaleToUnits  = 1.0f,
                centerOrigin  = io.github.sceneview.math.Position(0f, 0f, 0f)
            ).apply {
                isScaleEditable    = false
                isRotationEditable = false
            }
            sceneView.addChildNode(node)
            modelNode = node
            updateCamera()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: $modelPath", e)
            runOnUiThread {
                Toast.makeText(this, "Could not load model: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Thumbnail capture ─────────────────────────────────────────────────

    private var pendingCropRect: RectF? = null

    private fun captureAndSaveThumbnail() {
        val w = sceneView.width.takeIf  { it > 0 } ?: return
        val h = sceneView.height.takeIf { it > 0 } ?: return

        val bitmap  = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val handler = Handler(Looper.getMainLooper())

        PixelCopy.request(sceneView, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                val cropped = pendingCropRect
                    ?.let { cropBitmap(bitmap, it, w, h) }
                    ?: bitmap
                pendingCropRect = null
                val thumb = scaledForCard(cropped)
                saveThumbnailToDisk(thumb)
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Capture failed (code $result)", Toast.LENGTH_SHORT).show()
                }
            }
        }, handler)
    }

    private fun cropBitmap(bitmap: Bitmap, viewRect: RectF, viewW: Int, viewH: Int): Bitmap {
        val scaleX = bitmap.width.toFloat()  / viewW
        val scaleY = bitmap.height.toFloat() / viewH
        val left   = (viewRect.left   * scaleX).roundToInt().coerceIn(0, bitmap.width)
        val top    = (viewRect.top    * scaleY).roundToInt().coerceIn(0, bitmap.height)
        val right  = (viewRect.right  * scaleX).roundToInt().coerceIn(0, bitmap.width)
        val bottom = (viewRect.bottom * scaleY).roundToInt().coerceIn(0, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
    }

    private fun scaledForCard(src: Bitmap): Bitmap {
        val side   = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        if (square.width == THUMB_PX) return square
        val scaled = Bitmap.createScaledBitmap(square, THUMB_PX, THUMB_PX, true)
        if (scaled !== square) square.recycle()
        return scaled
    }

    private fun saveThumbnailToDisk(bitmap: Bitmap) {
        try {
            val file = File(filesDir, "thumbnails/$profileKey.jpg")
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
            }
            runOnUiThread { Toast.makeText(this, "Thumbnail saved!", Toast.LENGTH_SHORT).show() }
            Log.d(TAG, "Thumbnail saved: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save thumbnail for $profileKey", e)
            runOnUiThread { Toast.makeText(this, "Failed to save thumbnail", Toast.LENGTH_SHORT).show() }
        }
    }

    // ── Camera ────────────────────────────────────────────────────────────

    private fun updateCamera() {
        val elevRad = Math.toRadians(camElevDeg).toFloat()
        val azimRad = Math.toRadians(camAzimDeg).toFloat()
        sceneView.cameraNode.position = io.github.sceneview.math.Position(
            camDist * cos(elevRad) * sin(azimRad),
            camDist * sin(elevRad),
            camDist * cos(elevRad) * cos(azimRad)
        )
        sceneView.cameraNode.lookAt(io.github.sceneview.math.Position(0f, 0f, 0f))
    }

    // ── Touch ─────────────────────────────────────────────────────────────

    private fun setupTouchListener() {
        sceneView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x; lastTouchY = event.y; isTwoFinger = false
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        isTwoFinger = true; initialPinchDist = fingerSpacing(event); initialCamDist = camDist
                    }
                }
                android.view.MotionEvent.ACTION_POINTER_UP -> {
                    isTwoFinger = false; lastTouchX = event.x; lastTouchY = event.y
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (isTwoFinger && event.pointerCount >= 2) {
                        val dist = fingerSpacing(event)
                        if (initialPinchDist > 0f) {
                            camDist = (initialCamDist * initialPinchDist / dist).coerceIn(CAM_DIST_MIN, CAM_DIST_MAX)
                            updateCamera()
                        }
                    } else {
                        camAzimDeg -= (event.x - lastTouchX) * 0.3
                        camElevDeg  = (camElevDeg + (event.y - lastTouchY) * 0.3).coerceIn(CAM_ELEV_MIN, CAM_ELEV_MAX)
                        lastTouchX  = event.x; lastTouchY = event.y
                        updateCamera()
                    }
                }
            }
            true
        }
    }

    private fun fingerSpacing(event: android.view.MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt(x * x + y * y)
    }

    override fun onDestroy() {
        super.onDestroy()
        sceneView.destroy()
    }
}