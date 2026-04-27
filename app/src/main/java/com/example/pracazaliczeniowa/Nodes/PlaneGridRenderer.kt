package com.example.pracazaliczeniowa.Nodes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
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
 * Swaps the built-in PlaneRenderer dot texture for a 5 cm grid bitmap.
 *
 * Also filters plane visibility per mode:
 *   wallMagnetActive = true  → show VERTICAL planes only
 *   wallMagnetActive = false → show HORIZONTAL planes only
 *
 * SceneView 2.3.3 exposes no per-plane setVisible() publicly, so we reach
 * the private `visualizers: MutableMap<Plane, PlaneVisualizer>` field via
 * reflection and call PlaneVisualizer.setVisible() on each entry.
 */
class PlaneGridRenderer(private val sceneView: ARSceneView) {

    private val TEX_SIZE = 256
    private var gridTexture: Texture? = null
    private var injected = false

    // Cached reflection references — resolved once on first use.
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

    /** Call every frame from onSessionUpdated. */
    fun update(wallMagnetActive: Boolean) {
        if (!injected) injectTexture()
        filterPlaneVisibility(wallMagnetActive)
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

            // Find the private 'visualizers' field (MutableMap<Plane, PlaneVisualizer>)
            val field = rendererClass.declaredFields.firstOrNull { f ->
                Map::class.java.isAssignableFrom(f.type)
            } ?: run {
                Log.w("PlaneGrid", "Could not find visualizers field via reflection")
                return
            }
            field.isAccessible = true
            visualizersField = field

            // Attempt to resolve PlaneVisualizer's setVisible(Boolean) by class name.
            // If the class is not accessible by name, we fall back to lazy resolution
            // from the first live entry in the map (see filterPlaneVisibility).
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
    private fun filterPlaneVisibility(wallMagnetActive: Boolean) {
        val field = visualizersField ?: return
        try {
            val map = field.get(sceneView.planeRenderer) as? Map<*, *> ?: return
            if (map.isEmpty()) return

            // Lazily resolve setVisible from the first non-null visualizer instance
            // when class-name lookup failed during init().
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
                if (plane.trackingState != TrackingState.TRACKING) continue
                if (plane.subsumedBy != null) continue
                visualizer ?: continue

                val shouldShow = if (wallMagnetActive) {
                    plane.type == Plane.Type.VERTICAL
                } else {
                    plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING ||
                            plane.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING
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
            matInstance.setParameter("uvScale", 20.0f, 20.0f)
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
        bmp.recycle()

        tex.setImage(engine, 0,
            Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
        return tex
    }
}