package com.example.pracazaliczeniowa.Nodes

import androidx.compose.ui.graphics.Color
import com.example.pracazaliczeniowa.Overlays.DistanceUnit
import com.google.android.filament.Engine
import com.google.ar.sceneform.rendering.ViewAttachmentManager
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.createMaterialLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.node.ViewNode
import kotlinx.coroutines.CoroutineScope
/**
 * Selected node with extra overlays: highlight, width-depth plane, and height line.
 * Wraps an existing DefaultModelNode without changing its transform or scale.
 */
class SelectedModelNode(
    engine: Engine,
    private val scope: CoroutineScope,
) : Node(engine)
{
    private var wrappedNode: DefaultModelNode? = null
    private var dimensionOverlay: DimensionOverlayNode? = null

    private var initialWorldPos = Float3(0f)
    private var initialWorldQuat = dev.romainguy.kotlin.math.Quaternion()
    private var baseScale = Float3(1f)

    fun getWrappedNode(): DefaultModelNode? = wrappedNode
    fun unwrap(): DefaultModelNode? {

        val child = wrappedNode ?: return null
        val currentAnchor = this.parent ?: return null

        dimensionOverlay?.let {
            this.removeChildNode(it)
            it.destroy() // This cleans up the ViewNodes from ViewAttachmentManager
            dimensionOverlay = null
        }

//        child.showSelection(false)
        // 1. Capture state
        val pos = this.worldPosition
        val rot = this.worldQuaternion
        val localScale = child.scale

        // 3. Re-parent
        this.removeChildNode(child)
        currentAnchor.addChildNode(child)

        child.worldPosition = pos
        child.worldQuaternion = rot
        child.scale = localScale


        currentAnchor.removeChildNode(this)
        this.destroy() // Clean up the wrapper node
        return child
    }

    fun attachNode(node: DefaultModelNode) {
        this.wrappedNode = node

        // Capture starting state to use as the "Middle" (progress 100) point
        this.initialWorldPos = this.worldPosition
        this.initialWorldQuat = this.worldQuaternion
        this.baseScale = node.scale

        if (node.parent != this) {
            node.parent?.removeChildNode(node)
            this.addChildNode(node)
        }
        this.scale = Float3(1.0f)
    }

    fun showDimensions(node: DefaultModelNode, sceneView: SceneView, viewAttachmentManager: ViewAttachmentManager){

        dimensionOverlay?.destroy() // Remove old one if exists
        dimensionOverlay = DimensionOverlayNode(
            engine = engine,
            modelLoader = sceneView.modelLoader,
            materialLoader = sceneView.materialLoader,
            context = sceneView.context,
            viewAttachmentManager = viewAttachmentManager,
            targetNode = node
        )
        this.addChildNode(dimensionOverlay!!)
    }


    // Inside SelectedModelNode class
    fun updateRotation(xDeg: Float, yDeg: Float, zDeg: Float) {
        // x, y, z arrive as -180 to 180. We apply this as a relative offset to initial rotation.
        val extraRot = dev.romainguy.kotlin.math.Quaternion.fromEuler(Float3(xDeg, yDeg, zDeg))
        this.worldQuaternion = initialWorldQuat * extraRot
    }

    fun updatePosition(x: Float, y: Float, z: Float) {
        // x, y, z arrive as -5.0 to 5.0 meters.
        this.worldPosition = initialWorldPos + Float3(x, y, z)
    }

    fun updateScale(xMult: Float, yMult: Float, zMult: Float, uniProgress: Float) {
        // uniProgress 100 = 1.0x. Scale sliders provide up to 2.5x each.
        val universalFactor = uniProgress / 100f

        wrappedNode?.scale = Float3(
            baseScale.x * xMult * universalFactor,
            baseScale.y * yMult * universalFactor,
            baseScale.z * zMult * universalFactor
        )
        dimensionOverlay?.refresh()
    }

    //maybe needed?


    //select + change many?

    //model storage for changing position?? like, zrzucasz na psek kilka kart i możesz wybrać którą otworzyć

}