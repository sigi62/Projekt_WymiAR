package com.example.pracazaliczeniowa.Nodes

import android.util.Log
import androidx.compose.ui.graphics.Color
import com.example.pracazaliczeniowa.Nodes.DefaultModelNode
import com.example.pracazaliczeniowa.Nodes.DimensionOverlayNode
import com.google.android.filament.Engine
import com.google.ar.sceneform.rendering.ViewAttachmentManager
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SelectedModelNode(
    engine: Engine,
    private val scope: CoroutineScope
) : Node(engine) {

    private var wrappedNode: DefaultModelNode? = null

    /**
     * The name of the profile slot currently applied to this model.
     * [ProfilePickerDialog.SLOT_DEFAULT] for the default slot, a user-chosen
     * name for named slots, null if no profile has been loaded/saved yet.
     * Kept here (on the wrapper) so it survives dialog open/close cycles
     * without touching [DefaultModelNode].
     */
    var activeProfileName: String? = null

    private var isDimensionsVisible = false
    private var dimensionOverlay: DimensionOverlayNode? = null

    private var initialWorldPos = Float3(0f)
    private var initialWorldQuat = Quaternion()
    private var baseScale = Float3(1f)
    private var baseRotation = Float3(0f)
    private var cachedSceneView: SceneView? = null

    fun getWrappedNode(): DefaultModelNode? = wrappedNode

    /** Exposes the overlay so the Activity can read dimensions for the HUD. */
    fun getDimensionOverlay(): DimensionOverlayNode? = dimensionOverlay

    // ── Animation delegation ──────────────────────────────────────────────────

    /** Delegates to the wrapped node — true if the model has animation tracks. */
    fun hasAnimations(): Boolean = wrappedNode?.hasAnimations() ?: false

    /** Returns the number of animation tracks on the wrapped model (0 if none). */
    fun getAnimationCount(): Int =
        wrappedNode?.modelInstance?.animator?.animationCount ?: 0

    /**
     * Delegates play/stop to the wrapped [DefaultModelNode].
     * Safe to call when no node is attached or the model has no animations.
     */
    fun setAnimationPlaying(playing: Boolean) {
        wrappedNode?.setAnimationPlaying(playing)
    }

    /** Pause the active animation track, freezing the model at its current pose. */
    fun pauseAnimation() {
        wrappedNode?.pauseAnimation()
    }

    /** Resume (or start) the animation track at [index] from its last paused position. */
    fun resumeAnimation(index: Int) {
        wrappedNode?.resumeAnimation(index)
    }

    /** Stop all tracks and clear pause state — call on deselect / delete. */
    fun stopAllAnimations() {
        wrappedNode?.stopAllAnimations()
    }

    /**
     * Plays a specific animation track by index.
     * Stops any currently playing track first so tracks don't overlap.
     */
    fun playAnimation(index: Int) {
        wrappedNode?.resumeAnimation(index)
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
     * Re-anchors [baseScale] to the node's current physical scale.
     * Call after saving/loading a profile so subsequent slider movement is
     * relative to the committed scale, not the original.
     */
    fun syncBaseScale() {
        baseScale = wrappedNode?.scale ?: Float3(1f)
    }

    /**
     * Re-anchors [initialWorldQuat] to the wrapper's current world quaternion
     * so that [updateRotation] computes slider deltas from the right base.
     * Always call after [applyProfileRotation] or any direct rotation change.
     */
    fun syncBaseRotation() {
        initialWorldQuat = this.worldQuaternion
    }

    fun moveTo(pos: Float3) {
        this.worldPosition = pos
        this.initialWorldPos = pos
    }

    fun updateRotation(xDeg: Float, yDeg: Float, zDeg: Float) {
        val extraRot = Quaternion.fromEuler(Float3(xDeg, yDeg, zDeg))
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
    // Rotation handle – pointerRing.glb (flat ring with forward triangle)
    //
    // The .glb is authored at radius = 1.0 unit, flat on the XZ plane,
    // with the triangle pointer aimed toward +Z (glTF forward).
    // We scale it the same way the old CylinderNode was scaled:
    //   handle.scale = Float3(outerR, 1f, outerR)
    // so no changes to the sizing math are needed.
    // ---------------------------------------------------------------

    private var rotationHandle: ModelNode? = null

    /**
     * Loads pointerRing.glb from assets and attaches it as the rotation handle.
     * The load is async; [refreshRingScale] is called once the model is ready
     * so the ring immediately fits the wrapped node.
     */
    fun showRotationHandle(engine: Engine, sceneView: SceneView) {
        if (rotationHandle != null) return
        val target = wrappedNode ?: return
        cachedSceneView = sceneView

        scope.launch {
            // loadModelGlb returns a ModelInstance (nullable on failure).
            val instance = sceneView.modelLoader.loadModelInstance(
                fileLocation = "RotationHandle.glb"
            ) ?: run {
                Log.w("SelectedModelNode", "pointerRing.glb failed to load")
                return@launch
            }

            val handle = ModelNode(
                modelInstance = instance,
                autoAnimate   = false,
                scaleToUnits  = null,   // we scale manually in refreshRingScale
                centerOrigin  = null    // keep the model's own pivot
            ).apply {
                name       = "rotation_handle"
                isEditable = false
            }

            rotationHandle = handle
            this@SelectedModelNode.addChildNode(handle)

            // Size it immediately once geometry is available.
            refreshRingScale()
        }
    }
    fun refreshRingScale() {
        val handle = rotationHandle ?: return
        val target = wrappedNode ?: return

        val outerR = computeOuterRadius(target)
        val yOff   = computeYOffset(target)

        // Same math as the old CylinderNode (radius = 1 unit in the .glb):
        // X and Z scale the ring outward; Y stays 1 so the flat geometry
        // isn't squashed.
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

    fun setBaseScale(base: Float3) {
        baseScale = base
    }
    fun setBaseRotation(euler: Float3) {
        baseRotation = euler
        initialWorldQuat = Quaternion.fromEuler(euler)  // slider delta is relative to this
    }

    /**
     * Applies a saved profile rotation to this wrapper node so that the
     * rotation handle ring always stays correctly positioned beneath the model.
     *
     * Rotation must live on the **wrapper** (this node), NOT on the wrapped
     * child. When rotation is set on the child directly the ring — which is a
     * sibling child of the wrapper — stays flat and disconnected from the
     * tilted model.  By rotating the wrapper the ring, the model, and all
     * other children move together as one rigid body.
     *
     * After calling this, [syncBaseRotation] re-anchors [initialWorldQuat] so
     * that slider deltas start from the new orientation, and the wrapped
     * child's local rotation is reset to zero (it was only ever used as a
     * temporary staging value during pre-wrap placement).
     */
    fun applyProfileRotation(euler: Float3) {
        // Rotate the wrapper in world space (same convention as updateRotation).
        this.worldQuaternion = Quaternion.fromEuler(euler)
        // Clear any residual local rotation on the child — the wrapper owns it now.
        wrappedNode?.rotation = Float3(0f)
        // Re-anchor so slider deltas are relative to this orientation.
        initialWorldQuat = this.worldQuaternion
    }
    companion object {
        const val PINCH_SENSITIVITY = 2.0f
    }
}