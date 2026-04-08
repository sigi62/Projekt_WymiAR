package com.example.pracazaliczeniowa.Helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class ThumbnailCaptureHelper(
    private val context: Context,
    private val models: List<ModelItem>,
    private val onAllDone: () -> Unit
) {

    companion object {
        private const val TAG        = "ThumbnailCapture"
        private const val SIZE_PX    = 256
        private const val SETTLE_MS  = 1000L
        private val GREY_VOID_COLOR  = Color.rgb(200, 200, 200)
    }

    private val handler       = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private lateinit var sceneView: SceneView
    private val scope = MainScope()

    fun start() {
        val pending = models.filter { item ->
            !File(context.filesDir, "thumbnails/${item.profileKey}.png").exists()
        }

        if (pending.isEmpty()) {
            onAllDone()
            return
        }

        attachHiddenSceneView(pending)
    }

    private fun attachHiddenSceneView(pending: List<ModelItem>) {
        sceneView = SceneView(context).apply {
            setBackgroundColor(GREY_VOID_COLOR)
            skybox = null
            // We need visibility to be INVISIBLE or VISIBLE for a surface to be created
            visibility = View.INVISIBLE
        }

        // Use PixelFormat.TRANSLUCENT to ensure the alpha channel is handled if needed
        sceneView.holder.setFormat(PixelFormat.TRANSLUCENT)

        val params = WindowManager.LayoutParams(
            SIZE_PX, SIZE_PX,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        // CRITICAL: We must wait for the surface to be valid
        sceneView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "Surface created, starting capture loop")
                processNext(pending, 0)
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h1: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })

        try {
            windowManager.addView(sceneView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add view to WindowManager", e)
            onAllDone()
        }
    }

    private fun processNext(pending: List<ModelItem>, index: Int) {
        if (index >= pending.size) {
            detachHiddenSceneView()
            onAllDone()
            return
        }

        val item = pending[index]
        sceneView.childNodes.filterIsInstance<ModelNode>().forEach { sceneView.removeChildNode(it) }

        scope.launch {
            try {
                val instance = sceneView.modelLoader.createModelInstance(item.modelPath)
                val node = ModelNode(
                    modelInstance = instance,
                    scaleToUnits  = 0.7f,
                    centerOrigin  = io.github.sceneview.math.Position(0f, 0f, 0f)
                ).apply {
                    rotation = io.github.sceneview.math.Rotation(0f, 45f, 0f)
                }

                sceneView.addChildNode(node)

                // Isometric "Looking Down" Angle
                sceneView.cameraNode.position = io.github.sceneview.math.Position(1.0f, 1.0f, 1.0f)
                sceneView.cameraNode.lookAt(io.github.sceneview.math.Position(0f, 0f, 0f))

                handler.postDelayed({
                    captureAndSave(item.profileKey) {
                        processNext(pending, index + 1)
                    }
                }, SETTLE_MS)

            } catch (e: Exception) {
                Log.e(TAG, "Model load failed: ${item.profileKey}", e)
                processNext(pending, index + 1)
            }
        }
    }

    private fun captureAndSave(profileKey: String, onDone: () -> Unit) {
        // Double check surface validity before request
        val surface = sceneView.holder.surface
        if (surface == null || !surface.isValid) {
            Log.e(TAG, "Surface invalid for $profileKey - skipping")
            onDone()
            return
        }

        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)

        try {
            PixelCopy.request(sceneView, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    saveToFile(bitmap, profileKey)
                } else {
                    Log.w(TAG, "PixelCopy failed with code $result")
                }
                onDone()
            }, handler)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "PixelCopy crashed for $profileKey", e)
            onDone()
        }
    }

    private fun saveToFile(bitmap: Bitmap, profileKey: String) {
        val file = File(context.filesDir, "thumbnails/$profileKey.png")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun detachHiddenSceneView() {
        try {
            windowManager.removeViewImmediate(sceneView)
            sceneView.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup error", e)
        }
    }
}