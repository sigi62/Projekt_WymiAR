package com.example.pracazaliczeniowa.Nodes

import com.google.android.filament.Engine
import kotlinx.coroutines.CoroutineScope

/**
 * Utility to convert an AppModelNode into a MeasurableModelNode.
 *
 * The converted node wraps the existing modelNode and preserves
 * scale, position, and provides full MeasurableModelNode functionality.
 */
object AppModelNodeConverter {

    /**
     * Converts an AppModelNode into a MeasurableModelNode.
     *
     * @param appNode The source AppModelNode (tap-selectable node)
     * @param engine Filament engine
     * @param scope Coroutine scope for async model operations
     * @return A MeasurableModelNode wrapping the existing model
     */
    fun toMeasurableModelNode(
        appNode: AppModelNode,
        engine: Engine,
        scope: CoroutineScope
    ): MeasurableModelNode {

        val mmNode = MeasurableModelNode(engine, appNode.modelLoader, scope)

        // Attach existing ModelNode to the new MeasurableModelNode
        val childModel = appNode.modelNode
        if (childModel != null) {
            mmNode.addChildNode(childModel)
            mmNode.modelNode = childModel
        }

        // Copy position/scale
        mmNode.setLocalPosition(
            appNode.position.x,
            appNode.position.y,
            appNode.position.z
        )
        mmNode.setMetersScale(appNode.getMetersScale())

        // Copy bounding box dimensions immediately if loaded
        mmNode.onDimensionsChanged?.invoke(
            mmNode.widthMeters,
            mmNode.heightMeters,
            mmNode.depthMeters
        )

        return mmNode
    }
}