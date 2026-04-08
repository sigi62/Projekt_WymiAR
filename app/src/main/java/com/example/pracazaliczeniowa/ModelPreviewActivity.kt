package com.example.pracazaliczeniowa

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.launch

/**
 * Full-screen 3D model preview on a black background.
 *
 * Features:
 *  - Rotate by single-finger swipe (Y-axis)
 *  - Scale by pinch
 *
 * Thumbnail generation is handled by [ThumbnailCaptureHelper] which is
 * triggered from [LibraryActivity] on startup — no capture logic here.
 *
 * Launch extras:
 *  - EXTRA_MODEL_PATH (String) – asset path, e.g. "models/cat.glb"
 */
class ModelPreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODEL_PATH = "extra_model_path"
    }

    private lateinit var sceneView: SceneView
    private var modelNode: ModelNode? = null

    // Touch handling
    private var lastTouchX      = 0f
    private var lastTouchY      = 0f
    private var initialPinchDist = 0f
    private var initialScale    = 1f
    private var isTwoFinger     = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_preview)

        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: return

        sceneView = findViewById(R.id.previewSceneView)
        sceneView.setBackgroundColor(Color.BLACK)
        sceneView.skybox = null
        sceneView.holder?.setFormat(PixelFormat.OPAQUE)

        findViewById<ImageButton>(R.id.btnPreviewBack).setOnClickListener { finish() }

        findViewById<android.widget.Button>(R.id.btnOpenInAR).setOnClickListener {
            // TODO: launch your AR activity here
        }

        lifecycleScope.launch {
            try {
                val instance = sceneView.modelLoader.createModelInstance(modelPath)
                val node = ModelNode(
                    modelInstance = instance,
                    scaleToUnits  = 1.0f,
                    centerOrigin  = io.github.sceneview.math.Position(0f, 0f, 0f)
                ).also {
                    it.isScaleEditable    = false
                    it.isRotationEditable = false
                }
                sceneView.addChildNode(node)
                modelNode = node

                sceneView.cameraNode.position =
                    io.github.sceneview.math.Position(0f, 0f, 2.5f)

            } catch (e: Exception) {
                Log.e("ModelPreview", "Failed to load model: $modelPath", e)
                Toast.makeText(this@ModelPreviewActivity,
                    "Could not load model", Toast.LENGTH_SHORT).show()
            }
        }

        setupTouchListener()
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
