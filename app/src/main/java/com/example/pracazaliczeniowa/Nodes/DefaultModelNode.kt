package com.example.pracazaliczeniowa.Nodes

import android.util.Log
import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float3
import com.google.android.filament.Box
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.engine
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.CoroutineScope



/**
 * Default node for AR models.
 * Minimal UI, triggers selection when tapped.
 */
class DefaultModelNode(
    modelInstance: ModelInstance,
    private val scope: CoroutineScope
) : ModelNode(
    modelInstance = modelInstance,
    autoAnimate = true
)

{
    fun wrapAsSelected(scope: CoroutineScope, engine: Engine): SelectedModelNode? {
        val parentNode = this.parent ?: return null

        // 1. Capture current world state
        val worldPos = this.worldPosition
        val worldRot = this.worldQuaternion
        val worldScl = this.worldScale // Capture the actual visual scale

        // 2. Create wrapper at the exact same world location
        val wrapper = SelectedModelNode(engine,scope)

        parentNode.addChildNode(wrapper)
        wrapper.worldPosition = worldPos
        wrapper.worldQuaternion = worldRot
        // Note: We keep wrapper scale at 1.0 to avoid distorting children

        // 3. Move the model into the wrapper ??
        //parentNode.removeChildNode(this)
        //wrapper.addChildNode(this)

        wrapper.attachNode(this)
        // 4. RESET local transforms of 'this' so it sits at 0,0,0 inside wrapper
        // But keep the local scale that makes the model look right
        this.position = Float3(0f)
        this.quaternion = dev.romainguy.kotlin.math.Quaternion()


        return wrapper
    }
    fun scaleToUnits(f: Float) {
        this.scale = Float3(f)
    }
}