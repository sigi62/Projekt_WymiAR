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

    fun attachNode(node: DefaultModelNode, sceneView: SceneView, viewAttachmentManager: ViewAttachmentManager
    ) {
        this.wrappedNode = node

        if (node.parent != this) {
            node.parent?.removeChildNode(node)
            this.addChildNode(node)
        }

        this.scale = Float3(1.0f)
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

    override var scale: Float3
        get() = super.scale
        set(value) {
            super.scale = value
            // The wrappedNode's visual dimensions change because its parent (this) scaled
            dimensionOverlay?.refresh()
        }
    // gizmo doens't show up - chagge back to SelectedModelNode??

    //select + change many?

    //model storage for changing position?? like, zrzucasz na psek kilka kart i możesz wybrać którą otworzyć

}