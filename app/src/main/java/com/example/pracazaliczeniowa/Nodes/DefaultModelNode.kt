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
    modelInstance: ModelInstance,
    private val scope: CoroutineScope,
    private val sceneView: ARSceneView,
    private val viewAttachmentManager: ViewAttachmentManager
) : ModelNode(
    modelInstance = modelInstance,
    autoAnimate = true
)

{

    var onDimensionsChanged: ((widthM: Float, heightM: Float, depthM: Float) -> Unit)? = null
//    private var floorPlane: PlaneNode? = null
//    private var heightLine: CubeNode? = null
//    private var heightLabel: ViewNode? = null
//    private var widthLabel: ViewNode? = null
//    private var depthLabel: ViewNode? = null
    fun wrapAsSelected(scope: CoroutineScope, ): SelectedModelNode? {
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

        wrapper.attachNode(this, sceneView, viewAttachmentManager)
        // 4. RESET local transforms of 'this' so it sits at 0,0,0 inside wrapper
        // But keep the local scale that makes the model look right
        this.position = Float3(0f)
        this.quaternion = dev.romainguy.kotlin.math.Quaternion()

//        this.showSelection(true)
        return wrapper
    }



//    fun setupSelectionGizmo(sceneView: ARSceneView, viewAttachmentManager: ViewAttachmentManager) {
//        val engine = sceneView.engine
//        val context = sceneView.context
//        val box = modelInstance.asset.boundingBox
//
//        // Use current scale (e.g., 0.02f from ARActivity)
//        val s = this.scale
//        val width = box.halfExtent[0] * 2f * s.x
//        val height = box.halfExtent[1] * 2f * s.y
//        val depth = box.halfExtent[2] * 2f * s.z
//        val thickness = maxOf(width, height, depth) * 0.01f
//
//        val materialLoader = MaterialLoader( engine, context, scope)
//
//        floorPlane = PlaneNode(engine, size = Float3(width, thickness / 4f, depth)).apply {
//            position = Float3(0f, -height / 2f, 0f) // Local to this node
//            materialInstance = materialLoader.createColorInstance(Float4(0.0f, 0.5f, 1.0f, 0.3f))
//            isVisible = false
//        }
//        addChildNode(floorPlane!!)
//
//        heightLine = CubeNode(engine, size = Float3(thickness, height, thickness)).apply {
//            position = Float3(width / 2f, 0f, -depth / 2f)
//            materialInstance = materialLoader.createColorInstance(Float4(1.0f, 0.0f, 0.0f, 1.0f))
//            isVisible = false
//        }
//        addChildNode(heightLine!!)

//        heightLabel = createLabel(
//            String.format("H: %.2fm", height),
//            Float3(width / 2f + thickness, 0f, -depth / 2f),
//            sceneView, viewAttachmentManager
//        )
//        widthLabel = createLabel(
//            String.format("W: %.2fm", width),
//            Float3(0f, -height / 2f, depth / 2f + thickness),
//            sceneView, viewAttachmentManager
//        )
//        depthLabel = createLabel(
//            text = String.format("D: %.2fm", depth),
//            // Position: Right side (+X), Bottom (-Y), Middle of Depth (0 Z)
//            pos = Float3(width / 2f + thickness, -height / 2f, 0f),
//            sceneView, viewAttachmentManager
//        )
//        addChildNode(heightLabel!!)
//        addChildNode(widthLabel!!)
//        addChildNode(depthLabel!!)

//    }

//    fun showSelection(visible: Boolean) {
//        floorPlane?.isVisible = visible
//        heightLine?.isVisible = visible
////        heightLabel?.isVisible = visible
////        widthLabel?.isVisible = visible
////        depthLabel?.isVisible = visible
//    }
//
//    fun destroyGizmo() {
//
//        floorPlane?.destroy()
//        floorPlane = null
//
//        heightLine?.destroy()
//        heightLine = null
//
//        heightLabel?.destroy()
//        heightLabel = null
//
//        widthLabel?.destroy()
//        widthLabel = null
//
//        depthLabel?.destroy()
//        depthLabel = null
//    }
    fun scaleToUnits(f: Float) {
        this.scale = Float3(f)
    }
}
//
//private fun createLabel(
//    text: String,
//    pos: Float3,
//    sceneView: ARSceneView,
//    viewAttachmentManager: ViewAttachmentManager
//): ViewNode {
//    return ViewNode(
//        engine = sceneView.engine,
//        modelLoader = sceneView.modelLoader,
//        viewAttachmentManager = viewAttachmentManager
//    ).apply {
//        // Use the signature from your screenshot: loadView(context, layoutResId)
//        loadView(sceneView.context, R.layout.view_model_label)
//
//        onViewLoaded = { _, view ->
//            (view as? TextView)?.text = text
//        }
//
//        this.position = pos
//        this.isVisible = false
//    }
//}