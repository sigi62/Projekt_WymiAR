package com.example.pracazaliczeniowa.Nodes

import androidx.compose.ui.graphics.Color
import com.google.android.filament.Engine
import com.google.ar.sceneform.rendering.ViewAttachmentManager
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.CoroutineScope

class SelectedModelNode(
    engine: Engine,
    private val scope: CoroutineScope
) : Node(engine) {

    private var wrappedNode: DefaultModelNode? = null

    private var isDimensionsVisible = false
    private var dimensionOverlay: DimensionOverlayNode? = null

    private var initialWorldPos = Float3(0f)
    private var initialWorldQuat = dev.romainguy.kotlin.math.Quaternion()
    private var baseScale = Float3(1f)

    private var cachedSceneView: SceneView? = null

    fun getWrappedNode(): DefaultModelNode? = wrappedNode

    /** Exposes the overlay so the Activity can read dimensions for the HUD. */
    fun getDimensionOverlay(): DimensionOverlayNode? = dimensionOverlay

    // ── Animation delegation ──────────────────────────────────────────────────

    /** Delegates to the wrapped node — true if the model has animation tracks. */
    fun hasAnimations(): Boolean = wrappedNode?.hasAnimations() ?: false

    /**
     * Delegates play/stop to the wrapped [DefaultModelNode].
     * Safe to call when no node is attached or the model has no animations.
     */
    fun setAnimationPlaying(playing: Boolean) {
        wrappedNode?.setAnimationPlaying(playing)
    }

    // ── Wrap / unwrap ─────────────────────────────────────────────────────────

    fun unwrap(): DefaultModelNode? {
        val child = wrappedNode ?: return null
        val currentAnchor = this.parent ?: return null

        dimensionOverlay?.let {
            this.removeChildNode(it)
            it.destroy()
            dimensionOverlay = null
        }

        hideRotationHandle()

        val pos = this.worldPosition
        val rot = this.worldQuaternion
        val localScale = child.scale

        this.removeChildNode(child)
        currentAnchor.addChildNode(child)

        child.worldPosition = pos
        child.worldQuaternion = rot
        child.scale = localScale

        currentAnchor.removeChildNode(this)
        this.destroy()
        return child
    }

    fun attachNode(node: DefaultModelNode) {
        this.wrappedNode = node
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
        hideDimensions()
        dimensionOverlay = DimensionOverlayNode(
            engine = engine,
            materialLoader = sceneView.materialLoader,
            targetNode = node
        )
        this.addChildNode(dimensionOverlay!!)
        isDimensionsVisible = true
    }

    private var initialPinchScale = 1.0f

    fun startPinching() {
        initialPinchScale = wrappedNode?.scale?.x ?: 1.0f
    }

    fun applyPinchScale(factor: Float) {
        val amplified = 1f + (factor - 1f) * PINCH_SENSITIVITY
        val newScale = (initialPinchScale * amplified).coerceIn(0.01f, 10f)
        wrappedNode?.scale = Float3(newScale)
        dimensionOverlay?.refresh()
        refreshRingScale()
    }

    /**
     * Re-anchors baseScale to the node's current physical scale.
     * Call after saving a profile + resetting sliders so that subsequent
     * slider movement is relative to the saved scale, not the original.
     */
    fun syncBaseScale() {
        baseScale = wrappedNode?.scale ?: Float3(1f)
    }

    fun moveTo(pos: Float3) {
        this.worldPosition = pos
        this.initialWorldPos = pos
    }

    fun updateRotation(xDeg: Float, yDeg: Float, zDeg: Float) {
        val extraRot = dev.romainguy.kotlin.math.Quaternion.fromEuler(Float3(xDeg, yDeg, zDeg))
        this.worldQuaternion = initialWorldQuat * extraRot
    }

    fun updatePosition(x: Float, y: Float, z: Float) {
        this.worldPosition = initialWorldPos + Float3(x, y, z)
    }

    fun updateScale(xMult: Float, yMult: Float, zMult: Float, uniProgress: Float) {
        wrappedNode?.scale = Float3(
            baseScale.x * xMult * uniProgress,
            baseScale.y * yMult * uniProgress,
            baseScale.z * zMult * uniProgress
        )
        dimensionOverlay?.refresh()
        refreshRingScale()
    }

    /**
     * Toggles the dimension wireframe overlay on/off.
     * Returns true if the overlay is now visible, false if it was just hidden.
     */
    fun toggleDimensions(sceneView: SceneView, viewAttachmentManager: ViewAttachmentManager): Boolean {
        val target = wrappedNode ?: return false
        return if (isDimensionsVisible) {
            hideDimensions()
            false
        } else {
            showDimensions(target, sceneView, viewAttachmentManager)
            true
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

    // ---------------------------------------------------------------
    // Rotation handle – flat CylinderNode, sized via Node.scale
    // ---------------------------------------------------------------

    private var rotationHandle: CylinderNode? = null

    fun showRotationHandle(engine: Engine, sceneView: SceneView) {
        if (rotationHandle != null) return
        val target = wrappedNode ?: return
        cachedSceneView = sceneView

        rotationHandle = CylinderNode(
            engine = engine,
            radius = 1f,
            height = 0.0001f,
            materialInstance = sceneView.materialLoader.createColorInstance(
                Color.White.copy(alpha = 0.5f)
            )
        ).apply {
            name = "rotation_handle"
            isEditable = false
        }

        this.addChildNode(rotationHandle!!)
        refreshRingScale()
    }

    fun refreshRingScale() {
        val handle = rotationHandle ?: return
        val target = wrappedNode ?: return

        val outerR = computeOuterRadius(target)
        val yOff   = computeYOffset(target)

        handle.scale    = Float3(outerR, 1f, outerR)
        handle.position = Float3(0f, yOff, 0f)
    }

    fun updateHandleSize() = refreshRingScale()

    fun hideRotationHandle() {
        rotationHandle?.let {
            this.removeChildNode(it)
            it.destroy()
            rotationHandle = null
        }
    }

    // ---------------------------------------------------------------
    // Sizing helpers
    // ---------------------------------------------------------------

    private fun computeOuterRadius(target: DefaultModelNode): Float {
        val box   = target.modelInstance.asset.boundingBox
        val s     = target.worldScale
        val halfW = box.halfExtent[0] * s.x
        val halfD = box.halfExtent[2] * s.z
        return maxOf(halfW, halfD) * 1.25f
    }

    private fun computeYOffset(target: DefaultModelNode): Float {
        val box     = target.modelInstance.asset.boundingBox
        val s       = target.worldScale
        val bottomY = (box.center[1] - box.halfExtent[1]) * s.y
        return bottomY + 0.005f
    }

    companion object {
        const val PINCH_SENSITIVITY = 2.0f
    }
}