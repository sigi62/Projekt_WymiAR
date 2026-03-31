package com.example.pracazaliczeniowa.Nodes

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.example.pracazaliczeniowa.Overlays.DistanceUnit
import com.example.pracazaliczeniowa.R
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.gltfio.MaterialProvider
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
import java.nio.ByteBuffer

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

    private var isDimensionsVisible = false // ✅ State stored here
    private var dimensionOverlay: DimensionOverlayNode? = null

    private var isDragging = false


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

    fun showDimensions(node: DefaultModelNode, sceneView: SceneView, viewAttachmentManager: ViewAttachmentManager) {
        hideDimensions() // Clean up existing first
        dimensionOverlay = DimensionOverlayNode(
            engine = engine,
            modelLoader = sceneView.modelLoader,
            materialLoader = sceneView.materialLoader,
            context = sceneView.context,
            viewAttachmentManager = viewAttachmentManager,
            targetNode = node
        )
        this.addChildNode(dimensionOverlay!!)
        isDimensionsVisible = true
    }

    // Inside SelectedModelNode class
    private var initialPinchScale = 1.0f

    fun startPinching() {
        // Capture current scale of the wrapped node as the starting point
        initialPinchScale = wrappedNode?.scale?.x ?: 1.0f
    }

    fun applyPinchScale(factor: Float) {
        val newScale = initialPinchScale * factor
        // Optional: add coerceIn(0.01f, 5.0f) to prevent disappearing or massive models
        wrappedNode?.scale = Float3(newScale)
        dimensionOverlay?.refresh()
        updateHandleSize()
    }

    fun moveTo(pos: Float3) {
        // We move the SelectedModelNode (the wrapper), which moves everything inside
        this.worldPosition = pos
        // Update initialWorldPos so slider offsets remain relative to the new drop point
        this.initialWorldPos = pos
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
        val universalFactor = uniProgress

        wrappedNode?.scale = Float3(
            baseScale.x * xMult * universalFactor,
            baseScale.y * yMult * universalFactor,
            baseScale.z * zMult * universalFactor
        )
        dimensionOverlay?.refresh()
        updateHandleSize()
    }

    fun toggleDimensions(sceneView: SceneView, viewAttachmentManager: ViewAttachmentManager) {
        val target = wrappedNode ?: return

        if (isDimensionsVisible) {
            hideDimensions()
        } else {
            showDimensions(target, sceneView, viewAttachmentManager)
            isDimensionsVisible = true
        }
    }

    private fun hideDimensions() {
        dimensionOverlay?.let {
            this.removeChildNode(it)
            it.destroy()
            dimensionOverlay = null
        }
        isDimensionsVisible = false
    }

    private var rotationHandle: Node? = null

    fun showRotationHandle(engine: Engine, sceneView: SceneView) {
        if (rotationHandle != null) return
        val target = wrappedNode ?: return

        val rawHalfExtents = target.boundingBox.halfExtent
        val currentWorldScale = target.worldScale
        val maxHorizontalRadius = maxOf(rawHalfExtents[0] * currentWorldScale.x, rawHalfExtents[2] * currentWorldScale.z)

        val ringSize = maxHorizontalRadius * 2.5f // Make it slightly larger for easier grabbing
        // 2. Create a Disc
        // Using a Cylinder/Sphere flattened on the Y axis makes a perfect circle

        rotationHandle = PlaneNode(
            engine = engine,
            size = Float3(ringSize, 0.0f, ringSize),
            // Use a transparent ring PNG here to make the center "hollow"
            materialInstance = sceneView.materialLoader.createColorInstance(
                androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f)
                )
            ).apply {
            name = "rotation_handle"
            position = Float3(0f, 0.01f, 0f) // Slightly above floor
            isEditable = false
        }

        this.addChildNode(rotationHandle!!)
        updateHandleSize()
    }

    fun updateHandleSize() {
        val target = wrappedNode ?: return
        val handle = rotationHandle as? PlaneNode ?: return
        // 1. Get raw dimensions (in model-space meters)
        val box = target.modelInstance.asset.boundingBox
        val s = target.scale

        // 2. Calculate current world dimensions in METERS (just like DimensionOverlayNode)
        // We multiply raw extent by the scale to get actual size
        val w = box.halfExtent[0] * 10f * s.x
        val d = box.halfExtent[2] * 10f * s.z

        // 3. Determine the radius.
        // We use a multiplier (e.g., 1.5f) to ensure the ring is outside the model
        val multiplier = 1.5f
        val ringSize = maxOf(w, d) * multiplier

        // 4. Set the scale.
        // IMPORTANT: Since PlaneNode's 'size' is often 1m x 1m,
        // we set the scale to the exact meter size we want.
        handle.scale = Float3(ringSize, 1.0f, ringSize)

        val scaledCenter = Float3(box.center[0] * s.x, 0.01f, box.center[2] * s.z)
        handle.position = scaledCenter
    }


    fun hideRotationHandle() {
        rotationHandle?.let {
            this.removeChildNode(it)
            it.destroy()
            rotationHandle = null
        }
    }

    //TODO - rotation handle shrinking while
    //select + change many?

    //model storage for changing position?? like, zrzucasz na psek kilka kart i możesz wybrać którą otworzyć



}