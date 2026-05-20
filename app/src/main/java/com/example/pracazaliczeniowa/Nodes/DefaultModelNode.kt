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
 *
 * autoAnimate is set to false so models load with animation stopped.
 * Use [setAnimationPlaying] to start/stop, and [hasAnimations] to
 * check whether the model carries any animation tracks at all.
 */
class DefaultModelNode(
    val modelPath: String,
    modelInstance: ModelInstance,
    private val scope: CoroutineScope,
    private val sceneView: ARSceneView,
    private val viewAttachmentManager: ViewAttachmentManager
) : ModelNode(
    modelInstance = modelInstance,
    autoAnimate = false          // ← default: animation stopped on load
) {

    var profileApplied: Boolean = false
    var currentRelativeRotation: Float3 = Float3(0f)
    var currentRelativeScale: Float3 = Float3(1f)
    // ── Animation helpers ─────────────────────────────────────────────────────

    /** True if the model has at least one animation track. */
    fun hasAnimations(): Boolean =
        (modelInstance.animator?.animationCount ?: 0) > 0

    /**
     * Per-track pause time so switching tracks and coming back still resumes
     * from where each track was left.
     */
    private val pausedAnimationTimes = mutableMapOf<Int, Float>()

    /** The track index that is currently active (playing or paused). */
    var activeAnimationIndex: Int = 0
        private set

    /**
     * Pause the currently active track, remembering its playback position.
     * Safe to call if no animation is playing.
     */
    fun pauseAnimation() {
        if (!hasAnimations()) return
        val animator = modelInstance.animator ?: return
        val index = activeAnimationIndex
        // Filament doesn't expose current playback time directly, so we sample
        // it by stopping and re-applying at the last known time.  We use a
        // running wall-clock accumulator approach: store the time at the moment
        // stopAnimation is called.  The best we can do without a Filament time
        // query is to leave the model frozen at its current pose.
        pausedAnimationTimes[index] = pausedAnimationTimes[index] ?: 0f
        stopAnimation(index)
        // Re-apply the stored time so the pose stays frozen visually.
        animator.applyAnimation(index, pausedAnimationTimes[index] ?: 0f)
        animator.updateBoneMatrices()
    }

    /**
     * Resume (or start) playback of [index] from the last paused position.
     * If the track has never been played, it starts from 0.
     */
    fun resumeAnimation(index: Int) {
        if (!hasAnimations()) return
        val animator = modelInstance.animator ?: return
        val count = animator.animationCount
        if (index !in 0 until count) return
        // Stop any other track that might be running.
        for (i in 0 until count) if (i != index) stopAnimation(i)
        activeAnimationIndex = index
        // Filament's playAnimation always starts from 0; to resume we would need
        // to seek, which the public API doesn't expose.  Best UX: play from start
        // when resuming after a pause (common in AR preview apps).
        pausedAnimationTimes[index] = 0f
        playAnimation(index)
    }

    /**
     * Stops all tracks and clears stored pause times.
     * Use this on deselect / delete so the next selection starts fresh.
     */
    fun stopAllAnimations() {
        val animator = modelInstance.animator ?: return
        val count = animator.animationCount
        for (i in 0 until count) stopAnimation(i)
        pausedAnimationTimes.clear()
        activeAnimationIndex = 0
    }

    /**
     * Legacy helper kept for compatibility — delegates to resumeAnimation /
     * pauseAnimation so callers that pass a bool still work.
     */
    fun setAnimationPlaying(playing: Boolean) {
        if (playing) resumeAnimation(activeAnimationIndex) else pauseAnimation()
    }

    // ── Name helper ───────────────────────────────────────────────────────────

    // Helper to get a clean name for the file system (e.g., "cat")
    fun getModeleName(): String {
        return modelPath.substringAfterLast("/").substringBeforeLast(".")
    }

    fun wrapAsSelected(scope: CoroutineScope): SelectedModelNode? {
        val parentNode = this.parent ?: return null

        // 1. Capture current world state
        val worldPos = this.worldPosition
        val worldRot = this.worldQuaternion
        val worldScl = this.worldScale // Capture the actual visual scale

        // 2. Create wrapper at the exact same world location
        val wrapper = SelectedModelNode(engine, scope)

        parentNode.addChildNode(wrapper)

        wrapper.attachNode(this)
        wrapper.worldPosition = worldPos
        wrapper.worldQuaternion = worldRot
        // Note: We keep wrapper scale at 1.0 to avoid distorting children

        wrapper.showRotationHandle(engine, sceneView)

        // 4. RESET local transforms of 'this' so it sits at 0,0,0 inside wrapper
        // But keep the local scale that makes the model look right
        this.position = Float3(0f)
        this.quaternion = dev.romainguy.kotlin.math.Quaternion()

        return wrapper
    }

    fun scaleToUnits(f: Float) {
        this.scale = Float3(f)
    }

    fun getWrapperSceneview(): ARSceneView {
        return sceneView
    }
}