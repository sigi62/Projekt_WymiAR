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
        private const val SETTLE_MS  = 800L // Time for textures to load
        private val GREY_VOID_COLOR  = Color.rgb(220, 220, 220)
    }

    private val handler       = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private lateinit var sceneView: SceneView
    private val scope = MainScope()

    fun start() {
        val pending = models.filter { !File(context.filesDir, "thumbnails/${it.profileKey}.png").exists() }
        if (pending.isEmpty()) { onAllDone(); return }

        attachHiddenSceneView(pending)
    }

    private fun attachHiddenSceneView(pending: List<ModelItem>) {
        sceneView = SceneView(context).apply {
            setBackgroundColor(GREY_VOID_COLOR)
            skybox = null
            visibility = View.VISIBLE // MUST be visible to render
        }

        val params = WindowManager.LayoutParams(
            SIZE_PX, SIZE_PX,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            x = 10000 // Move off-screen
            y = 10000
        }

        sceneView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { processNext(pending, 0) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h1: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })

        windowManager.addView(sceneView, params)
    }

    private fun processNext(pending: List<ModelItem>, index: Int) {
        if (index >= pending.size) {
            windowManager.removeViewImmediate(sceneView)
            onAllDone()
            return
        }

        val item = pending[index]
        sceneView.childNodes.filterIsInstance<ModelNode>().forEach { sceneView.removeChildNode(it) }

        scope.launch {
            try {
                val node = ModelNode(
                    modelInstance = sceneView.modelLoader.createModelInstance(item.modelPath)!!,
                    scaleToUnits  = 0.7f,
                    centerOrigin  = io.github.sceneview.math.Position(0f, 0f, 0f)
                ).apply {
                    rotation = io.github.sceneview.math.Rotation(0f, 45f, 0f)
                }
                sceneView.addChildNode(node)
                sceneView.cameraNode.position = io.github.sceneview.math.Position(1f, 1f, 1f)
                sceneView.cameraNode.lookAt(io.github.sceneview.math.Position(0f, 0f, 0f))

                handler.postDelayed({ captureAndSave(item.profileKey) { processNext(pending, index + 1) } }, SETTLE_MS)
            } catch (e: Exception) { processNext(pending, index + 1) }
        }
    }

    private fun captureAndSave(profileKey: String, onDone: () -> Unit) {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sceneView, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                val file = File(context.filesDir, "thumbnails/$profileKey.png")
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            onDone()
        }, handler)
    }
}