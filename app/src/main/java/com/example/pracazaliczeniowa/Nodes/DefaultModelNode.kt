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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    /** The track index that is currently active (playing or paused). */
    var activeAnimationIndex: Int = 0
        private set

    // Per-track elapsed playback time in seconds — survives pause.
    private val elapsedTimes = mutableMapOf<Int, Float>()

    // The coroutine driving the manual animation loop; cancelled on stop/switch.
    private var animJob: Job? = null

    // Timestamp of the last frame tick — used to compute delta time.
    private var lastTickNanos: Long = 0L

    /**
     * Start (or resume) playback of [index] from the last paused position.
     * Drives the animation manually via applyAnimation so pause is exact.
     */
    fun resumeAnimation(index: Int) {
        if (!hasAnimations()) return
        val animator = modelInstance.animator ?: return
        val count = animator.animationCount
        if (index !in 0 until count) return

        // Cancel any running loop first.
        animJob?.cancel()
        activeAnimationIndex = index
        val duration = animator.getAnimationDuration(index)
        if (duration <= 0f) return

        lastTickNanos = System.nanoTime()

        animJob = scope.launch {
            while (isActive) {
                val now = System.nanoTime()
                val delta = (now - lastTickNanos) / 1_000_000_000f
                lastTickNanos = now

                val elapsed = (elapsedTimes[index] ?: 0f) + delta
                // Loop: wrap elapsed time within duration.
                elapsedTimes[index] = elapsed % duration

                animator.applyAnimation(index, elapsedTimes[index]!!)
                animator.updateBoneMatrices()

                delay(16) // ~60 fps
            }
        }
    }

    /**
     * Pause playback — freezes the model on the exact current frame.
     * Call resumeAnimation() to continue from here.
     */
    fun pauseAnimation() {
        if (!hasAnimations()) return
        animJob?.cancel()
        animJob = null
        // Pose is already frozen at the last applyAnimation call — nothing else needed.
    }

    /**
     * Stop all playback and reset elapsed times to 0.
     * Use on deselect / delete / stop button.
     */
    fun stopAllAnimations() {
        animJob?.cancel()
        animJob = null
        elapsedTimes.clear()
        activeAnimationIndex = 0
        // Reset pose to frame 0.
        val animator = modelInstance.animator ?: return
        if (animator.animationCount > 0) {
            animator.applyAnimation(0, 0f)
            animator.updateBoneMatrices()
        }
    }

    /** Legacy helper kept for compatibility. */
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