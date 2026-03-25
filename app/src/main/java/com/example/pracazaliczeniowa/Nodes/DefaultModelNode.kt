package com.example.pracazaliczeniowa.Nodes

import android.util.Log
import android.widget.TextView
import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float3
import com.google.android.filament.Box
import com.google.ar.sceneform.rendering.ViewAttachmentManager
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.engine
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.node.ViewNode
import kotlinx.coroutines.CoroutineScope
import android.view.LayoutInflater
import android.widget.SearchView
import com.example.pracazaliczeniowa.R
import com.example.pracazaliczeniowa.R.layout


/**
 * Default node for AR models.
 * Minimal UI, triggers selection when tapped.
 */
class DefaultModelNode(
    val modelPath: String,
    modelInstance: ModelInstance,
    private val scope: CoroutineScope,
    private val sceneView: ARSceneView,
    private val viewAttachmentManager: ViewAttachmentManager
) : ModelNode(
    modelInstance = modelInstance,
    autoAnimate = true
)

{
    // Helper to get a clean name for the file system (e.g., "cat")
    fun getModeleName(): String {
        return modelPath.substringAfterLast("/").substringBeforeLast(".")
    }
    fun wrapAsSelected(scope: CoroutineScope, ): SelectedModelNode? {
        val parentNode = this.parent ?: return null

        // 1. Capture current world state
        val worldPos = this.worldPosition
        val worldRot = this.worldQuaternion
        val worldScl = this.worldScale // Capture the actual visual scale

        // 2. Create wrapper at the exact same world location
        val wrapper = SelectedModelNode(engine,scope)

        parentNode.addChildNode(wrapper)


        wrapper.attachNode(this)
        wrapper.worldPosition = worldPos
        wrapper.worldQuaternion = worldRot
        // Note: We keep wrapper scale at 1.0 to avoid distorting children

        // 3. Move the model into the wrapper ??
        //parentNode.removeChildNode(this)
        //wrapper.addChildNode(this)

        // change -= wrapper.attachNode(this)
        showSelectedNodeDimensions(wrapper)
        wrapper.showRotationHandle(engine, sceneView)

        // 4. RESET local transforms of 'this' so it sits at 0,0,0 inside wrapper
        // But keep the local scale that makes the model look right
        this.position = Float3(0f)
        this.quaternion = dev.romainguy.kotlin.math.Quaternion()

        return wrapper
    }

    fun showSelectedNodeDimensions(selectedNode: SelectedModelNode) {
        selectedNode.showDimensions(this, sceneView, viewAttachmentManager)
    }
    fun scaleToUnits(f: Float) {
        this.scale = Float3(f)
    }
}
