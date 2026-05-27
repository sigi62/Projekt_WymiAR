package com.example.WymiAR.Nodes

import android.view.Choreographer
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.CoroutineScope



class DefaultModelNode(
    val modelId: String,
    val modelPath: String,
    modelInstance: ModelInstance,
    private val scope: CoroutineScope,
    private val sceneView: ARSceneView,
) : ModelNode(
    modelInstance = modelInstance,
    autoAnimate = false
) {

    var profileApplied: Boolean = false
    var currentRelativeRotation: Float3 = Float3(0f)
    var currentRelativeScale: Float3 = Float3(1f)
    fun hasAnimations(): Boolean =
        (modelInstance.animator?.animationCount ?: 0) > 0

    var activeAnimationIndex: Int = 0
        private set
    private val elapsedTimes = mutableMapOf<Int, Float>()
    private var lastTickNanos: Long = 0L
    private var isAnimating: Boolean = false
    private val choreographer = Choreographer.getInstance()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAnimating) return

            val animator = modelInstance.animator ?: return
            val duration = animator.getAnimationDuration(activeAnimationIndex)
            if (duration <= 0f) return

            val delta = (frameTimeNanos - lastTickNanos) / 1_000_000_000f
            lastTickNanos = frameTimeNanos

            val elapsed = (elapsedTimes[activeAnimationIndex] ?: 0f) + delta
            elapsedTimes[activeAnimationIndex] = elapsed % duration

            animator.applyAnimation(activeAnimationIndex, elapsedTimes[activeAnimationIndex]!!)
            animator.updateBoneMatrices()

            choreographer.postFrameCallback(this)
        }
    }

    fun resumeAnimation(index: Int) {
        if (!hasAnimations()) return
        val animator = modelInstance.animator ?: return
        val count = animator.animationCount
        if (index !in 0 until count) return

        stopAnimating()
        activeAnimationIndex = index
        val duration = animator.getAnimationDuration(index)
        if (duration <= 0f) return

        lastTickNanos = System.nanoTime()
        isAnimating = true
        choreographer.postFrameCallback(frameCallback)
    }

    fun pauseAnimation() {
        if (!hasAnimations()) return
        stopAnimating()
    }

    fun stopAllAnimations() {
        stopAnimating()
        elapsedTimes.clear()
        activeAnimationIndex = 0
        val animator = modelInstance.animator ?: return
        if (animator.animationCount > 0) {
            animator.applyAnimation(0, 0f)
            animator.updateBoneMatrices()
        }
    }

    // Centralised helper — cancels the VSync loop without touching animation state.
    private fun stopAnimating() {
        isAnimating = false
        choreographer.removeFrameCallback(frameCallback)
    }

    fun setAnimationPlaying(playing: Boolean) {
        if (playing) resumeAnimation(activeAnimationIndex) else pauseAnimation()
    }

    fun getModeleName(): String {
        return modelPath.substringAfterLast("/").substringBeforeLast(".")
    }
    fun wrapAsSelected(scope: CoroutineScope): SelectedModelNode? {
        val parentNode = this.parent ?: return null

        val worldPos = this.worldPosition
        val worldRot = this.worldQuaternion
        val worldScl = this.worldScale

        val wrapper = SelectedModelNode(engine, scope)

        parentNode.addChildNode(wrapper)

        wrapper.attachNode(this)
        wrapper.worldPosition = worldPos
        wrapper.worldQuaternion = worldRot

        wrapper.showRotationHandle(engine, sceneView)

        this.position = Float3(0f)
        this.quaternion = dev.romainguy.kotlin.math.Quaternion()

        return wrapper
    }


}