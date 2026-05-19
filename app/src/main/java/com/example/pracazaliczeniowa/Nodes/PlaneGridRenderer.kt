package com.example.pracazaliczeniowa.Nodes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.example.pracazaliczeniowa.Objects.PlaneMode
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.material.setTexture
import java.nio.ByteBuffer

/**
 * PlaneGridRenderer
 * ─────────────────
 * Swaps the built-in PlaneRenderer dot texture for a grid bitmap.
 *
 * Plane visibility is filtered according to [PlaneMode]:
 *   HORIZONTAL → show HORIZONTAL planes only
 *   VERTICAL   → show VERTICAL planes only
 *   BOTH       → show all tracked planes
 *   OFF        → hide all plane overlays
 */
class PlaneGridRenderer(private val sceneView: ARSceneView) {

    private val TEX_SIZE = 256
    private var gridTexture: Texture? = null
    private var injected = false

    private var frameCount = 0

    private var visualizersField: java.lang.reflect.Field? = null
    private var setVisibleMethod: java.lang.reflect.Method? = null
    private var reflectionReady = false

    // ── Public API ────────────────────────────────────────────────────────────

    fun init() {
        sceneView.planeRenderer.isEnabled = true
        sceneView.planeRenderer.planeRendererMode =
            io.github.sceneview.ar.scene.PlaneRenderer.PlaneRendererMode.RENDER_ALL

        gridTexture = buildGridTexture()
        prepareReflection()
        Log.d("PlaneGrid", "init() — texture built, reflection ready=$reflectionReady")
    }

    /**
     * Call every frame from onSessionUpdated.
     * [mode] controls which planes are shown and accepted for hit-testing.
     */
    fun update(mode: PlaneMode) {
        frameCount++
        if (!injected && frameCount > 2) injectTexture()
        filterPlaneVisibility(mode)
    }

    fun destroy() {
        gridTexture?.let { sceneView.engine.destroyTexture(it) }
        gridTexture = null
        injected = false
    }

    // ── Reflection setup ──────────────────────────────────────────────────────

    private fun prepareReflection() {
        try {
            val rendererClass = sceneView.planeRenderer.javaClass

            val field = rendererClass.declaredFields.firstOrNull { f ->
                Map::class.java.isAssignableFrom(f.type)
            } ?: run {
                Log.w("PlaneGrid", "Could not find visualizers field via reflection")
                return
            }
            field.isAccessible = true
            visualizersField = field

            val vizClass = try {
                Class.forName("io.github.sceneview.ar.scene.PlaneVisualizer")
            } catch (e: ClassNotFoundException) {
                Log.d("PlaneGrid", "PlaneVisualizer not found by name — will resolve lazily")
                null
            }

            if (vizClass != null) {
                val method = vizClass.methods.firstOrNull { m ->
                    m.name == "setVisible" &&
                            m.parameterCount == 1 &&
                            m.parameterTypes[0] == Boolean::class.javaPrimitiveType
                }
                if (method != null) {
                    method.isAccessible = true
                    setVisibleMethod = method
                    reflectionReady = true
                    Log.d("PlaneGrid", "Reflection ready via class name lookup")
                } else {
                    Log.w("PlaneGrid", "setVisible not found on PlaneVisualizer")
                }
            }
        } catch (e: Exception) {
            Log.e("PlaneGrid", "prepareReflection failed: ${e.message}")
        }
    }

    // ── Per-plane visibility filter ───────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun filterPlaneVisibility(mode: PlaneMode) {
        val field = visualizersField ?: return
        try {
            val map = field.get(sceneView.planeRenderer) as? Map<*, *> ?: return
            if (map.isEmpty()) return

            if (!reflectionReady) {
                val firstViz = map.values.firstOrNull() ?: return
                val method = firstViz.javaClass.methods.firstOrNull { m ->
                    m.name == "setVisible" &&
                            m.parameterCount == 1 &&
                            m.parameterTypes[0] == Boolean::class.javaPrimitiveType
                } ?: return
                method.isAccessible = true
                setVisibleMethod = method
                reflectionReady = true
                Log.d("PlaneGrid", "Reflection ready via lazy resolution")
            }

            val method = setVisibleMethod ?: return

            for ((planeKey, visualizer) in map) {
                val plane = planeKey as? Plane ?: continue
                visualizer ?: continue

                val isActive = plane.trackingState == TrackingState.TRACKING
                        && plane.subsumedBy == null

                val shouldShow = isActive && when (mode) {
                    PlaneMode.OFF        -> false
                    PlaneMode.HORIZONTAL -> plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING ||
                            plane.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING
                    PlaneMode.VERTICAL   -> plane.type == Plane.Type.VERTICAL
                    PlaneMode.BOTH       -> true
                }

                method.invoke(visualizer, shouldShow)
            }
        } catch (e: Exception) {
            Log.w("PlaneGrid", "filterPlaneVisibility error: ${e.message}")
        }
    }

    // ── Texture injection ─────────────────────────────────────────────────────

    private fun injectTexture() {
        val tex = gridTexture ?: return
        val matInstance = sceneView.planeRenderer.planeMaterial?.defaultInstance ?: return
        try {
            matInstance.setTexture(
                "texture",
                tex,
                TextureSampler(
                    TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
                    TextureSampler.MagFilter.LINEAR,
                    TextureSampler.WrapMode.REPEAT
                )
            )
            matInstance.setParameter("uvScale", 8.0f, 8.0f)
            injected = true
            Log.d("PlaneGrid", "Texture injected successfully")
        } catch (e: Exception) {
            Log.e("PlaneGrid", "Injection failed: ${e.message}")
        }
    }

    // ── Texture building ──────────────────────────────────────────────────────

    private fun buildGridTexture(): Texture {
        val bmp    = Bitmap.createBitmap(TEX_SIZE, TEX_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

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

        canvas.drawLine(0f,   h,    sz,   h,    paint)
        canvas.drawLine(0f,   sz-h, sz,   sz-h, paint)
        canvas.drawLine(h,    0f,   h,    sz,   paint)
        canvas.drawLine(sz-h, 0f,   sz-h, sz,   paint)

        return uploadBitmap(bmp)
    }

    private fun mipLevels(w: Int, h: Int): Int {
        var levels = 1
        var size = maxOf(w, h)
        while (size > 1) { size = size shr 1; levels++ }
        return levels
    }

    private fun uploadBitmap(bmp: Bitmap): Texture {
        val engine = sceneView.engine
        val tex = Texture.Builder()
            .width(bmp.width)
            .height(bmp.height)
            .levels(mipLevels(bmp.width, bmp.height))
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .build(engine)

        val buf = ByteBuffer.allocateDirect(bmp.byteCount)
        bmp.copyPixelsToBuffer(buf)
        buf.rewind()
        bmp.recycle()

        tex.setImage(engine, 0,
            Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))

        tex.generateMipmaps(engine)

        return tex
    }
}