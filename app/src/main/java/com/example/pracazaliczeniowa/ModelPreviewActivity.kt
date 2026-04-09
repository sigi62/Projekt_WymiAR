package com.example.pracazaliczeniowa

import android.graphics.Bitmap
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

        // ── Grey void ─────────────────────────────────────────────────────
        sceneView.skybox = null

        // ── Lighting ──────────────────────────────────────────────────────
        lifecycleScope.launch {
            try {
                val env = sceneView.environmentLoader.loadHDREnvironment("envs/environment.hdr")
                if (env != null) {
                    sceneView.environment = env.apply { sceneView.skybox = null }
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
                saveThumbnailToDisk(cropped)
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

    private fun saveThumbnailToDisk(bitmap: Bitmap) {
        try {
            val file = File(filesDir, "thumbnails/$profileKey.png")
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
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
                    lastTouchX  = event.getX(0)
                    lastTouchY  = event.getY(0)
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