package com.example.pracazaliczeniowa

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.pracazaliczeniowa.Overlays.CropOverlayView
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

        /**
         * The thumbnail is displayed in item_model_card.xml as a 120 dp tall
         * ImageView with scaleType="centerCrop". We save at exactly this logical
         * size so the image is never upscaled and disk usage stays small.
         *
         * Width is set equal to height so it's square — the card will
         * centerCrop it to fill whatever width the grid column provides.
         */
        private const val THUMB_PX     = 360   // ~120 dp × 3 (xxhdpi)
        private const val THUMB_QUALITY = 85    // JPEG quality — good balance of size/sharpness
    }

    private lateinit var sceneView:       SceneView
    private lateinit var cropOverlay: CropOverlayView
    private lateinit var normalButtonBar: LinearLayout
    private lateinit var cropConfirmBar:  LinearLayout

    private var modelNode: ModelNode? = null
    private lateinit var profileKey: String

    /** Set to true inside onFrame so PixelCopy runs on a live surface. */
    private var captureNextFrame = false

    private var camDist    = CAM_DIST_INIT
    private var camElevDeg = CAM_ELEV_DEG_INIT
    private var camAzimDeg = CAM_AZIM_DEG_INIT

    private var lastTouchX       = 0f
    private var lastTouchY       = 0f
    private var initialPinchDist = 0f
    private var initialCamDist   = 0f
    private var isTwoFinger      = false

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

        // ── Grey void — kill the skybox immediately so there is never a flash
        //    of the default street HDR while the environment file loads.
        sceneView.skybox = null

        // Tell the Filament renderer to clear to our grey instead of black.
        // This is what actually stops the black-flash: the clear colour is what
        // you see between frames and before the first model frame is rendered.
        sceneView.renderer.clearOptions = sceneView.renderer.clearOptions.apply {
            clear = true
            // #DCDCDC → sRGB floats
            clearColor[0] = 0.863f  // R
            clearColor[1] = 0.863f  // G
            clearColor[2] = 0.863f  // B
            clearColor[3] = 1.0f    // A
        }

        // ── Lighting — load IBL but explicitly suppress its skybox so the
        //    background stays our plain grey FrameLayout colour (#DCDCDC). ─
        lifecycleScope.launch {
            try {
                val env = sceneView.environmentLoader.loadHDREnvironment("envs/environment.hdr")
                if (env != null) {
                    sceneView.environment = env
                    // Re-assert null here — loadHDREnvironment may set the skybox
                    // as a side-effect; clearing it gives us the grey void look.
                    sceneView.skybox = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not load environment.hdr: ${e.message}")
            }
        }

        // ── Normal buttons ────────────────────────────────────────────────
        findViewById<ImageButton>(R.id.btnPreviewBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnOpenInAR).setOnClickListener {
            // TODO: launch ARActivity with modelPath
        }

        findViewById<Button>(R.id.btnTakeScreenshot).setOnClickListener {
            showCropOverlay()
        }

        // ── Crop overlay buttons ──────────────────────────────────────────
        findViewById<Button>(R.id.btnCropCancel).setOnClickListener {
            hideCropOverlay()
        }

        findViewById<Button>(R.id.btnCropConfirm).setOnClickListener {
            // Grab the crop rect now — before the overlay is hidden —
            // then hide the UI and trigger a PixelCopy on the next frame.
            pendingCropRect = cropOverlay.getCropRect()
            hideCropOverlay()
            captureNextFrame = true
        }

        // ── Model loading ─────────────────────────────────────────────────
        lifecycleScope.launch { loadModel(modelPath) }

        // ── Frame callback — PixelCopy only on a live surface ─────────────
        sceneView.onFrame = { _ ->
            if (captureNextFrame) {
                captureNextFrame = false
                captureAndSaveThumbnail()
            }
        }

        setupTouchListener()
    }

    // ── Crop overlay visibility ───────────────────────────────────────────

    private fun showCropOverlay() {
        cropOverlay.visibility     = View.VISIBLE
        cropConfirmBar.visibility  = View.VISIBLE
        normalButtonBar.visibility = View.GONE
        // Disable orbit/zoom while the crop UI is up
        sceneView.setOnTouchListener(null)
    }

    private fun hideCropOverlay() {
        cropOverlay.visibility     = View.GONE
        cropConfirmBar.visibility  = View.GONE
        normalButtonBar.visibility = View.VISIBLE
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

    /**
     * Stored when the user taps "Save Thumbnail" in the crop UI.
     * In view pixels — converted to bitmap pixels inside [cropBitmap].
     */
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
                    ?: bitmap          // fallback: save full frame
                pendingCropRect = null
                // Scale down to card-slot size then save as JPEG
                val thumb = scaledForCard(cropped)
                saveThumbnailToDisk(thumb)
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Capture failed (code $result)", Toast.LENGTH_SHORT).show()
                }
            }
        }, handler)
    }

    /**
     * Converts the crop rect from view-pixel space to bitmap-pixel space
     * (they may differ on high-density screens) then crops the bitmap.
     */
    private fun cropBitmap(bitmap: Bitmap, viewRect: RectF, viewW: Int, viewH: Int): Bitmap {
        val scaleX = bitmap.width.toFloat()  / viewW
        val scaleY = bitmap.height.toFloat() / viewH

        val left   = (viewRect.left   * scaleX).roundToInt().coerceIn(0, bitmap.width)
        val top    = (viewRect.top    * scaleY).roundToInt().coerceIn(0, bitmap.height)
        val right  = (viewRect.right  * scaleX).roundToInt().coerceIn(0, bitmap.width)
        val bottom = (viewRect.bottom * scaleY).roundToInt().coerceIn(0, bitmap.height)

        val cropW = (right  - left).coerceAtLeast(1)
        val cropH = (bottom - top ).coerceAtLeast(1)

        return Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
    }

    /**
     * Scales [src] to a [THUMB_PX] × [THUMB_PX] square so it fits the card's
     * 120 dp ImageView exactly at xxhdpi without upscaling.
     *
     * The bitmap is first centre-cropped to a square (matching the card's
     * centerCrop scaleType) then resized — this way what the user sees in the
     * crop overlay is exactly what ends up in the card.
     */
    private fun scaledForCard(src: Bitmap): Bitmap {
        // 1. Centre-crop to square
        val side   = minOf(src.width, src.height)
        val startX = (src.width  - side) / 2
        val startY = (src.height - side) / 2
        val square = Bitmap.createBitmap(src, startX, startY, side, side)

        // 2. Scale to target thumbnail size
        if (square.width == THUMB_PX && square.height == THUMB_PX) return square
        val scaled = Bitmap.createScaledBitmap(square, THUMB_PX, THUMB_PX, true)
        if (scaled !== square) square.recycle()
        return scaled
    }

    /**
     * Saves [bitmap] as a JPEG thumbnail for the current profile.
     * JPEG at quality [THUMB_QUALITY] keeps the file small (~20–40 KB)
     * while preserving enough detail for the 120 dp card slot.
     */
    private fun saveThumbnailToDisk(bitmap: Bitmap) {
        try {
            val file = File(filesDir, "thumbnails/$profileKey.jpg")
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
            }
            runOnUiThread {
                Toast.makeText(this, "Thumbnail saved!", Toast.LENGTH_SHORT).show()
            }
            Log.d(TAG, "Thumbnail saved: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save thumbnail for $profileKey", e)
            runOnUiThread {
                Toast.makeText(this, "Failed to save thumbnail", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Camera ────────────────────────────────────────────────────────────

    private fun updateCamera() {
        val elevRad = Math.toRadians(camElevDeg).toFloat()
        val azimRad = Math.toRadians(camAzimDeg).toFloat()
        val x = camDist * cos(elevRad) * sin(azimRad)
        val y = camDist * sin(elevRad)
        val z = camDist * cos(elevRad) * cos(azimRad)

        sceneView.cameraNode.position =
            io.github.sceneview.math.Position(x, y, z)
        sceneView.cameraNode.lookAt(
            io.github.sceneview.math.Position(0f, 0f, 0f)
        )
    }

    // ── Touch — 1 finger orbit, 2 finger zoom ─────────────────────────────

    private fun setupTouchListener() {
        sceneView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastTouchX  = event.x
                    lastTouchY  = event.y
                    isTwoFinger = false
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        isTwoFinger      = true
                        initialPinchDist = fingerSpacing(event)
                        initialCamDist   = camDist
                    }
                }
                android.view.MotionEvent.ACTION_POINTER_UP -> {
                    isTwoFinger = false
                    lastTouchX  = event.x
                    lastTouchY  = event.y
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
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        camAzimDeg -= dx * 0.3
                        camElevDeg  = (camElevDeg + dy * 0.3)
                            .coerceIn(CAM_ELEV_MIN, CAM_ELEV_MAX)
                        lastTouchX  = event.x
                        lastTouchY  = event.y
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