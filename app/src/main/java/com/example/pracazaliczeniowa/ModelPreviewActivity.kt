package com.example.pracazaliczeniowa

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Full-screen 3D model preview — no AR, grey void background.
 *
 * Layout XML must declare R.id.previewSceneView as io.github.sceneview.SceneView.
 *
 * One-finger drag  → orbit camera (azimuth + elevation)
 * Two-finger pinch → zoom (camera distance)
 * "Set Thumbnail"  → captures the current frame via PixelCopy and saves it to
 *                    filesDir/thumbnails/<profileKey>.png, which is the same
 *                    path ModelLibraryAdapter reads from.
 *
 * Extras:
 *  - EXTRA_MODEL_PATH  (String) – asset path, e.g. "models/cat.glb"
 *  - EXTRA_PROFILE_KEY (String) – thumbnail filename key, e.g. "cat"
 */
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

    private lateinit var sceneView: SceneView
    private var modelNode: ModelNode? = null
    private lateinit var profileKey: String

    /**
     * Flipped to true by the "Set Thumbnail" button.
     * Consumed inside the onFrame callback so PixelCopy always sees a
     * fully-rendered, non-black surface.
     */
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
            Log.e(TAG, "No model path provided")
            finish()
            return
        }
        profileKey = intent.getStringExtra(EXTRA_PROFILE_KEY) ?: run {
            Log.e(TAG, "No profile key provided")
            finish()
            return
        }

        sceneView = findViewById(R.id.previewSceneView)

        // ── Grey void ─────────────────────────────────────────────────────
        sceneView.skybox = null

        // ── Lighting ──────────────────────────────────────────────────────
        lifecycleScope.launch {
            try {
                val env = sceneView.environmentLoader.loadHDREnvironment("envs/environment.hdr")
                if (env != null) {
                    sceneView.environment = env.apply { sceneView.skybox = null }
                    Log.d(TAG, "HDR environment loaded")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not load environment.hdr: ${e.message}")
            }
        }

        // ── Buttons ───────────────────────────────────────────────────────
        findViewById<ImageButton>(R.id.btnPreviewBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnOpenInAR).setOnClickListener {
            // TODO: launch ARActivity with modelPath
        }

        findViewById<Button>(R.id.btnTakeScreenshot).setOnClickListener {
            captureNextFrame = true
            Toast.makeText(this, "Capturing…", Toast.LENGTH_SHORT).show()
        }

        // ── Load model ────────────────────────────────────────────────────
        lifecycleScope.launch { loadModel(modelPath) }

        // ── Thumbnail capture — fires after Filament renders each frame ───
        // We wait for the flag rather than capturing immediately so the
        // bitmap is never black (surface is guaranteed live inside onFrame).
        sceneView.onFrame = { _ ->
            if (captureNextFrame) {
                captureNextFrame = false
                captureAndSaveThumbnail()
            }
        }

        setupTouchListener()
    }

    // -------------------------------------------------------------------------
    // Model loading
    // -------------------------------------------------------------------------
    private suspend fun loadModel(modelPath: String) {
        try {
            Log.d(TAG, "Loading model: $modelPath")
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
            Log.d(TAG, "Model loaded successfully")
            updateCamera()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: $modelPath", e)
            runOnUiThread {
                Toast.makeText(this, "Could not load model: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Thumbnail capture
    // Called from inside onFrame so the SurfaceView surface is guaranteed live.
    // -------------------------------------------------------------------------
    private fun captureAndSaveThumbnail() {
        val w = sceneView.width.takeIf  { it > 0 } ?: return
        val h = sceneView.height.takeIf { it > 0 } ?: return

        val bitmap  = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val handler = Handler(Looper.getMainLooper())

        PixelCopy.request(sceneView, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                saveThumbnailToDisk(bitmap)
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Capture failed (code $result)", Toast.LENGTH_SHORT).show()
                }
            }
        }, handler)
    }

    /**
     * Crops [bitmap] to a centered square so the thumbnail always shows
     * the middle of the screen rather than the bottom edge of the model.
     */
    private fun cropCentered(bitmap: Bitmap): Bitmap {
        val side   = minOf(bitmap.width, bitmap.height)
        val x      = (bitmap.width - side) / 2
        // Shift the crop window upward by 15% of the screen height so the
        // model head is included. Increase VERTICAL_OFFSET_FACTOR to move
        // further up, decrease to move back toward centre.
        val VERTICAL_OFFSET_FACTOR = 0.1f
        val y      = ((bitmap.height - side) / 2 - bitmap.height * VERTICAL_OFFSET_FACTOR)
            .toInt().coerceIn(0, bitmap.height - side)
        return Bitmap.createBitmap(bitmap, x, y, side, side)
    }

    private fun saveThumbnailToDisk(bitmap: Bitmap) {
        try {
            val cropped = cropCentered(bitmap)
            val file = File(filesDir, "thumbnails/$profileKey.png")
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
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

    // -------------------------------------------------------------------------
    // Camera — spherical coords, always looking at origin
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // Touch — 1 finger orbit, 2 finger zoom
    // -------------------------------------------------------------------------
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