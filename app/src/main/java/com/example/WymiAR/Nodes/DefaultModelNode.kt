package com.example.WymiAR.Nodes

import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch



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
    private var animJob: Job? = null
    private var lastTickNanos: Long = 0L

    fun resumeAnimation(index: Int) {
        if (!hasAnimations()) return
        val animator = modelInstance.animator ?: return
        val count = animator.animationCount
        if (index !in 0 until count) return

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
                elapsedTimes[index] = elapsed % duration

                animator.applyAnimation(index, elapsedTimes[index]!!)
                animator.updateBoneMatrices()

                delay(16)
            }
        }
    }

    fun pauseAnimation() {
        if (!hasAnimations()) return
        animJob?.cancel()
        animJob = null
    }

    fun stopAllAnimations() {
        animJob?.cancel()
        animJob = null
        elapsedTimes.clear()
        activeAnimationIndex = 0
        val animator = modelInstance.animator ?: return
        if (animator.animationCount > 0) {
            animator.applyAnimation(0, 0f)
            animator.updateBoneMatrices()
        }
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