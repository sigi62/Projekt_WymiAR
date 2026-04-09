package com.example.pracazaliczeniowa

import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Full-screen 3D model preview — no AR, grey void background.
 *
 * Uses plain SceneView (same Filament pipeline as ARSceneView, no AR session).
 * Layout XML must declare the view as io.github.sceneview.SceneView.
 *
 * One-finger drag  → orbit camera (azimuth + elevation)
 * Two-finger pinch → zoom (camera distance)
 *
 * Extras:
 *  - EXTRA_MODEL_PATH  (String) – asset path, e.g. "models/cat.glb"
 *  - EXTRA_PROFILE_KEY (String) – reserved for thumbnail use
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

        sceneView = findViewById(R.id.previewSceneView)

        // ── Grey void ─────────────────────────────────────────────────────
        // No skybox → Filament clears to the window/view background colour.
        // The view background is set to #808080 in the layout XML (or below).
        sceneView.skybox = null

        // ── Lighting ──────────────────────────────────────────────────────
        // SceneView 2.x ships a built-in default environment (KTX IBL + a
        // directional main light node).  Loading our HDR on top of that gives
        // the same quality as ARActivity while keeping the main light in place.
        lifecycleScope.launch {
            try {
                // loadHDREnvironment returns an Environment that contains
                // both indirectLight (IBL) and optionally a skybox.
                // We only take the indirectLight — skybox stays null (grey void).
                val env = sceneView.environmentLoader.loadHDREnvironment("envs/environment.hdr")
                if (env != null) {
                    sceneView.environment = env.apply {
                        // Swap the skybox out so the background stays grey
                        sceneView.skybox = null
                    }
                    Log.d(TAG, "HDR environment loaded")
                } else {
                    Log.w(TAG, "loadHDREnvironment returned null — using SceneView default lighting")
                }
            } catch (e: Exception) {
                // If environment.hdr is missing or fails, SceneView's built-in
                // default lighting is still active — model will still be visible.
                Log.w(TAG, "Could not load environment.hdr: ${e.message}")
            }
        }

        // ── Buttons ───────────────────────────────────────────────────────
        findViewById<ImageButton>(R.id.btnPreviewBack).setOnClickListener { finish() }

        findViewById<android.widget.Button>(R.id.btnOpenInAR).setOnClickListener {
            // TODO: launch ARActivity with modelPath
        }

        // btnTakeScreenshot — thumbnail logic intentionally omitted for now

        // ── Load model ────────────────────────────────────────────────────
        lifecycleScope.launch {
            loadModel(modelPath)
        }

        setupTouchListener()
    }

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