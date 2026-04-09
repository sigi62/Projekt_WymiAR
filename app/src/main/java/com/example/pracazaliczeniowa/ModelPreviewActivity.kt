package com.example.pracazaliczeniowa

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Full-screen 3D model preview on a grey void background.
 *
 * Features:
 *  - Starts at an isometric-style angle (camera elevated + offset, looking at origin)
 *  - Rotate by single-finger swipe (Y-axis)
 *  - Scale by pinch
 *  - "Set Thumbnail" button: captures the current view via PixelCopy and saves it
 *    to filesDir/thumbnails/<profileKey>.png, then shows a confirmation toast.
 *
 * Launch extras:
 *  - EXTRA_MODEL_PATH  (String) – asset path, e.g. "models/cat.glb"
 *  - EXTRA_PROFILE_KEY (String) – used as the thumbnail filename, e.g. "cat"
 */
class ModelPreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODEL_PATH  = "extra_model_path"
        const val EXTRA_PROFILE_KEY = "extra_profile_key"

        // Match the grey used by ThumbnailCaptureHelper so the background is consistent
        private val GREY_VOID_COLOR = Color.rgb(220, 220, 220)

        // Starting isometric camera position (same angle as ThumbnailCaptureHelper)
        private val CAM_POS = io.github.sceneview.math.Position(1f, 1f, 1f)
        private val ORIGIN  = io.github.sceneview.math.Position(0f, 0f, 0f)

        private const val TAG = "ModelPreviewActivity"
    }

    private lateinit var sceneView: SceneView
    private var modelNode: ModelNode? = null

    // Touch handling
    private var lastTouchX       = 0f
    private var lastTouchY       = 0f
    private var initialPinchDist = 0f
    private var initialScale     = 1f
    private var isTwoFinger      = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_preview)

        val modelPath  = intent.getStringExtra(EXTRA_MODEL_PATH)  ?: return
        val profileKey = intent.getStringExtra(EXTRA_PROFILE_KEY) ?: return

        sceneView = findViewById(R.id.previewSceneView)
        sceneView.setBackgroundColor(GREY_VOID_COLOR)
        sceneView.skybox = null
        sceneView.holder?.setFormat(PixelFormat.OPAQUE)

        findViewById<ImageButton>(R.id.btnPreviewBack).setOnClickListener { finish() }

        findViewById<android.widget.Button>(R.id.btnOpenInAR).setOnClickListener {
            // TODO: launch AR activity here
        }

        findViewById<android.widget.Button>(R.id.btnTakeScreenshot).setOnClickListener {
            captureAndSaveThumbnail(profileKey)
        }

        lifecycleScope.launch {
            try {
                val instance = sceneView.modelLoader.createModelInstance(modelPath)
                val node = ModelNode(
                    modelInstance = instance,
                    scaleToUnits  = 1.0f,
                    centerOrigin  = ORIGIN
                ).also {
                    it.isScaleEditable    = false
                    it.isRotationEditable = false
                    // Start at the same 45° Y rotation used for auto-thumbnails
                    it.rotation = io.github.sceneview.math.Rotation(0f, 45f, 0f)
                }
                sceneView.addChildNode(node)
                modelNode = node

                // Isometric-style camera: elevated and offset, looking at origin
                sceneView.cameraNode.position = CAM_POS
                sceneView.cameraNode.lookAt(ORIGIN)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model: $modelPath", e)
                Toast.makeText(this@ModelPreviewActivity,
                    "Could not load model", Toast.LENGTH_SHORT).show()
            }
        }

        setupTouchListener()
    }

    // -----------------------------------------------------------------------
    // Thumbnail capture
    // -----------------------------------------------------------------------

    private fun captureAndSaveThumbnail(profileKey: String) {
        val bitmap = Bitmap.createBitmap(
            sceneView.width.takeIf { it > 0 } ?: 256,
            sceneView.height.takeIf { it > 0 } ?: 256,
            Bitmap.Config.ARGB_8888
        )

        PixelCopy.request(sceneView, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                try {
                    val file = File(filesDir, "thumbnails/$profileKey.png")
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    runOnUiThread {
                        Toast.makeText(this, "Thumbnail saved!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save thumbnail for $profileKey", e)
                    runOnUiThread {
                        Toast.makeText(this, "Failed to save thumbnail", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Capture failed (code $result)", Toast.LENGTH_SHORT).show()
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    // -----------------------------------------------------------------------
    // Gesture handling – single finger = rotate Y, two fingers = pinch scale
    // -----------------------------------------------------------------------

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
                        initialScale     = modelNode?.scale?.x ?: 1f
                    }
                }
                android.view.MotionEvent.ACTION_POINTER_UP -> {
                    isTwoFinger = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val node = modelNode ?: return@setOnTouchListener true

                    if (isTwoFinger && event.pointerCount >= 2) {
                        val dist = fingerSpacing(event)
                        if (initialPinchDist > 0f) {
                            val factor   = dist / initialPinchDist
                            val newScale = (initialScale * factor).coerceIn(0.1f, 5f)
                            node.scale   = io.github.sceneview.math.Scale(newScale)
                        }
                    } else {
                        val dx          = event.x - lastTouchX
                        val sensitivity = 0.4f
                        val currentRot  = node.rotation
                        node.rotation   = io.github.sceneview.math.Rotation(
                            currentRot.x,
                            currentRot.y + dx * sensitivity,
                            currentRot.z
                        )
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                }
            }
            true
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun fingerSpacing(event: android.view.MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(x * x + y * y)
    }

    override fun onDestroy() {
        super.onDestroy()
        sceneView.destroy()
    }
}
