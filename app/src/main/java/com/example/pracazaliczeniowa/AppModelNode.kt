package com.example.pracazaliczeniowa

import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.model.model
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Default node for placement and selection.
 *
 * Handles tap detection and loading a model.
 */
class AppModelNode(
    engine: Engine,
    val modelLoader: ModelLoader,
    private val scope: CoroutineScope,
    private val onSelected: (AppModelNode) -> Unit
) : Node(engine) {

    var modelNode: ModelNode? = null
        private set

    private var _scale: Float = 1f

    /** Tap callback for this node */
    init {
        // Setup single-tap detection
        onDoubleTap = {
            onSelected(this)
            true
        }
    }

    var onDimensionsChanged: ((Float, Float, Float) -> Unit)? = null

    fun loadModel(assetPath: String, initialScale: Float = 1f) {
        _scale = initialScale
        scope.launch {
            val instance = modelLoader.createModelInstance(assetFileLocation = assetPath)

            val mn = ModelNode(
                modelInstance = instance,
                scaleToUnits = 1f
            )

            addChildNode(mn)
            modelNode = mn

            // Apply initial scale
            mn.scale = Scale(_scale, _scale, _scale)

            // Compute bounding box for dimensions
            val bb = mn.modelInstance.model.getBoundingBox()
            val halfX = bb.halfExtent[0]
            val halfY = bb.halfExtent[1]
            val halfZ = bb.halfExtent[2]

            onDimensionsChanged?.invoke(
                halfX * 2f * _scale,
                halfY * 2f * _scale,
                halfZ * 2f * _scale
            )
        }
    }

    fun setMetersScale(scale: Float) {
        _scale = scale
        modelNode?.scale = Scale(scale, scale, scale)
    }

    fun getMetersScale(): Float = _scale

    fun localPosition(): Float3 = Float3(position.x, position.y, position.z)

    fun setLocalPosition(x: Float, y: Float, z: Float) {
        position = Position(x, y, z)
    }
}