package com.example.pracazaliczeniowa

import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.model.model
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Base node for the project.
 *
 * Wraps a [ModelNode] child and tracks real-world dimensions (metres) that
 * automatically update whenever the scale changes.
 *
 * Usage:
 *   val node = MeasurableModelNode(engine, modelLoader, lifecycleScope)
 *   node.loadModel("models/cube.glb", initialScale = 0.5f)
 *   node.onDimensionsChanged = { w, h, d -> ... }
 *   anchorNode.addChildNode(node)
 */
open class MeasurableModelNode(
    engine: Engine,
    private val modelLoader: ModelLoader,
    private val scope: CoroutineScope,
) : Node(engine) {

    /** Inner model node, available after [loadModel] suspends. */
    var modelNode: ModelNode? = null

    private var _scale: Float = 1f

    // Normalised half-extents from the bounding box (model fitted to 1 m longest axis).
    private var halfX = 0f
    private var halfY = 0f
    private var halfZ = 0f

    // ── Public dimensions ──────────────────────────────────────────────────

    /** Width along X axis, in metres. */
    val widthMeters: Float  get() = halfX * 2f * _scale

    /** Height along Y axis, in metres. */
    val heightMeters: Float get() = halfY * 2f * _scale

    /** Depth along Z axis, in metres. */
    val depthMeters: Float  get() = halfZ * 2f * _scale

    /**
     * Called on the main thread whenever width/height/depth change
     * (after model load or after [setScale]).
     */
    var onDimensionsChanged: ((widthM: Float, heightM: Float, depthM: Float) -> Unit)? = null

    // ── Loading ────────────────────────────────────────────────────────────

    /**
     * Asynchronously loads [assetPath] from assets/ and attaches it as a child.
     * [initialScale] is the desired real-world scale in metres (applied to the
     * normalised 1 m model, so 0.5 → 50 cm on the longest axis).
     */
    fun loadModel(assetPath: String, initialScale: Float = 1f) {
        _scale = initialScale
        scope.launch {
            val instance = modelLoader.createModelInstance(assetFileLocation = assetPath)

            // scaleToUnits = 1f normalises the model so its longest bounding-box
            // axis equals exactly 1 m. We then apply _scale manually.
            val mn = ModelNode(
                modelInstance = instance,
                scaleToUnits   = 1f,
                centerOrigin   = Position(y = -1f),
            )
            addChildNode(mn)
            modelNode = mn

            // Read the normalised bounding box from the filament asset.
            readBoundingBox()

            // Apply the desired visual scale.
            mn.scale = Scale(_scale, _scale, _scale)

            fireDimensions()
        }
    }

    // ── Transform API (scale + position) ───────────────────────────────────

    /** Current uniform scale in metres. */
    fun getMetersScale(): Float = _scale

    /**
     * Rescales the model uniformly to [scale] metres.
     * Dimension values and [onDimensionsChanged] are updated immediately.
     */
    fun setMetersScale(scale: Float) {
        _scale = scale
        modelNode?.scale = Scale(scale, scale, scale)
        fireDimensions()
    }

    /** Convenience getter for this node's local-position as [Float3]. */
    fun localPosition(): Float3 = Float3(position.x, position.y, position.z)

    /** Convenience setter for this node's local-position in metres. */
    fun setLocalPosition(x: Float, y: Float, z: Float) {
        position = Position(x, y, z)
    }

    // ── World-space axis endpoints (used by DimensionOverlayView) ──────────

    /**
     * Returns the six midpoints of the bounding-box faces in world space,
     * ordered: [-X, +X, -Y, +Y, -Z, +Z].
     * Returns null if the model has not finished loading.
     */
    fun getWorldAxisEndpoints(): Array<Float3>? {
        val mn = modelNode ?: return null
        val s  = _scale

        val local = arrayOf(
            Float4(-halfX * s, 0f, 0f, 1f),   // 0: -X
            Float4( halfX * s, 0f, 0f, 1f),   // 1: +X
            Float4(0f, -halfY * s, 0f, 1f),   // 2: -Y
            Float4(0f,  halfY * s, 0f, 1f),   // 3: +Y
            Float4(0f, 0f, -halfZ * s, 1f),   // 4: -Z
            Float4(0f, 0f,  halfZ * s, 1f),   // 5: +Z
        )

        val wt = mn.worldTransform
        return local.map { p ->
            val r = wt * p
            Float3(r.x, r.y, r.z)
        }.toTypedArray()
    }

    /**
     * Returns the eight corners of the model's bounding box in world space.
     *
     * Order:
     *  0: (-X, -Y, -Z)
     *  1: (+X, -Y, -Z)
     *  2: (+X, -Y, +Z)
     *  3: (-X, -Y, +Z)
     *  4: (-X, +Y, -Z)
     *  5: (+X, +Y, -Z)
     *  6: (+X, +Y, +Z)
     *  7: (-X, +Y, +Z)
     */
    fun getWorldBoundingBoxCorners(): Array<Float3>? {
        val mn = modelNode ?: return null
        val s = _scale

        val x = halfX * s
        val y = halfY * s
        val z = halfZ * s

        val local = arrayOf(
            Float4(-x, -y, -z, 1f),
            Float4( x, -y, -z, 1f),
            Float4( x, -y,  z, 1f),
            Float4(-x, -y,  z, 1f),
            Float4(-x,  y, -z, 1f),
            Float4( x,  y, -z, 1f),
            Float4( x,  y,  z, 1f),
            Float4(-x,  y,  z, 1f),
        )

        val wt = mn.worldTransform
        return local.map { p ->
            val r = wt * p
            Float3(r.x, r.y, r.z)
        }.toTypedArray()
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private fun readBoundingBox() {
        // `modelInstance.model` (via the io.github.sceneview.model.model extension) already
        // returns the underlying FilamentAsset — there is no further `.filamentAsset` property.
        // FilamentAsset.getBoundingBox() returns com.google.android.filament.Box where
        // halfExtent is a FloatArray(3): [0]=X, [1]=Y, [2]=Z.
        val bb = modelNode?.modelInstance?.model?.getBoundingBox() ?: return
        halfX = bb.halfExtent[0]
        halfY = bb.halfExtent[1]
        halfZ = bb.halfExtent[2]
    }

    private fun fireDimensions() {
        onDimensionsChanged?.invoke(widthMeters, heightMeters, depthMeters)
    }
}
