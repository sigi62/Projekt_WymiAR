package com.example.pracazaliczeniowa.Nodes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.material.setTexture
import java.nio.ByteBuffer

/**
 * PlaneGridRenderer
 * ─────────────────
 * Swaps the built-in PlaneRenderer dot texture for a 5 cm grid bitmap.
 * Updated for Sceneview 2.x compatibility.
 */
class PlaneGridRenderer(private val sceneView: ARSceneView) {

    private val TEX_SIZE = 256
    private var gridTexture: Texture? = null
    private var injected = false

    /** Call once in onCreate — enables the renderer and builds the texture. */
    fun init() {
        sceneView.planeRenderer.isEnabled = true
        // Set mode to RENDER_ALL or RENDER_TOP_ONLY depending on preference
        sceneView.planeRenderer.planeRendererMode =
            io.github.sceneview.ar.scene.PlaneRenderer.PlaneRendererMode.RENDER_ALL

        gridTexture = buildGridTexture()
        Log.d("PlaneGrid", "init() — texture built")
    }

    /**
     * Call every frame from onSessionUpdated.
     */
    fun update(wallMagnetActive: Boolean) {
        if (!injected) injectTexture()
    }

    fun destroy() {
        gridTexture?.let { sceneView.engine.destroyTexture(it) }
        gridTexture = null
        injected = false
    }

    private fun injectTexture() {
        val tex = gridTexture ?: return

        // In Sceneview 2.x, the material instance is managed internally.
        // We access the current material through the planeRenderer.
        val matInstance = sceneView.planeRenderer.planeMaterial?.defaultInstance ?: run {
            // If the material or its defaultInstance isn't ready, we wait.
            return
        }

        try {
            // "texture" is the parameter name in the default ARCore plane shader
            matInstance.setTexture(
                "texture",
                tex,
                TextureSampler(
                    TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
                    TextureSampler.MagFilter.LINEAR,
                    TextureSampler.WrapMode.REPEAT
                )
            )

            // "uvScale" controls tiling. 20.0f = repeat every 5cm (1/20 meters)
            matInstance.setParameter("uvScale", 20.0f, 20.0f)

            injected = true
            Log.d("PlaneGrid", "Texture injected successfully into material instance")
        } catch (e: Exception) {
            // This might catch if the parameter names changed in a specific filament version
            Log.e("PlaneGrid", "Injection failed: ${e.message}")
        }
    }

    private fun buildGridTexture(): Texture {
        val bmp    = Bitmap.createBitmap(TEX_SIZE, TEX_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Light background fill (semi-transparent)
        canvas.drawColor(Color.argb(15, 255, 255, 255))

        val linePx = (TEX_SIZE * 0.07f).coerceAtLeast(3f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = Color.argb(210, 255, 255, 255)
            strokeWidth = linePx
            style       = Paint.Style.STROKE
            strokeCap   = Paint.Cap.SQUARE
        }
        val h  = linePx / 2f
        val sz = TEX_SIZE.toFloat()

        canvas.drawLine(0f,   h,    sz,   h,    paint)   // top
        canvas.drawLine(0f,   sz-h, sz,   sz-h, paint)   // bottom
        canvas.drawLine(h,    0f,   h,    sz,   paint)   // left
        canvas.drawLine(sz-h, 0f,   sz-h, sz,   paint)   // right

        return uploadBitmap(bmp)
    }

    private fun uploadBitmap(bmp: Bitmap): Texture {
        val engine = sceneView.engine
        val tex = Texture.Builder()
            .width(bmp.width)
            .height(bmp.height)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8)
            .build(engine)

        val buf = ByteBuffer.allocateDirect(bmp.byteCount)
        bmp.copyPixelsToBuffer(buf)
        buf.rewind()

        // Ensure we don't recycle if the engine still needs it,
        // though copyPixelsToBuffer is usually safe for immediate recycle.
        bmp.recycle()

        tex.setImage(engine, 0,
            Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
        return tex
    }
}