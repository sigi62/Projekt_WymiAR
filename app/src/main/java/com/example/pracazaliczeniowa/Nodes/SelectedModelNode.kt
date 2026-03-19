package com.example.pracazaliczeniowa.Nodes

import androidx.compose.ui.graphics.Color
import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.CoroutineScope
/**
 * Selected node with extra overlays: highlight, width-depth plane, and height line.
 * Wraps an existing DefaultModelNode without changing its transform or scale.
 */
class SelectedModelNode(
    engine: Engine,
    private val scope: CoroutineScope
) : Node(engine)
{
    private var wrappedNode: DefaultModelNode? = null
    private var boundingBoxNode: CubeNode? = null
    fun unwrap(): DefaultModelNode? {

        val child = wrappedNode ?: return null
        val currentAnchor = this.parent ?: return null

        // 1. Capture state
        val pos = this.worldPosition
        val rot = this.worldQuaternion
        val localScale = child.scale

        // 3. Re-parent
        this.removeChildNode(child)
        currentAnchor.addChildNode(child)

        child.worldPosition = pos
        child.worldQuaternion = rot

        boundingBoxNode?.destroy()
        boundingBoxNode = null

        currentAnchor.removeChildNode(this)
        this.destroy() // Clean up the wrapper node
        return child
    }

    fun attachNode(node: DefaultModelNode) {
        this.wrappedNode = node

        if (node.parent != this) {
            node.parent?.removeChildNode(node)
            this.addChildNode(node)
        }

        this.scale = Float3(1.0f)

        val box = node.modelInstance?.asset?.boundingBox
        if (box != null) {
            val center = Float3(box.center[0], box.center[1], box.center[2])
            val halfExtent = Float3(box.halfExtent[0], box.halfExtent[1], box.halfExtent[2])

            // Create the selection "Frame"
            // IMPORTANT: We multiply the box size by the node's scale
            // because the box is calculated from the raw mesh data.
            boundingBoxNode = CubeNode(engine, size = Float3(1.0f), center = Float3(0.0f)).apply {
                this.position = center * node.scale
                this.scale = (halfExtent * 2.1f) * node.scale

                this.isPositionEditable = false
                this.isRotationEditable = false
                this.isScaleEditable = false
            }
            addChildNode(boundingBoxNode!!)
        }

    }

}